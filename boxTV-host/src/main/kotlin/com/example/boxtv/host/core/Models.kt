package com.example.boxtv.host.core

import java.util.UUID
import kotlinx.serialization.Serializable

/**
 * 数据模型模块（core 层）
 *
 * 定义 boxTV Host 的所有持久化数据结构，对应细则 6.3 节 core 模块。
 * 全部使用 kotlinx.serialization 标注，最终持久化到 %APPDATA%/boxTV/config.json。
 */

/**
 * 已授权文件夹
 *
 * @param id   文件夹唯一标识（UUID），电视端通过 folderId 引用
 * @param name 显示名称（电视端文件夹列表中展示的名字，默认取文件夹原名）
 * @param path 本地绝对路径（电脑端实际授权的目录）
 */
@Serializable
data class AuthorizedFolder(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val path: String
)

/**
 * 已配对的电视设备
 *
 * @param deviceUuid      电视端首次启动生成的 UUID，作为设备唯一标识
 * @param name            设备名称（电视端上报，如"客厅电视"）
 * @param model           设备型号（电视端上报）
 * @param token           访问令牌（32 位随机十六进制），电视端后续所有请求凭此鉴权
 * @param pairedAt        配对时间戳（毫秒）
 * @param lastConnectedAt 最近一次成功鉴权的时间戳（毫秒）
 */
@Serializable
data class PairedDevice(
    val deviceUuid: String,
    val name: String,
    val model: String = "",
    val token: String,
    val pairedAt: Long = System.currentTimeMillis(),
    val lastConnectedAt: Long = 0L
)

/**
 * 电脑端根配置（config.json 的完整结构）
 *
 * @param port         HTTP 服务端口（默认 8964，可在设置中修改）
 * @param hostName     电视端发现列表中显示的主机名
 * @param hostDeviceId 电脑端自身唯一标识（首次启动生成后持久化，电视端据此去重）
 * @param folders      已授权文件夹列表
 * @param devices      已配对设备列表（含 Token，撤销授权 = 从列表删除）
 * @param closeToTray  关闭窗口行为：true=最小化到托盘（细则 6.4），false=直接退出
 */
@Serializable
data class HostConfig(
    val port: Int = 8964,
    val hostName: String = "",
    val hostDeviceId: String = UUID.randomUUID().toString(),
    val folders: List<AuthorizedFolder> = emptyList(),
    val devices: List<PairedDevice> = emptyList(),
    val closeToTray: Boolean = true
)
