package com.example.boxtv.host.server

import java.io.File

/**
 * ffmpeg 工具链定位模块（server 层）
 *
 * 2026-11-07 从 ThumbGenerator 抽出为共享定位器：缩略图（ThumbGenerator）
 * 与实时转码（Transcoder）都需要 ffmpeg/ffprobe，避免两份定位逻辑漂移。
 *
 * 解析顺序（与原 ThumbGenerator 一致）：
 * 1. 工作目录 tools/<name>.exe（安装包捆绑位置）；
 * 2. Compose Desktop 资源目录 tools/<name>.exe（MSI 安装布局主路径）；
 * 3. jar 所在目录的上级 tools/（便携/手工布局）；
 * 4. winget 工具链接目录（开发机常见安装位置）；
 * 5. 系统 PATH 逐目录探测。
 * 返回 null 表示本机无此工具 → 相关功能整体降级。
 */
internal object FfmpegLocator {

    /** 工具可执行文件定位（按顺序尝试各候选位置，取第一个存在的） */
    private fun resolve(name: String): String? {
        val candidates = mutableListOf<File>()
        candidates.add(File("tools", "$name.exe"))
        // Compose Desktop 打包资源目录（安装布局：<install>\app\resources\tools\）：
        // appResourcesRootDir 捆绑的 tools 随 MSI 落在此处（M5 交付主路径）
        System.getProperty("compose.application.resources.dir")?.let {
            candidates.add(File(File(it), "tools/$name.exe"))
        }
        // jar 所在目录的上级 tools/（便携/手工布局：<install>/app.jar → <install>/tools/ffmpeg.exe）
        runCatching {
            FfmpegLocator::class.java.protectionDomain.codeSource.location.toURI()
                ?.let { File(it).parentFile?.parentFile?.let { root -> File(root, "tools/$name.exe") } }
        }.getOrNull()?.let { candidates.add(it) }
        // 开发机 winget 链接目录
        System.getenv("LOCALAPPDATA")?.let {
            candidates.add(File(File(it, "Microsoft\\WinGet\\Links"), "$name.exe"))
        }
        // 系统 PATH 逐目录探测
        System.getenv("PATH")?.split(File.pathSeparator)?.forEach {
            candidates.add(File(it, "$name.exe"))
        }
        return candidates.firstOrNull { it.isFile }?.absolutePath
    }

    /** ffmpeg 可执行文件（null = 缺失，转码/缩略图功能降级） */
    val ffmpeg: String? by lazy {
        resolve("ffmpeg").also { println("[boxTV] ffmpeg 定位: ${it ?: "缺失（转码与缩略图将降级）"}") }
    }

    /** ffprobe 可执行文件（读取视频时长/分辨率；null = 缺失，功能降级） */
    val ffprobe: String? by lazy { resolve("ffprobe") }

    /** 工具链是否完整可用（ffmpeg 与 ffprobe 二者缺一即不可用） */
    val available: Boolean get() = ffmpeg != null && ffprobe != null
}
