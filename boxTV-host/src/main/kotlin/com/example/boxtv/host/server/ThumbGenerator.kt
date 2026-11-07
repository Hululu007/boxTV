package com.example.boxtv.host.server

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 缩略图生成器（server 层，细则 4.4 节）
 *
 * 职责：
 * - 定位 ffmpeg 工具：优先程序目录 tools/ffmpeg.exe（随安装包捆绑分发），
 *   缺失时回退系统 PATH 中的 ffmpeg（开发环境便捷）；两者都没有则整体降级；
 * - 首次收到缩略图请求：ffprobe 读取视频时长 → ffmpeg 提取 10% 处关键帧
 *   → 缩放为宽 480px 的 JPEG；
 * - 缓存：%APPDATA%/boxTV/thumbCache/<md5(videoId)>.jpg，命中直接返回；
 * - 生成失败（损坏文件/不支持的编码）→ 返回 null，接口统一降级 404，
 *   电视端显示占位图标，不影响其他功能；
 * - 同一视频的并发请求通过 per-key Mutex 合并，避免重复生成。
 *
 * 说明：细则中「捆绑 ffmpeg.exe」指 ffmpeg 工具链，ffprobe（读取时长）
 * 与 ffmpeg 同目录分发，二者缺一即视为工具缺失。
 */
class ThumbGenerator(cacheDir: File) {

    private companion object {
        /** 缩略图宽度（细则 4.4：宽 480px） */
        const val THUMB_WIDTH = 480

        /** 关键帧提取位置（细则 4.4：视频 10% 处） */
        const val SEEK_RATIO = 0.10

        /** 单次 ffmpeg/ffprobe 进程超时（毫秒），超时视为生成失败 */
        const val PROC_TIMEOUT_MS = 20_000L
    }

    /** 缓存目录（不存在时自动创建） */
    private val dir: File = cacheDir.apply { mkdirs() }

    /** 每个视频一把互斥锁：并发请求同一缩略图时只生成一次 */
    private val locks = ConcurrentHashMap<String, Mutex>()

    // 2026-11-07：工具定位逻辑抽到 FfmpegLocator（与实时转码器 Transcoder 共享），
    // 首次访问时解析并打印结果，便于排查降级原因

    /** ffmpeg 可执行文件（null = 缺失，功能降级） */
    private val ffmpeg: String? get() = FfmpegLocator.ffmpeg

    /** ffprobe 可执行文件（读取视频时长用，null = 缺失，功能降级） */
    private val ffprobe: String? get() = FfmpegLocator.ffprobe

    /** 缩略图是否可用（设置界面/日志可用于展示降级状态） */
    val available: Boolean get() = FfmpegLocator.available

    /** videoId 对应的缓存文件：%APPDATA%/boxTV/thumbCache/<md5(videoId)>.jpg */
    private fun cacheFile(videoId: String): File =
        File(dir, md5(videoId) + ".jpg")

    /** 获取缩略图：缓存命中直接返回；未命中现场生成（并发合并）。
     *  @return 缩略图 JPEG 文件；工具缺失/生成失败返回 null（调用方降级 404） */
    suspend fun getOrGenerate(videoId: String, source: File): File? {
        val ff = ffmpeg ?: return null
        val probe = ffprobe ?: return null
        val cached = cacheFile(videoId)
        if (cached.isFile && cached.length() > 0) return cached

        val mutex = locks.computeIfAbsent(videoId) { Mutex() }
        return mutex.withLock {
            // 双重检查：等锁期间可能已被并发请求生成
            if (cached.isFile && cached.length() > 0) return@withLock cached

            withContext(Dispatchers.IO) {
                try {
                    generate(probe, ff, videoId, source)
                } catch (_: Exception) {
                    null // 生成失败 → 降级 404
                }
            }
        }
    }

    /** 现场生成：ffprobe 读时长 → ffmpeg 提取 10% 处帧 → 原子落盘 */
    private fun generate(probe: String, ff: String, videoId: String, source: File): File? {
        // 1. 读取视频时长（秒，浮点字符串；读取失败按 0 处理 → 从头取帧）
        val duration = probeDuration(probe, source)
        // 2. 定位 10% 处（负值/非有限值兜底为 0）
        val seek = if (duration > 0) duration * SEEK_RATIO else 0.0
        val seekStr = if (seek >= 1) "%.2f".format(seek) else "0"

        // 3. 提取关键帧：缩放宽 480（高度自适应偶数对齐），JPEG 质量 q:v=4（约 75%）
        // 注意：临时文件必须以 .jpg 结尾——ffmpeg 依据输出扩展名推断封装格式，
        // 若用 ".tmp" 结尾会报「Unable to find a suitable output format」；
        // M3U8 源需附加 hls 输入选项（见 inputOptionsFor），位置必须在 -i 之前
        val finalFile = cacheFile(videoId)
        val tmp = File(dir, "${finalFile.nameWithoutExtension}.tmp.jpg")
        tmp.delete()
        val ok = runProcess(
            ff, listOf(
                "-y",                     // 覆盖临时文件
                "-ss", seekStr            // 快速定位到 10% 处（输入侧 seek，速度快）
            ) + inputOptionsFor(source) + listOf(
                "-i", source.absolutePath,
                "-frames:v", "1",         // 只取一帧
                "-vf", "scale=$THUMB_WIDTH:-2",
                "-q:v", "4",
                tmp.absolutePath
            )
        )
        if (!ok || !tmp.isFile || tmp.length() == 0L) {
            tmp.delete()
            println("[boxTV] 缩略图生成失败: ${source.name}（ffmpeg 退出码非 0 或无输出）")
            return null
        }
        // 4. 原子替换：先写临时文件再重命名，避免并发读到半张图
        if (!tmp.renameTo(finalFile)) {
            finalFile.delete()
            if (!tmp.renameTo(finalFile)) {
                tmp.delete()
                return null
            }
        }
        return finalFile
    }

    /** ffprobe 读取视频时长（秒）；失败返回 -1（调用方退化为从头取帧）。
     *  M3U8 播放列表源需附加 hls 输入选项（位置必须在输入文件之前）。 */
    private fun probeDuration(probe: String, source: File): Double {
        val out = runProcessWithOutput(
            probe, listOf(
                "-v", "error",
                "-show_entries", "format=duration",
                "-of", "csv=p=0"
            ) + inputOptionsFor(source) + listOf(source.absolutePath)
        ) ?: return -1.0
        return out.trim().toDoubleOrNull() ?: -1.0
    }

    /**
     * M3U8 播放列表源的输入选项（2026-11-07 M3U8 播放支持，聚合条目缩略图用）：
     * - -allowed_extensions ALL：hls demuxer 默认只认 .ts/.m3u8 等扩展名，
     *   下载器常把分片命名为 .seg/.part 等或无扩展名，需放行全部；
     * - -protocol_whitelist file,crypto,data：本地文件协议 + AES-128 解密
     *   （KEY 文件为本地相对路径时经 crypto 解密读取）。
     * 普通媒体文件不能携带这些参数（会被当作输入文件名），故按扩展名区分。
     */
    private fun inputOptionsFor(source: File): List<String> =
        if (source.extension.equals("m3u8", ignoreCase = true))
            listOf("-allowed_extensions", "ALL", "-protocol_whitelist", "file,crypto,data")
        else emptyList()

    /** 运行外部进程（阻塞至完成，带超时；返回是否成功）。
     *  合并 stdout/stderr 到缓存目录 ffmpeg-last.log（每次覆盖），便于排查生成失败原因。 */
    private fun runProcess(cmd: String, args: List<String>): Boolean {
        val logFile = File(dir, "ffmpeg-last.log")
        return try {
            val proc = ProcessBuilder(listOf(cmd) + args)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(logFile))
                .start()
            // waitFor 带超时：超时强杀进程（避免损坏文件导致 ffmpeg 挂起拖垮请求）
            if (proc.waitFor(PROC_TIMEOUT_MS, TimeUnit.MILLISECONDS)) proc.exitValue() == 0
            else {
                proc.destroyForcibly()
                false
            }
        } catch (e: Exception) {
            logFile.writeText("进程启动异常: ${e.message}")
            false
        }
    }

    /** 运行外部进程并捕获标准输出（带超时保护）。
     *  ffprobe 正常仅输出一行时长，stdout 极小，无死锁风险；stderr 透传到控制台。 */
    private fun runProcessWithOutput(cmd: String, args: List<String>): String? {
        return try {
            val proc = ProcessBuilder(listOf(cmd) + args)
                .redirectErrorStream(false)
                .start()
            val output = proc.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            if (proc.waitFor(PROC_TIMEOUT_MS, TimeUnit.MILLISECONDS)) output
            else {
                proc.destroyForcibly()
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** videoId 的 MD5 十六进制串（缓存文件名，细则 4.4） */
    private fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
