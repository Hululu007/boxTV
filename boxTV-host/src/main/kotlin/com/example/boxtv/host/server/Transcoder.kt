package com.example.boxtv.host.server

import com.example.boxtv.host.server.ApiCodes.INTERNAL_ERROR
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondBytesWriter
import io.ktor.utils.io.writeFully
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.coroutineContext

/**
 * 视频实时转码器（server 层）—— 2026-11-07 播放器升级新增
 *
 * 背景：原 /api/video/stream 直接回传原文件（原画直连），电视端无法真正
 * 降低清晰度/流量。本模块用电脑端捆绑的 ffmpeg 实现「边转码边推流」：
 * - 电视端请求 720P/480P（或 1080P 但源片分辨率更高）时，ffmpeg 实时转码，
 *   输出分段 MP4（fMP4）经管道推给 ExoPlayer，网络流量随清晰度显著下降；
 * - 1080P 默认档：源片高度 ≤ 1080 时仍走原文件直连（零转码，零 CPU 开销）；
 * - 进度拖动：电视端以「重新起播」模型对接——拖动进度条/切清晰度时携带
 *   start 参数重新请求，本模块会终止旧转码进程，以 -ss 精确寻址重新起转。
 *
 * 命令要点：
 * - `-ss` 放在 `-i` 之前（输入侧快速定位）+ 转码场景下逐帧解码对齐，实际精确；
 * - `-map 0:v:0 -map 0:a:0?`：只取第一路视频与第一路音频（音频可缺失），
 *   显式丢弃内封字幕/多音轨，避免 MP4 封装器因 ASS 字幕流报错中断；
 * - `-movflags +frag_keyframe+empty_moov+default_base_moof`：输出分段 MP4，
 *   头部即时可播，无需等待整个 moov 元数据写完（管道推流的前提）；
 * - `-pix_fmt yuv420p`：统一输出 8bit 4:2:0，兼容电视端硬解。
 */
class Transcoder {

    companion object {
        /** ffprobe 单次进程超时（毫秒），超时视为探测失败 */
        private const val PROC_TIMEOUT_MS = 20_000L

        /** 管道读写缓冲区大小（128KB：LAN 场景吞吐与内存占用的平衡） */
        private const val PIPE_BUF_SIZE = 128 * 1024
    }

    /**
     * 视频元数据（ffprobe 探测结果）
     * @param durationMs 视频时长（毫秒；转码流本身不携带时长，电视端以此补偿进度条）
     * @param width      源片宽（像素）
     * @param height     源片高（像素；电视端据此决定 1080P 档是否需要转码）
     */
    data class Meta(val durationMs: Long, val width: Int, val height: Int)

    /** JSON 解析（ffprobe -of json 输出） */
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 元数据缓存：key = videoId|文件修改时间。
     * 文件被覆盖（修改时间变化）后自动失效重探，无需手动清理。
     */
    private val metaCache = ConcurrentHashMap<String, Meta>()

    /** 活跃转码进程表：key = videoId|设备标识（同设备同视频只保留最新一条流） */
    private val activeProcesses = ConcurrentHashMap<String, Process>()

    /** 转码功能是否可用（ffmpeg 工具链完整） */
    val available: Boolean get() = FfmpegLocator.available

    // ------------------------------------------------------------------
    // 元数据探测（/api/video/meta 使用）
    // ------------------------------------------------------------------

    /**
     * 探测视频元数据（时长/分辨率），带缓存。
     * @return 元数据；ffprobe 缺失、探测失败或输出无法解析时返回 null
     */
    fun meta(videoId: String, file: File): Meta? {
        val key = "$videoId|${file.lastModified()}"
        metaCache[key]?.let { return it }
        val probe = FfmpegLocator.ffprobe ?: return null
        val result = runCatching { probeMeta(probe, file) }.getOrNull()
        if (result != null) metaCache[key] = result
        return result
    }

    /** ffprobe 读取流信息（一次调用同时取分辨率与时长，JSON 输出便于解析）。
     *  M3U8 播放列表源：附加 hls demuxer 私有输入选项（放行全部扩展名 + 本地
     *  文件/解密协议，支持 AES-128 本地密钥）；普通媒体文件不能携带这些参数。 */
    private fun probeMeta(probe: String, file: File): Meta? {
        val args = mutableListOf(
            probe,
            "-v", "error",                    // 只输出错误，保持 stdout 纯净
            "-select_streams", "v:0",         // 只看第一路视频流
            "-show_entries", "stream=width,height:format=duration",
            "-of", "json"
        )
        if (file.extension.equals("m3u8", ignoreCase = true)) {
            args += listOf("-allowed_extensions", "ALL", "-protocol_whitelist", "file,crypto,data")
        }
        args += file.absolutePath
        val proc = ProcessBuilder(args).redirectErrorStream(false).start()
        val out = proc.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        if (!proc.waitFor(PROC_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            proc.destroyForcibly()
            return null
        }
        if (proc.exitValue() != 0) return null

        val root = json.parseToJsonElement(out).jsonObject
        val stream = root["streams"]?.jsonArray?.firstOrNull()?.jsonObject
        val width = stream?.get("width")?.jsonPrimitive?.intOrNull ?: 0
        val height = stream?.get("height")?.jsonPrimitive?.intOrNull ?: 0
        val durationSec = root["format"]?.jsonObject?.get("duration")?.jsonPrimitive?.doubleOrNull ?: 0.0
        val durationMs = (durationSec * 1000).toLong()
        if (durationMs <= 0 && width <= 0) return null
        return Meta(durationMs = durationMs, width = width, height = height)
    }

    // ------------------------------------------------------------------
    // 实时转码推流（/api/video/stream 的转码分支使用）
    // ------------------------------------------------------------------

    /**
     * 启动 ffmpeg 实时转码并把 fMP4 输出经管道推流给电视端。
     *
     * @param call         Ktor 请求上下文（响应体以流方式写出）
     * @param key          进程去重键（videoId|设备标识）
     * @param file         源视频文件
     * @param targetHeight 目标高度（720/480/1080；宽度按比例自适应偶数对齐）
     * @param startSec     起播位置（秒；电视端拖动进度条/切清晰度时重新请求携带）
     */
    suspend fun respondTranscode(
        call: ApplicationCall,
        key: String,
        file: File,
        targetHeight: Int,
        startSec: Double
    ) {
        val ffmpeg = FfmpegLocator.ffmpeg ?: run {
            call.respondError(HttpStatusCode.InternalServerError, INTERNAL_ERROR, "电脑端缺少 ffmpeg 工具，无法转码播放")
            return
        }

        // 同 key 旧进程终止：电视端拖动进度条/切换清晰度会重新请求，
        // 旧流已无意义，立即回收（避免多个 ffmpeg 抢占同一文件的 CPU）
        activeProcesses.remove(key)?.destroyForcibly()

        val args = listOf(
            ffmpeg,
            "-hide_banner", "-loglevel", "error",
            "-ss", "%.3f".format(startSec.coerceAtLeast(0.0)),  // 输入侧定位：秒级精确起点
            "-i", file.absolutePath,
            "-map", "0:v:0", "-map", "0:a:0?",                  // 第一路视频 + 第一路音频（? = 音频可缺失）
            "-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
            "-pix_fmt", "yuv420p",                              // 统一 8bit 4:2:0，兼容电视硬解
            "-vf", "scale=-2:$targetHeight",                    // 高度定标，宽度自适应偶数对齐
            "-c:a", "aac", "-b:a", "160k", "-ac", "2",
            "-sn", "-dn",                                       // 丢弃字幕流/数据流（MP4 封装兼容）
            "-movflags", "+frag_keyframe+empty_moov+default_base_moof",
            "-f", "mp4", "pipe:1"                               // fMP4 输出到标准输出 → 管道推流
        )
        val proc = try {
            ProcessBuilder(args).redirectErrorStream(false).start()
        } catch (e: Exception) {
            println("[boxTV] 转码进程启动失败: ${e.message}")
            call.respondError(HttpStatusCode.InternalServerError, INTERNAL_ERROR, "转码进程启动失败")
            return
        }
        activeProcesses[key] = proc
        println("[boxTV] 转码开始: ${file.name} → ${targetHeight}p @ ${"%.1f".format(startSec)}s")

        // stderr 排水泵（守护线程）：ffmpeg 持续向 stderr 写日志，不排空会塞满
        // 管道缓冲区导致转码卡死；仅保留末尾若干行用于失败排查
        val errTail = ArrayDeque<String>()
        val errPump = Thread {
            try {
                proc.errorStream.bufferedReader().forEachLine { line ->
                    synchronized(errTail) {
                        errTail.addLast(line)
                        if (errTail.size > 20) errTail.removeFirst()
                    }
                }
            } catch (_: Exception) {
                // 进程被销毁时读取流会抛异常，属正常结束路径
            }
        }.apply { isDaemon = true; start() }

        try {
            call.respondBytesWriter(contentType = ContentType.Video.MP4) {
                val input = proc.inputStream
                val buf = ByteArray(PIPE_BUF_SIZE)
                while (true) {
                    // 阻塞读放 IO 池：读满/读到 EOF 才返回（-1 = ffmpeg 输出结束）
                    val n = withContext(Dispatchers.IO) { input.read(buf) }
                    if (n < 0) break
                    writeFully(buf, 0, n)
                    flush() // 逐块冲刷：电视端尽快起播（fMP4 分段边界到达即可解码）
                }
            }
        } catch (e: CancellationException) {
            throw e // 客户端断开/响应取消：按协程约定原样上抛
        } catch (e: Exception) {
            // 电视端退出播放/被新流替换/网络抖动导致的连接中断，属正常结束路径
            println("[boxTV] 转码流中断（${file.name}）: ${e.message ?: "连接关闭"}")
        } finally {
            // 进程回收：仅当映射仍指向本进程时移除（可能已被同 key 新请求替换）
            activeProcesses.remove(key, proc)
            proc.destroyForcibly()
            runCatching { errPump.join(1_000) }
            val errors = synchronized(errTail) { errTail.joinToString("\n") }
            if (errors.isNotBlank()) println("[boxTV] 转码进程日志（${file.name}）:\n$errors")
        }
    }
}
