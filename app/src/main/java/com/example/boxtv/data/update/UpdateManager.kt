package com.example.boxtv.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.boxtv.data.remote.UpdateCheckData
import com.example.boxtv.model.PairedHost
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.headers
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 局域网应用更新管理器（data/update 层）—— 2026-11-07 局域网更新功能
 *
 * 职责（全程局域网直连电脑端，不依赖任何公网服务）：
 * - 流式下载电脑端 /api/update/download 下发的 APK 安装包，
 *   实时上报进度（StateFlow，供下载弹窗进度条消费）；
 * - 支持暂停/继续（断点续传：保留 .part 文件，续传时携带 Range 头，
 *   电脑端 PartialContent 插件返回 206 分段）、取消（删除半成品）；
 * - 下载完成后 MD5 完整性校验（与 /api/update/check 返回的 md5 比对），
 *   校验失败删除重下，杜绝损坏包进入安装环节；
 * - 触发系统安装器（FileProvider + ACTION_VIEW，Android 8+ 引导授权
 *   「安装未知应用」）；安装完成由 UpdateDoneReceiver（MY_PACKAGE_REPLACED）
 *   自动重启应用，完成版本迭代引导。
 */
class UpdateManager(context: Context) {

    companion object {
        /** 安装包版本记录持久化文件与键 */
        private const val PREFS_UPDATE = "boxtv_update"
        private const val KEY_READY_VERSION = "ready_apk_version"
    }

    // ------------------------------------------------------------------
    // 状态定义（下载弹窗订阅渲染）
    // ------------------------------------------------------------------

    /** 下载状态机：进度 / 暂停 / 校验 / 完成 / 失败（失败携带重试建议） */
    sealed class DownloadState {
        /** 空闲（未开始或已取消） */
        data object Idle : DownloadState()

        /** 下载中：downloaded/total 为字节数（total=-1 表示服务端未返回总长） */
        data class Downloading(val downloaded: Long, val total: Long) : DownloadState()

        /** 已暂停：保留断点，可继续 */
        data class Paused(val downloaded: Long, val total: Long) : DownloadState()

        /** 下载完成，MD5 校验中 */
        data object Verifying : DownloadState()

        /** 校验通过：apk 为可安装文件（调用方触发系统安装器） */
        data class Success(val apk: File) : DownloadState()

        /**
         * 失败：message 为中文错误提示；canResume=true 表示可从断点续传重试
         * （网络中断等），false 表示需完整重新下载（如校验失败）。
         */
        data class Failed(val message: String, val canResume: Boolean) : DownloadState()
    }

    private val appContext = context.applicationContext

    /** 下载目录：filesDir/updates（FileProvider files-path 与之对应） */
    private val dir = File(appContext.filesDir, "updates").apply { mkdirs() }
    private val apkFile = File(dir, "update.apk")
    private val partFile = File(dir, "update.apk.part")

    /** 已校验安装包版本记录持久化（key：apk 对应的 versionName） */
    private val prefs = appContext.getSharedPreferences(PREFS_UPDATE, Context.MODE_PRIVATE)

    /** 本次下载对应的目标版本号（verifyAndFinish 落盘时持久化） */
    private var pendingVersion: String? = null

    /**
     * 已下载且校验通过的安装包（存在即代表 MD5 校验已通过）。
     * 安装被「未知来源授权」等打断时保留复用，用户重试无需重新下载；
     * 新版安装成功后由 [cleanupInstalledPackage] 清除（MY_PACKAGE_REPLACED），
     * 避免残留旧包被更新流程误复用。
     */
    val readyApk: File? get() = apkFile.takeIf { it.exists() && it.length() > 0 }

    /** readyApk 对应的版本号（与待更新版本比对，不一致则视为残留旧包不复用） */
    val readyApkVersion: String? get() = prefs.getString(KEY_READY_VERSION, null)

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state

    /** 下载协程（独立作用域：不随 UI lifecycleScope 取消，暂停/取消显式控制） */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** 下载参数（重试/继续时复用） */
    private var currentUrl: String? = null
    private var currentToken: String? = null
    private var expectedMd5: String? = null

    /** 专用下载客户端：无整体请求超时（大文件慢速局域网），仅连接超时 10s */
    private val client = HttpClient(io.ktor.client.engine.cio.CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 3_600_000
        }
    }

    // ------------------------------------------------------------------
    // 下载控制
    // ------------------------------------------------------------------

    /** 开始下载（从当前断点继续；无断点则从头开始） */
    fun start(host: PairedHost, url: String, token: String, meta: UpdateCheckData) {
        if (job?.isActive == true) return // 已在下载中：忽略重复触发
        currentUrl = url
        currentToken = token
        expectedMd5 = meta.md5.ifBlank { null }
        pendingVersion = meta.versionName // 记录目标版本（落盘时绑定）
        _state.value = DownloadState.Downloading(partFile.length(), meta.size)
        job = scope.launch { runDownload(url, token, meta.size) }
    }

    /**
     * 清理已下载的安装包（2026-11-07 安装包自动清理）：
     * - 新版覆盖安装成功后（MY_PACKAGE_REPLACED）调用：删除旧版本 APK 与断点，
     *   保证安装目录无残留，下次更新检测从干净状态开始；
     * - readyApk 版本与待更新版本不一致时也会调用（旧包不进安装环节）。
     */
    fun cleanupInstalledPackage() {
        runCatching {
            apkFile.delete()
            partFile.delete()
            prefs.edit().remove(KEY_READY_VERSION).apply()
        }
    }

    /** 暂停：停止读取，保留 .part 断点（进度条停在当前位置） */
    fun pause() {
        if (job?.isActive == true) {
            paused = true
        }
    }

    /** 继续：等价于 start（内部从断点续传） */
    fun resume() {
        val url = currentUrl ?: return
        val token = currentToken ?: return
        if (job?.isActive == true) return
        paused = false
        _state.value = DownloadState.Downloading(partFile.length(), -1L)
        job = scope.launch { runDownload(url, token, -1L) }
    }

    /** 取消：终止下载并删除半成品，回到空闲（可重新完整下载） */
    fun cancel() {
        paused = false
        job?.cancel()
        job = null
        runCatching { partFile.delete() }
        _state.value = DownloadState.Idle
    }

    /** 暂停标记（下载循环内轮询；volatile 保证跨线程可见） */
    @Volatile
    private var paused = false

    // ------------------------------------------------------------------
    // 下载与校验实现
    // ------------------------------------------------------------------

    /**
     * 单轮下载：从 .part 已有长度发起 Range 请求断点续传。
     * - 无断点（首次下载）：不发 Range 头，普通 GET 期望 200 全量响应——
     *   若无断点也携带 Range: bytes=0-，电脑端 PartialContent 插件会按协议
     *   返回 206，曾被下方状态判断误判为「下载失败（HTTP 206）」导致下载
     *   在起点即中止（2026-11-07 修复的根因）；
     * - 有断点：携带 Range 头，服务端 206 = 续传（追加写入）；
     * - 服务端 200（不支持 Range/断点失效）：从头覆盖写入；
     * - 网络异常 / 暂停：保留断点，返回是否可续传。
     */
    private suspend fun runDownload(url: String, token: String, declaredTotal: Long) {
        val offset = partFile.length()
        try {
            client.prepareGet(url) {
                headers {
                    // 仅在已有断点时携带 Range 头（首次下载走普通 GET 全量请求）
                    if (offset > 0) append(HttpHeaders.Range, "bytes=$offset-")
                    if (token.isNotBlank()) append("X-BoxTV-Token", token)
                }
            }.execute { response ->
                val status = response.status.value
                // 合法响应：200（全量）与 206（按 Range 分段续传）；
                // 其余状态（404/401/500…）视为失败
                if (status != 200 && status != 206) {
                    _state.value = DownloadState.Failed(
                        "下载失败（HTTP $status）", canResume = offset > 0
                    )
                    return@execute
                }
                // 206 且无断点（offset=0）：请求的是 bytes=0-，响应体同样从
                // 第 0 字节开始，appending=false 走覆盖写入语义即可，无需报错
                val appending = status == 206 && offset > 0
                val contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: -1L
                val total = if (contentLength > 0) {
                    if (appending) offset + contentLength else contentLength
                } else declaredTotal

                val channel = response.bodyAsChannel()
                java.io.RandomAccessFile(partFile, "rw").use { raf ->
                    if (appending) raf.seek(offset) else raf.setLength(0)
                    var downloaded = if (appending) offset else 0L
                    var lastReport = 0L
                    while (!channel.isClosedForRead) {
                        // 暂停/取消：跳出循环（保留断点）
                        if (paused || !currentCoroutineActive()) {
                            if (paused) _state.value = DownloadState.Paused(raf.filePointer, total)
                            return@execute
                        }
                        val packet = channel.readRemaining(64 * 1024)
                        if (packet.exhausted()) break
                        val bytes = packet.readByteArray()
                        raf.write(bytes)
                        downloaded += bytes.size
                        // 进度上报节流（>100ms 或完成时），避免重组风暴
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 100 || channel.isClosedForRead) {
                            lastReport = now
                            _state.value = DownloadState.Downloading(downloaded, total)
                        }
                    }
                }
                // 流读完：进入校验
                verifyAndFinish()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // cancel() 主动取消：状态已在 cancel() 置为 Idle，不再覆盖
            throw e
        } catch (e: Exception) {
            // 网络中断等：保留断点，提示可续传重试（需求 8：下载中断场景）
            _state.value = DownloadState.Failed(
                "下载中断：${e.message ?: "网络异常"}",
                canResume = partFile.length() > 0
            )
        }
    }

    /** 下载循环存活判断（配合 paused 双保险退出） */
    private fun currentCoroutineActive(): Boolean = job?.isActive != false

    /** MD5 完整性校验：通过 → 落正式文件名并进入 Success；失败 → 删除重下 */
    private fun verifyAndFinish() {
        _state.value = DownloadState.Verifying
        val expected = expectedMd5
        val actual = md5Of(partFile)
        if (expected != null && !actual.equals(expected, ignoreCase = true)) {
            // 需求 8：安装包校验失败 → 删除半成品，提示完整重新下载
            runCatching { partFile.delete() }
            _state.value = DownloadState.Failed("安装包校验失败，文件可能已损坏", canResume = false)
            return
        }
        runCatching {
            if (apkFile.exists()) apkFile.delete()
            check(partFile.renameTo(apkFile)) { "安装包落盘失败" }
            // 安装包与版本号绑定持久化：复用时校验版本一致，杜绝旧包串版本
            prefs.edit().putString(KEY_READY_VERSION, pendingVersion ?: "").apply()
        }.onSuccess {
            _state.value = DownloadState.Success(apkFile)
        }.onFailure {
            _state.value = DownloadState.Failed("安装包保存失败：${it.message}", canResume = false)
        }
    }

    /** 流式计算文件 MD5 */
    private fun md5Of(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------------------
    // 安装触发
    // ------------------------------------------------------------------

    /**
     * 触发系统安装器安装已下载的 APK。
     * @return false = 需要用户先授权「安装未知应用」（已跳转系统设置页，
     *         用户授权后回到本页再次点击安装即可）
     */
    fun install(activity: android.app.Activity): Boolean {
        // Android 8.0+：未授予「安装未知应用」权限时先引导到授权页
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")
                )
            )
            return false
        }
        // FileProvider 共享安装包（targetSdk 24+ 禁止直接暴露 file:// URI）
        val uri = FileProvider.getUriForFile(
            appContext, "${appContext.packageName}.fileprovider", apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            setDataAndType(uri, "application/vnd.android.package-archive")
        }
        activity.startActivity(intent)
        return true
    }

    /** 释放下载客户端（App 退出时调用） */
    fun shutdown() {
        client.close()
        scope.launch { }
    }
}
