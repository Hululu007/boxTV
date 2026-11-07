package com.example.boxtv.host.discovery

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlin.concurrent.thread
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * UDP 设备发现应答模块（discovery 层）
 *
 * 对应细则 4.1 节「设备发现协议」：
 * - 电视端向局域网广播探测包「BOXTV_DISCOVER_V1」（UDP 8965 端口）；
 * - 本模块监听 8965 端口，收到合法探测后从监听端口单播应答自身信息（JSON）；
 * - 电视端按 hostDeviceId 去重后展示主机列表。
 *
 * 多网卡处理（细则 6.4 节）：应答包由操作系统按路由选择源网卡 IP，
 * 对同网段的发送方会自动选中正确的局域网接口地址。
 */
class DiscoveryResponder(
    private val udpPort: Int,
    private val infoProvider: () -> DiscoveryInfo
) {

    /** 应答包数据结构（细则 4.1 节 JSON 字段） */
    @Serializable
    data class DiscoveryInfo(
        val service: String = "boxtv-host",
        val protocol: Int = 1,
        val hostName: String,
        val httpPort: Int,
        val hostDeviceId: String,
        val version: String
    )

    companion object {
        /** 合法探测包内容（细则 4.1 节规定） */
        private const val PROBE_KEYWORD = "BOXTV_DISCOVER_V1"

        /** 应答 JSON 编解码器 */
        private val json = Json { encodeDefaults = true }
    }

    /** 监听套接字（volatile 保证跨线程关闭可见性） */
    @Volatile
    private var socket: DatagramSocket? = null

    /** 监听线程 */
    private var workerThread: Thread? = null

    /** 启动 UDP 监听（绑定所有网卡的 udpPort 端口） */
    fun start() {
        val s = DatagramSocket(InetSocketAddress(udpPort))
        s.reuseAddress = true
        socket = s
        workerThread = thread(name = "boxtv-discovery-responder", isDaemon = true) {
            val buf = ByteArray(512)
            while (!s.isClosed) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    s.receive(packet)
                    val probe = String(packet.data, packet.offset, packet.length).trim()
                    if (probe == PROBE_KEYWORD) {
                        respond(packet, s)
                    }
                } catch (_: SocketTimeoutException) {
                    // 超时继续循环
                } catch (_: SocketException) {
                    break // 套接字被关闭（stop 调用），正常退出
                } catch (_: Exception) {
                    // 单次处理失败不影响监听循环
                }
            }
        }
    }

    /** 构建应答并发送到探测方地址 */
    private fun respond(probePacket: DatagramPacket, listenSocket: DatagramSocket) {
        val senderAddr = probePacket.address
        val senderPort = probePacket.port

        val info = infoProvider()
        val payload = json.encodeToString(DiscoveryInfo.serializer(), info)
            .toByteArray(Charsets.UTF_8)
        val reply = DatagramPacket(payload, payload.size, senderAddr, senderPort)

        // 直接用监听套接字应答：源端口=8965，与电视端「连接到 8965」的接收语义匹配
        //（标准发现协议如 SSDP 均从监听端口应答）。
        // 源 IP 由操作系统按路由表选择：对同网段发送方必然选中对应网卡 IP（细则 6.4）。
        runCatching { listenSocket.send(reply) }
        // 发送失败静默忽略：电视端会重试广播
    }

    /** 停止监听并关闭套接字 */
    fun stop() {
        runCatching { socket?.close() }
        workerThread = null
        socket = null
    }
}
