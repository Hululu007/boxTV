package com.example.boxtv.data.repository

import kotlinx.coroutines.flow.StateFlow
import com.example.boxtv.data.local.LocalStore
import com.example.boxtv.data.remote.ApiResult
import com.example.boxtv.data.remote.HostApi
import com.example.boxtv.data.remote.PairRequestData
import com.example.boxtv.data.remote.PairVerifyData
import com.example.boxtv.data.remote.PingData
import com.example.boxtv.data.remote.UpdateCheckData
import com.example.boxtv.model.Folder
import com.example.boxtv.model.PairedHost
import com.example.boxtv.model.PlaybackProgress
import com.example.boxtv.model.VideoEntry
import com.example.boxtv.model.VideoMeta

/**
 * 数据仓库模块（data/repository 层）
 *
 * 细则 5.2 节「统一数据出口，UI 不直接接触网络」：
 * - UI 层（Activity/Fragment）只依赖 [HostRepository]；
 * - 网络调用（HostApi）与本地持久化（LocalStore）在此编排；
 * - 配对成功 → addPairedHost 落库；鉴权成功 → touchHost 更新最近连接。
 */
class HostRepository(
    private val api: HostApi,
    private val store: LocalStore
) {

    /** 已配对主机流（主页实时刷新） */
    val pairedHosts: StateFlow<List<PairedHost>> = store.hosts

    /** 电视端自身唯一标识 */
    val deviceUuid: String get() = store.deviceUuid

    /** 移除已配对主机（本地清除） */
    suspend fun removeHost(hostDeviceId: String) = store.removeHost(hostDeviceId)

    // ------------------------------------------------------------------
    // 连接与配对
    // ------------------------------------------------------------------

    /** 健康检查（主页在线状态检测 / 手动连接验证） */
    suspend fun ping(host: String, port: Int): ApiResult<PingData> = api.ping(host, port)

    /** 发起配对：返回 pairingId 与过期时间 */
    suspend fun pairRequest(
        host: String,
        port: Int,
        deviceName: String,
        deviceModel: String
    ): ApiResult<PairRequestData> =
        api.pairRequest(host, port, store.deviceUuid, deviceName, deviceModel)

    /** 轮询配对结果；电脑端允许后立即持久化主机与 Token */
    suspend fun pairVerify(
        host: String,
        port: Int,
        pairingId: String,
        hostDeviceId: String
    ): ApiResult<PairedHost> {
        return when (val result = api.pairVerify(host, port, pairingId)) {
            is ApiResult.Ok -> {
                val saved = PairedHost(
                    hostDeviceId = hostDeviceId,
                    name = result.data.hostName.ifBlank { host },
                    host = host,
                    port = port,
                    token = result.data.token,
                    lastConnectedAt = System.currentTimeMillis()
                )
                store.addHost(saved)
                ApiResult.Ok(saved)
            }
            is ApiResult.ApiError -> result
            is ApiResult.NetworkError -> result
        }
    }

    // ------------------------------------------------------------------
    // 资源访问（自动携带 Token，并在鉴权成功后刷新最近连接时间）
    // ------------------------------------------------------------------

    /** 获取授权文件夹列表 */
    suspend fun folders(host: PairedHost): ApiResult<List<Folder>> =
        withAuthRefresh(host) { api.folders(host.host, host.port, host.token) }

    /** 浏览目录（path 为相对路径） */
    suspend fun browse(host: PairedHost, folderId: String, path: String): ApiResult<List<VideoEntry>> =
        withAuthRefresh(host) { api.browse(host.host, host.port, host.token, folderId, path) }

    /** 递归搜索视频 */
    suspend fun search(host: PairedHost, folderId: String, query: String): ApiResult<List<VideoEntry>> =
        withAuthRefresh(host) { api.search(host.host, host.port, host.token, folderId, query) }

    /**
     * 视频流地址（M3 播放使用；2026-11-07 播放器升级扩展清晰度与起播位置）。
     * - quality：null/1080p = 原画直连；720p/480p = 电脑端 ffmpeg 实时转码；
     * - startSec：转码流起播位置（秒）。
     */
    fun streamUrl(host: PairedHost, videoId: String, quality: String? = null, startSec: Double? = null): String =
        api.streamUrl(host.host, host.port, videoId, quality, startSec)

    /**
     * 视频元数据（时长/源片分辨率，电脑端 ffprobe 探测；2026-11-07 转码功能配套）。
     * 任何失败（网络/401/电脑端无 ffprobe）统一返回 null，调用方降级为原画直连。
     */
    suspend fun videoMeta(host: PairedHost, videoId: String): VideoMeta? =
        when (val r = api.videoMeta(host.host, host.port, host.token, videoId)) {
            is ApiResult.Ok -> r.data
            else -> null
        }

    /** 缩略图地址（M4 使用） */
    fun thumbUrl(host: PairedHost, videoId: String): String =
        api.thumbUrl(host.host, host.port, videoId)

    // ------------------------------------------------------------------
    // 局域网应用更新（2026-11-07）
    // ------------------------------------------------------------------

    /**
     * 检查电脑端发布的电视端新版本（需 Token；局域网更新功能）。
     * 注意：不走 withAuthRefresh——电脑端撤销授权时由 UI 提示重新配对，
     * 避免更新检查的多台扫描过程中误删主机。
     */
    suspend fun checkUpdate(host: PairedHost): ApiResult<UpdateCheckData> =
        api.checkUpdate(host.host, host.port, host.token)

    /** 更新安装包下载地址（下载由 data/update/UpdateManager 流式执行） */
    fun updateDownloadUrl(host: PairedHost): String = api.updateDownloadUrl(host.host, host.port)

    /** 请求成功后刷新最近连接时间（失败不打扰调用方）。
     *  2026-11-06 优化：电脑端取消授权后接口返回 401，此处统一清理本地失效主机——
     *  主页「我的主机」随之移除该电脑，重新进入配对引导时可再次被搜索发现。 */
    private suspend inline fun <T> withAuthRefresh(
        host: PairedHost,
        crossinline call: suspend () -> ApiResult<T>
    ): ApiResult<T> {
        val result = call()
        when (result) {
            is ApiResult.Ok -> store.touchHost(host.hostDeviceId)
            is ApiResult.ApiError -> if (result.isUnauthorized) {
                // 授权已被电脑端撤销：本地移除该主机（Token 已失效，无法再访问）
                store.removeHost(host.hostDeviceId)
            }
            is ApiResult.NetworkError -> {}
        }
        return result
    }

    // ------------------------------------------------------------------
    // 播放进度（断点续播，M3 播放器接入）
    // ------------------------------------------------------------------

    /** 保存播放进度 */
    suspend fun saveProgress(progress: PlaybackProgress) = store.saveProgress(progress)

    /** 查询播放进度 */
    fun getProgress(host: PairedHost, videoPath: String): PlaybackProgress? =
        store.getProgress(host.hostDeviceId, videoPath)
}
