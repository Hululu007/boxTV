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
 * 对应细则 4.1 节「设备发现协议」电视端侧，两阶段扫描：
 *
 * 阶段一（广播探测）：
 * - 立即首发探测包「BOXTV_DISCOVER_V1」，之后每 1 秒向
 *   255.255.255.255:8965 与各网卡定向广播地址重发（丢包容错）；
 * - 静默收敛提前结束：已发现至少一台主机后，连续 1.2 秒无新主机应答
 *   即结束扫描（局域网广播应答集中在百毫秒级，典型场景 ~2 秒出结果）；
 * - 最长 4 秒，未发现任何主机则进入阶段二。
 *
 * 阶段二（单播扫描兜底，2026-11-09 修复「个别电视搜不到任何电脑」）：
 * - 个别电视的 WiFi 驱动/部分路由器会过滤或丢弃 UDP 广播包（还可能因
 *   多网卡把 255.255.255.255 送错接口），导致广播探测永远无应答；
 * - 兜底做法：对本机各网卡的 /24 网段逐 IP 发送**单播**探测（x.x.x.1~254），
 *   电脑端 DiscoveryResponder 对任何来源（单播/广播）的合法探测包都会
 *   单播应答，因此电脑端无需任何改动；单播按目标地址精确路由，
 *   同时绕开了"受限广播只走默认路由"的多网卡问题；
 * - 分批限速发送（每 16 包暂停 8ms）避免瞬时突发触发路由器防洪水丢弃；
 * - 最长 5.5 秒，两阶段合计不超过调用方传入的总窗口（默认 10 秒）。
 *
 * 通用规则：
 * - 应答中的主机 IP 取自探测包来源地址（电脑端应答源 IP 即其局域网地址）；
 * - 无任何应答时维持调用方总窗口兜底（细则 7 超时要求）。
 */
class DiscoveryClient {

    companion object {
        /** 探测包内容（与电脑端 DiscoveryResponder 约定一致） */
        private const val PROBE_KEYWORD = "BOXTV_DISCOVER_V1"

        /** 电脑端 UDP 监听端口 */
        private const val DISCOVERY_PORT = 8965

        /** 广播探测重发间隔（毫秒）：单次广播丢包后更快补偿 */
        private const val SEND_INTERVAL_MS = 1_000L

        /** 单次收包等待超时（毫秒）：缩短后静默收敛判定更精准、循环更细粒度 */
        private const val RECV_TIMEOUT_MS = 400

        /** 静默收敛提前结束（毫秒）：已发现主机且该时长内无新主机应答 → 提前结束 */
        private const val QUIET_EXIT_MS = 1_200L

        /** 阶段一（广播）最长耗时（毫秒）：超过仍无应答则进入单播扫描兜底 */
        private const val BROADCAST_PHASE_MS = 4_000L

        /** 阶段二（单播扫描）最长耗时（毫秒）：与阶段一合计 ≤ 默认总窗口 10 秒 */
        private const val SWEEP_PHASE_MS = 5_500L

        /** 单播扫描重发间隔（毫秒）：5.5 秒窗口内完整扫描两轮，容忍零星丢包 */
        private const val SWEEP_RESEND_INTERVAL_MS = 3_000L

        /** 单播扫描限速：每连发 16 包暂停一次，避免突发触发路由器防洪水丢弃 */
        private const val SWEEP_BURST = 16

        /** 单播扫描限速暂停（毫秒） */
        private const val SWEEP_PACING_MS = 8L
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
     * 发起发现扫描：在 [durationMs] 时间窗口内两阶段探测并收集应答。
     * 阶段一广播探测（最多 [BROADCAST_PHASE_MS]）→ 阶段二单播扫描兜底
     * （仅当广播无任何应答时触发）。任一阶段满足「已发现主机 + 静默期
     * 无新应答」即提前收敛结束。
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
            val startedAt = System.currentTimeMillis()
            val totalDeadline = startedAt + durationMs
            val broadcastTargets = buildList {
                add(InetAddress.getByName("255.255.255.255"))
                addAll(subnetBroadcastAddresses())
            }

            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = RECV_TIMEOUT_MS
                if (com.example.boxtv.BuildConfig.DEBUG) {
                    android.util.Log.d(
                        "boxtv-discovery",
                        "阶段一广播探测开始: 广播地址=${broadcastTargets.map { it.hostAddress }} 总窗口=${durationMs}ms"
                    )
                }

                // ---- 阶段一：广播探测（原有路径，覆盖绝大多数正常环境） ----
                scanPhase(
                    socket, probe, buf, broadcastTargets, found, onHostFound,
                    deadline = minOf(totalDeadline, startedAt + BROADCAST_PHASE_MS),
                    resendIntervalMs = SEND_INTERVAL_MS
                )
                if (com.example.boxtv.BuildConfig.DEBUG) {
                    android.util.Log.d(
                        "boxtv-discovery",
                        "阶段一广播结束: 发现=${found.size} 台, 耗时=${System.currentTimeMillis() - startedAt}ms"
                    )
                }

                // ---- 阶段二：广播一无所获时，对本机各 /24 网段做单播扫描兜底 ----
                // 覆盖：电视/路由器丢广播包、255.255.255.255 走错网卡等设备侧问题；
                // 电脑端 DiscoveryResponder 对单播探测同样应答，协议无需变更。
                if (found.isEmpty() && System.currentTimeMillis() < totalDeadline) {
                    val sweepTargets = unicastSweepTargets()
                    if (sweepTargets.isNotEmpty()) {
                        if (com.example.boxtv.BuildConfig.DEBUG) {
                            android.util.Log.d(
                                "boxtv-discovery",
                                "广播无应答，启动阶段二网段单播扫描: 目标=${sweepTargets.size} 个 IP"
                            )
                        }
                        scanPhase(
                            socket, probe, buf, sweepTargets, found, onHostFound,
                            deadline = minOf(totalDeadline, System.currentTimeMillis() + SWEEP_PHASE_MS),
                            resendIntervalMs = SWEEP_RESEND_INTERVAL_MS
                        )
                    }
                }
            }
            if (com.example.boxtv.BuildConfig.DEBUG) {
                android.util.Log.d(
                    "boxtv-discovery",
                    "扫描结束: ${found.size} 台主机 [${found.values.joinToString { "${it.hostName}(${it.host})" }}] 总耗时=${System.currentTimeMillis() - startedAt}ms"
                )
            }
            found.values.toList()
        }

    /**
     * 单阶段扫描循环：按 [resendIntervalMs] 周期性向 [targets] 发送探测包并
     * 收集应答，直到 [deadline] 或静默收敛。目标数较多（单播扫描 254 个 IP）
     * 时分批限速发送，避免瞬时突发被路由器/驱动丢弃。
     */
    private fun scanPhase(
        socket: DatagramSocket,
        probe: ByteArray,
        buf: ByteArray,
        targets: List<InetAddress>,
        found: LinkedHashMap<String, DiscoveredHost>,
        onHostFound: ((DiscoveredHost) -> Unit)?,
        deadline: Long,
        resendIntervalMs: Long
    ) {
        var lastSendAt = 0L    // 0 = 循环首轮立即首发，不等第一个间隔
        var lastFoundAt = 0L   // 最近一次发现新主机的时刻（静默收敛基准）

        while (System.currentTimeMillis() < deadline) {
            // 周期性重发探测包（丢包容错）
            val now = System.currentTimeMillis()
            if (now - lastSendAt >= resendIntervalMs) {
                if (targets.size > SWEEP_BURST) {
                    // 单播扫描：分批限速发送，避免瞬时突发触发路由器防洪水丢弃
                    targets.forEachIndexed { i, addr ->
                        runCatching {
                            socket.send(DatagramPacket(probe, probe.size, addr, DISCOVERY_PORT))
                        }
                        if (i % SWEEP_BURST == SWEEP_BURST - 1) Thread.sleep(SWEEP_PACING_MS)
                    }
                } else {
                    targets.forEach { addr ->
                        runCatching {
                            socket.send(DatagramPacket(probe, probe.size, addr, DISCOVERY_PORT))
                        }
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
                        lastFoundAt = System.currentTimeMillis()
                        if (com.example.boxtv.BuildConfig.DEBUG) {
                            android.util.Log.d("boxtv-discovery", "发现主机: ${host.hostName} ($ip:${host.httpPort})")
                        }
                        onHostFound?.invoke(host)
                    }
                }
            } catch (_: SocketTimeoutException) {
                // 静默收敛：已发现主机且静默期内无新应答 → 提前结束扫描
                if (found.isNotEmpty() &&
                    System.currentTimeMillis() - lastFoundAt >= QUIET_EXIT_MS
                ) break
            } catch (_: Exception) {
                // 单包解析失败不影响整体扫描
            }
        }
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

    /**
     * 枚举本机各 IPv4 网卡所在 /24 网段的全部主机地址（x.y.z.1 ~ x.y.z.254），
     * 作为广播无应答时的单播扫描目标。按目标地址由操作系统精确选择路由
     * 网卡——天然规避"受限广播只走默认路由"的多网卡错配问题。
     * 枚举失败返回空列表（此时跳过兜底阶段，保持原广播行为）。
     */
    private fun unicastSweepTargets(): List<InetAddress> {
        return runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .flatMap { ip ->
                    val b = ip.address
                    (1..254).asSequence().map { i ->
                        InetAddress.getByAddress(byteArrayOf(b[0], b[1], b[2], i.toByte()))
                    }
                }
                .toList()
        }.getOrDefault(emptyList())
    }
}
