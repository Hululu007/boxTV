package com.example.boxtv.host.server

import java.util.Base64

/**
 * videoId 编解码模块（server 层）
 *
 * 对应细则 4.3 节「关键规则 1」：
 * videoId = Base64URL(相对路径)，无状态、可反解，服务端无需存储映射表。
 *
 * 细则细化说明（多文件夹场景）：videoId 内编码「文件夹id|相对路径」的复合串，
 * 反解后先定位授权文件夹，再在其根目录内拼接相对路径。相对路径统一使用
 * '/' 分隔符，避免 Windows 反斜杠在 URL/JSON 中产生歧义。
 */
object VideoIdCodec {

    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    /**
     * 编码：folderId + 相对路径 → videoId 字符串（URL 安全，无填充）
     */
    fun encode(folderId: String, relativePath: String): String {
        val composite = "$folderId|$relativePath"
        return encoder.encodeToString(composite.toByteArray(Charsets.UTF_8))
    }

    /**
     * 解码：videoId → (folderId, 相对路径)
     * 解码失败或格式非法返回 null。
     */
    fun decode(videoId: String): Pair<String, String>? {
        return runCatching {
            val composite = String(decoder.decode(videoId), Charsets.UTF_8)
            val idx = composite.indexOf('|')
            if (idx <= 0 || idx == composite.length - 1) return null
            composite.substring(0, idx) to composite.substring(idx + 1)
        }.getOrNull()
    }
}
