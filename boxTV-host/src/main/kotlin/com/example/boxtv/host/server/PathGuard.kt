package com.example.boxtv.host.server

import java.io.File

/**
 * 路径安全防护模块（server 层）
 *
 * 对应细则 4.3 节「关键规则 2」（P0 安全项）：
 * 所有来自电视端的相对路径，反解后必须做规范化（canonicalPath），
 * 规范化结果必须仍在对应授权文件夹之内，否则判定为路径越界，
 * 杜绝 `../` 目录穿越攻击。
 *
 * 机制说明：File.canonicalFile 会解析 `.`、`..`、符号链接等所有路径歧义，
 * 得到真实绝对路径；随后做「根前缀校验」。Windows 文件系统大小写不敏感，
 * 前缀比较统一使用 ignoreCase = true。
 */
object PathGuard {

    /**
     * 将授权文件夹根目录 + 相对路径 解析为安全的目标 File。
     *
     * @param root     授权文件夹的本地绝对路径
     * @param relative 相对路径（'/' 分隔；null 或空白表示根目录本身）
     * @return 规范化后的目标 File；若越界返回 null（调用方应返回 40300）
     */
    fun resolve(root: String, relative: String?): File? {
        val canonicalRoot = File(root).canonicalFile
        if (relative.isNullOrBlank()) return canonicalRoot

        // 用根目录为基拼接相对路径（'/' 在 Windows 上同样可用），再规范化
        val candidate = File(canonicalRoot, relative).canonicalFile
        val rootPath = canonicalRoot.path
        val candidatePath = candidate.path

        // 越界判定：目标路径必须等于根目录，或位于根目录内部
        val inside = candidatePath.equals(rootPath, ignoreCase = true) ||
            candidatePath.startsWith(rootPath + File.separator, ignoreCase = true)
        return if (inside) candidate else null
    }

    /**
     * 可播放扩展名白名单（细则 4.3 节「关键规则 3」）
     */
    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "mkv", "webm", "avi", "mov", "ts", "flv", "m4v", "mpg", "mpeg", "wmv", "3gp"
    )

    /** 判断文件名是否为白名单内的视频文件 */
    fun isVideoFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in VIDEO_EXTENSIONS
    }
}
