package com.example.boxtv.host.core

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 配对会话模块（core 层）—— 2026-11-08 配对机制重构（免输入配对码）
 *
 * 新配对流程：电视端选中主机发起配对请求 → 电脑端弹出配对通知
 * （设备名 + 请求发起时间 + 300 秒有效等待时长）→ 电脑端用户点击
 * 「允许配对」后电视端轮询即拿到 Token 完成配对（全程无需任何配对码输入）；
 * 点击「拒绝配对」则会话作废并记录拒绝标记；300 秒内无任何操作则请求自动失效。
 *
 * 核心约束：
 * - 同一时刻最多存在 1 个待确认配对（新请求会替换旧会话）；
 * - 配对完成后会话立即作废（一次性使用）；
 * - activeSession 状态流供主窗口实时展示配对通知与倒计时。
 */
class PairingSessionManager {

    /** 配对请求有效期（毫秒）：300 秒内电脑端未处理则自动失效 */
    private companion object {
        const val SESSION_TTL_MS = 300_000L

        /** 「已拒绝」记录的保留时长（毫秒）：供电视端下一次轮询识别拒绝结果 */
        const val REJECTED_TTL_MS = 300_000L
    }

    /** 当前活动会话（volatile 保证多线程可见：HTTP 线程写入，UI 线程读取） */
    @Volatile
    private var session: PairingSession? = null

    /** 已拒绝会话记录：pairingId → 拒绝时间戳（电视端轮询时识别「电脑端已拒绝」） */
    private val rejectedSessions = ConcurrentHashMap<String, Long>()

    /** 活动会话响应式流：主窗口订阅实时展示配对通知；会话作废/替换时发布 null/新会话 */
    private val _activeSession = MutableStateFlow<PairingSession?>(null)
    val activeSession: StateFlow<PairingSession?> = _activeSession

    /**
     * 配对会话数据
     *
     * @param pairingId  会话唯一标识（UUID），电视端轮询配对结果时回传
     * @param deviceUuid 请求配对的电视设备唯一标识
     * @param deviceName 设备名称（配对通知展示）
     * @param deviceModel 设备型号
     * @param createdAt  请求发起时间戳（毫秒，配对通知展示）
     * @param expiresAt  过期时间戳（毫秒，300 秒有效等待时长）
     * @param approved   电脑端是否已点击「允许配对」（未允许前电视端轮询返回 Pending）
     */
    data class PairingSession(
        val pairingId: String,
        val deviceUuid: String,
        val deviceName: String,
        val deviceModel: String,
        val createdAt: Long,
        val expiresAt: Long,
        val approved: Boolean = false
    )

    /** 电视端配对结果（区分「等待允许/已拒绝」，供接口返回不同业务码） */
    sealed class VerifyOutcome {
        /** 配对成功，返回会话数据（含 deviceUuid 等，用于签发 Token） */
        data class Approved(val session: PairingSession) : VerifyOutcome()

        /** 电脑端尚未允许：电视端应提示并自动轮询重试 */
        data object Pending : VerifyOutcome()

        /** 电脑端已拒绝本次配对：电视端提示用户，可重新发起 */
        data object Rejected : VerifyOutcome()

        /** 会话不存在/已超时失效 */
        data object Failed : VerifyOutcome()
    }

    /**
     * 发起配对：创建新会话并替换旧会话（旧请求即被顶替失效）。
     * 同时清理过期的拒绝记录，避免长期占用内存。
     * @return 新创建的会话（含请求发起时间与过期时间）
     */
    fun begin(deviceUuid: String, deviceName: String, deviceModel: String): PairingSession {
        val now = System.currentTimeMillis()
        rejectedSessions.entries.removeIf { now - it.value > REJECTED_TTL_MS }
        val s = PairingSession(
            pairingId = java.util.UUID.randomUUID().toString(),
            deviceUuid = deviceUuid,
            deviceName = deviceName,
            deviceModel = deviceModel,
            createdAt = now,
            expiresAt = now + SESSION_TTL_MS
        )
        session = s
        _activeSession.value = s // 通知主窗口弹出配对通知（含允许/拒绝按钮与倒计时）
        return s
    }

    /** 电脑端点击「允许配对」：标记会话已允许，电视端下一次轮询即拿到 Token 完成配对。
     *  @return 是否成功标记（会话不存在/已超时失效返回 false） */
    fun approve(pairingId: String): Boolean {
        val s = session ?: return false
        if (s.pairingId != pairingId) return false
        if (System.currentTimeMillis() > s.expiresAt) return false
        session = s.copy(approved = true)
        _activeSession.value = session // 刷新 UI（按钮区变为「已允许」等待提示）
        return true
    }

    /** 电脑端点击「拒绝配对」：作废会话并记录拒绝标记（电视端轮询识别为 40902） */
    fun reject(pairingId: String) {
        val s = session ?: return
        if (s.pairingId != pairingId) return
        session = null
        _activeSession.value = null
        rejectedSessions[pairingId] = System.currentTimeMillis()
    }

    /**
     * 查询配对结果（电视端每 2 秒轮询一次）。
     * 判定顺序：已拒绝记录 → 会话存在性 → 未超时 → 已允许。
     * 配对成功后会话立即作废（一次性使用）。
     */
    fun verify(pairingId: String): VerifyOutcome {
        // 电脑端已拒绝：即使会话已作废也要告知电视端拒绝结果
        if (rejectedSessions.remove(pairingId) != null) {
            return VerifyOutcome.Rejected
        }
        val s = session ?: return VerifyOutcome.Failed
        if (s.pairingId != pairingId) return VerifyOutcome.Failed
        if (System.currentTimeMillis() > s.expiresAt) {
            session = null // 已超时会话直接清理（请求自动失效）
            _activeSession.value = null
            return VerifyOutcome.Failed
        }
        if (!s.approved) return VerifyOutcome.Pending // 等待电脑端「允许配对」
        session = null // 一次性使用：配对成功立即作废
        _activeSession.value = null
        return VerifyOutcome.Approved(s)
    }
}
