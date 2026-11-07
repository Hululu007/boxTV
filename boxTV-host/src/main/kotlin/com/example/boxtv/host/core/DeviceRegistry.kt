package com.example.boxtv.host.core

import java.security.SecureRandom
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 设备注册模块（core 层）
 *
 * 管理已配对电视设备及其 Token，对应细则 4.2 节「配对 + Token」机制：
 * - 配对成功（电脑端允许）后签发 32 位随机十六进制 Token，与 deviceUuid 一一对应并持久化；
 * - 电视端后续请求携带 X-BoxTV-Token 头，[findByToken] 负责校验；
 * - 电脑端「设备管理」移除设备 = 删除该记录，对应电视端立即 401。
 */
class DeviceRegistry(private val store: ConfigStore) {

    private val mutex = Mutex() // 与 ConfigStore 的写盘互斥保持独立，仅保护 Token 生成

    /** SecureRandom 用于生成高强度 Token（≥128bit 熵，满足细则 7 安全要求） */
    private val secureRandom = SecureRandom()

    /**
     * 按 Token 查找设备（鉴权入口）。
     * 命中后顺带更新内存中的 lastConnectedAt（由 ConfigStore 定时批量落盘）。
     */
    suspend fun findByToken(token: String): PairedDevice? {
        val device = store.peek().devices.firstOrNull { it.token == token }
        if (device != null) {
            store.touchDeviceLastSeen(device.deviceUuid)
        }
        return device
    }

    /** 列出全部已配对设备（设备管理 Tab 使用） */
    fun all(): List<PairedDevice> = store.peek().devices

    /**
     * 注册新设备：生成 Token 并立即落盘。
     * 若同一 deviceUuid 已存在（重复配对），覆盖旧记录并签发新 Token。
     */
    suspend fun registerDevice(deviceUuid: String, name: String, model: String): PairedDevice {
        val token = mutex.withLock { generateToken() }
        val device = PairedDevice(
            deviceUuid = deviceUuid,
            name = name,
            model = model,
            token = token,
            pairedAt = System.currentTimeMillis()
        )
        store.update {
            // 移除同 UUID 的旧记录后追加，保证 UUID 唯一
            it.copy(devices = it.devices.filter { d -> d.deviceUuid != deviceUuid } + device)
        }
        return device
    }

    /**
     * 撤销设备授权：删除记录使其 Token 立即失效，立即落盘。
     * @return 是否成功删除（设备不存在返回 false）
     */
    suspend fun removeDevice(deviceUuid: String): Boolean {
        var removed = false
        store.update {
            val filtered = it.devices.filter { d -> d.deviceUuid != deviceUuid }
            removed = filtered.size != it.devices.size
            it.copy(devices = filtered)
        }
        return removed
    }

    /** 生成 16 字节随机数并转为 32 位十六进制字符串 */
    private fun generateToken(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
