package com.example.boxtv.data.discovery

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.example.boxtv.model.DiscoveredHost

/**
 * UDP 设备发现客户端（data/discovery 层）
 *
 * 对应细则 4.1 节「设备发现协议」电视端侧：
 * - 立即首发探测包「BOXTV_DISCOVER_V1」，之后每 1 秒向
 *   255.255.255.255:8965 与各网卡定向广播地址重发（丢包容错）；
 * - 静默收敛提前结束：已发现至少一台主机后，连续 1.2 秒无新主机应答
 *   即结束扫描（局域网广播应答集中在百毫秒级，典型场景 ~2 秒出结果，
 *   旧版固定 10 秒窗口是搜索慢的主因）；
 * - 无任何应答时维持 10 秒最大窗口兜底（细则 7 超时要求）；
 * - 应答中的主机 IP 取自探测包来源地址（电脑端应答源 IP 即其局域网地址）。
 */
class DiscoveryClient {

    companion object {
        /** 探测包内容（与电脑端 DiscoveryResponder 约定一致） */
        private const val PROBE_KEYWORD = "BOXTV_DISCOVER_V1"

        /** 电脑端 UDP 监听端口 */
        private const val DISCOVERY_PORT = 8965

        /** 探测重发间隔（毫秒）：比旧版 2 秒更密，单次广播丢包后更快补偿 */
        private const val SEND_INTERVAL_MS = 1_000L

        /** 单次收包等待超时（毫秒）：缩短后静默收敛判定更精准、循环更细粒度 */
        private const val RECV_TIMEOUT_MS = 400

        /** 静默收敛提前结束（毫秒）：已发现主机且该时长内无新主机应答 → 提前结束 */
        private const val QUIET_EXIT_MS = 1_200L
    }

    /** 电脑端发现应答包结构（与细则 4.1 节 JSON 字段一致） */
    @Serializable
    private data class DiscoveryResponse(
        val service: String,
        val protocol: Int = 1,
        val hostName: String,
        val httpPort: Int,
        val hostDeviceId: String,
        val version: String
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 发起发现扫描：在 [durationMs] 时间窗口内广播探测并收集应答，
     * 满足「已发现主机 + 静默期无新应答」时提前收敛结束。
     * @param onHostFound 新主机发现回调（IO 线程，按 hostDeviceId 去重后触发；
     *        可选，供调用方流式展示，不传则等扫描结束后统一取结果）
     * @return 去重后的主机列表（每台电脑只保留第一次应答的 IP）
     */
    suspend fun discover(
        durationMs: Long = 10_000L,
        onHostFound: ((DiscoveredHost) -> Unit)? = null
    ): List<DiscoveredHost> =
        withContext(Dispatchers.IO) {
            val found = LinkedHashMap<String, DiscoveredHost>()
            val probe = PROBE_KEYWORD.toByteArray(Charsets.UTF_8)
            val buf = ByteArray(1024)
            val broadcastTargets = buildList {
                add(InetAddress.getByName("255.255.255.255"))
                addAll(subnetBroadcastAddresses())
            }

            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = RECV_TIMEOUT_MS
                val deadline = System.currentTimeMillis() + durationMs
                var lastSendAt = 0L    // 0 = 循环首轮立即首发，不等第一个间隔
                var lastFoundAt = 0L   // 最近一次发现新主机的时刻（静默收敛基准）

                while (System.currentTimeMillis() < deadline) {
                    // 周期性重发探测包（丢包容错）
                    val now = System.currentTimeMillis()
                    if (now - lastSendAt >= SEND_INTERVAL_MS) {
                        broadcastTargets.forEach { addr ->
                            runCatching {
                                socket.send(DatagramPacket(probe, probe.size, addr, DISCOVERY_PORT))
                            }
                        }
                        lastSendAt = now
                    }
                    // 收集应答（单次超时 400ms 后回到循环顶部判断剩余时间/收敛条件）
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        socket.receive(packet)
                        val text = String(packet.data, packet.offset, packet.length)
                        val resp = json.decodeFromString(DiscoveryResponse.serializer(), text)
                        if (resp.service == "boxtv-host") {
                            val ip = packet.address.hostAddress ?: continue
                            // put 返回 null = 新主机（非重复应答）：更新收敛基准并回调
                            val host = DiscoveredHost(
                                hostName = resp.hostName,
                                host = ip,
                                httpPort = resp.httpPort,
                                hostDeviceId = resp.hostDeviceId,
                                version = resp.version
                            )
                            if (found.put(resp.hostDeviceId, host) == null) {
                                lastFoundAt = now
                                onHostFound?.invoke(host)
                            }
                        }
                    } catch (_: SocketTimeoutException) {
                        // 静默收敛：已发现主机且静默期内无新应答 → 提前结束扫描
                        if (found.isNotEmpty() && now - lastFoundAt >= QUIET_EXIT_MS) break
                    } catch (_: Exception) {
                        // 单包解析失败不影响整体扫描
                    }
                }
            }
            found.values.toList()
        }

    /** 枚举已启用 IPv4 网卡并推算其 /24 定向广播地址（x.y.z.255） */
    private fun subnetBroadcastAddresses(): List<InetAddress> {
        return runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .map { ip ->
                    val b = ip.address
                    InetAddress.getByAddress(byteArrayOf(b[0], b[1], b[2], -1))
                }
                .toList()
        }.getOrDefault(emptyList())
    }
}
