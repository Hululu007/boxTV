package com.example.boxtv.model

import kotlinx.serialization.Serializable

/**
 * 电视端数据模型模块（model 层）
 *
 * 定义电视端使用的所有数据结构：
 * - PairedHost：已配对主机（对应细则 5.3 节 paired_host 表）
 * - PlaybackProgress：播放进度（对应细则 5.3 节 playback_progress 表）
 * - DiscoveredHost：UDP 发现到的主机（对应细则 4.1 节应答包）
 * - Folder / VideoEntry：浏览接口的展示模型（对应细则 4.3 节 browse 响应）
 */

/**
 * 已配对主机（持久化，主键 hostDeviceId）
 *
 * @param hostDeviceId    电脑端唯一标识（UUID）
 * @param name            电脑端主机显示名
 * @param host            IP 地址
 * @param port            HTTP 端口
 * @param token           配对签发的访问令牌
 * @param lastConnectedAt 最近一次成功访问的时间戳（毫秒）
 */
@Serializable
data class PairedHost(
    val hostDeviceId: String,
    val name: String,
    val host: String,
    val port: Int,
    val token: String,
    val lastConnectedAt: Long = 0L
)

/**
 * 播放进度（断点续播，主键 hostDeviceId + videoPath）
 *
 * 触发规则（细则 5.3）：退出播放页 / 切后台 / 每 5 秒节流写入；
 * 清理规则：进度 > 95% 或 < 5% 视为看完/刚开始，不参与续播。
 */
@Serializable
data class PlaybackProgress(
    val hostDeviceId: String,
    val videoPath: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long
)

/**
 * UDP 发现到的主机（细则 4.1 节应答 + 探测包来源 IP）
 */
data class DiscoveredHost(
    val hostName: String,
    val host: String,
    val httpPort: Int,
    val hostDeviceId: String,
    val version: String
)

/**
 * 视频元数据（2026-11-07 播放器升级新增，对应电脑端 /api/video/meta）
 *
 * @param durationMs 视频总时长（毫秒；转码流不携带时长，以此补偿进度条/进度保存）
 * @param width      源片宽（像素）
 * @param height     源片高（像素；1080P 档据此判断是否需要电脑端转码）
 */
@Serializable
data class VideoMeta(
    val durationMs: Long = 0,
    val width: Int = 0,
    val height: Int = 0
)

/**
 * 授权文件夹（电视端只看到 id 与名称）
 */
data class Folder(
    val id: String,
    val name: String
)

/**
 * 目录条目（浏览/搜索共用）
 * type: "dir"（子文件夹）| "video"（可播放视频文件）
 * format: "file"（普通视频文件）| "m3u8"（电脑端聚合的 M3U8 虚拟条目，
 *         2026-11-07 M3U8 播放支持新增；默认 "file"，与旧版电脑端响应兼容）
 */
data class VideoEntry(
    val name: String,
    val type: String,
    val size: Long = 0,
    val modifiedAt: Long = 0,
    val videoId: String? = null,
    val format: String = "file"
) {
    val isDir: Boolean get() = type == "dir"

    /** 是否为 M3U8 聚合条目（HLS 原生直连播放，不支持电脑端转码清晰度档） */
    val isM3u8: Boolean get() = format == "m3u8"
}
