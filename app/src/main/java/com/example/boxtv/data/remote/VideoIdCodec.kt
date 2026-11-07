package com.example.boxtv.data.remote

import java.util.Base64

/**
 * videoId 编解码模块（电视端，data/remote 层）
 *
 * 与电脑端 VideoIdCodec 的编码约定一致（细则 4.3 规则 1）：
 * videoId = Base64URL("folderId|相对路径")。
 *
 * 电视端反解用途：获取相对路径作为「断点续播进度」的存储键
 * （细则 5.3 节 playback_progress 表的 videoPath 字段）。
 */
object VideoIdCodec {

    /**
     * 解码 videoId → (folderId, 相对路径)。
     * 解码失败或格式非法返回 null。
     */
    fun decode(videoId: String): Pair<String, String>? {
        return runCatching {
            val composite = String(Base64.getUrlDecoder().decode(videoId), Charsets.UTF_8)
            val idx = composite.indexOf('|')
            if (idx <= 0 || idx == composite.length - 1) return null
            composite.substring(0, idx) to composite.substring(idx + 1)
        }.getOrNull()
    }
}
