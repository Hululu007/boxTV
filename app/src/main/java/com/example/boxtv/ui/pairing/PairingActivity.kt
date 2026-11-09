package com.example.boxtv.ui.pairing

import android.os.Bundle
import android.text.InputType
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction
import androidx.lifecycle.lifecycleScope
import com.example.boxtv.BoxTvApp
import com.example.boxtv.R
import com.example.boxtv.data.remote.ApiCodes
import com.example.boxtv.data.remote.ApiResult
import com.example.boxtv.model.DiscoveredHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 配对引导（ui/pairing 层）
 *
 * 2026-11-08 配对机制重构（免配对码）：三步 GuidedStepFragment 引导：
 * - 步骤 1「选择主机」：UDP 自动搜索局域网内可用电脑端（发现即快速收敛，最长 10 秒兜底）
 *   + 手动输入 IP 兜底，展示可配对主机列表供用户选择；
 * - 步骤 2「等待允许」：选中主机后自动向电脑端发送配对请求，电脑端弹出配对通知
 *   （设备名 + 请求发起时间 + 300 秒有效等待时长），电视端每 2 秒自动轮询配对结果；
 *   电脑端用户点击「允许配对」后电视端直接拿到 Token 完成配对（全程无需任何输入操作）；
 * - 步骤 3「配对成功」：Token 已持久化，返回主页。
 *
 * 结果分支：
 * - 电脑端点击「拒绝配对」→ 提示已拒绝，可「重新发起配对」；
 * - 电脑端 300 秒内未处理 → 请求自动失效，提示超时，可「重新发起配对」；
 * - 发起请求网络失败 → 提示原因，可重试或返回重新选择主机。
 *
 * Token 失效重新配对同样走此流程。
 */
class PairingActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, PairingStepFragment())
                .commit()
        }
    }
}

/**
 * 配对引导步骤实现（GuidedStepFragment）
 */
class PairingStepFragment : GuidedStepSupportFragment() {

    companion object {
        // 配对请求总等待时限：电脑端有效期 300 秒 + 10 秒网络余量（超时提示失效）
        private const val WAIT_TIMEOUT_MS = 310_000L

        /** 轮询间隔（毫秒）：每 2 秒向电脑端查询一次配对结果 */
        private const val POLL_INTERVAL_MS = 2_000L

        // 步骤索引
        private const val STEP_SELECT = 0   // 选择主机
        private const val STEP_WAIT = 1     // 等待电脑端允许
        private const val STEP_DONE = 2     // 配对成功

        // 动作 id
        private const val ACTION_SCANNING = 1L        // 状态行占位（不可点）
        private const val ACTION_HOST_BASE = 100L     // 发现的主机 id 基数（+索引）
        private const val ACTION_MANUAL = 900L        // 手动输入 IP
        private const val ACTION_RESCAN = 901L        // 重新搜索
        private const val ACTION_RESEND = 912L        // 重新发起配对（超时/拒绝/失败后重试）
        private const val ACTION_BACK = 913L          // 返回重新选择主机
        private const val ACTION_FINISH = 999L        // 完成配对
    }

    /** 当前步骤 */
    private var step = STEP_SELECT

    /** 配对目标（选择主机后填充） */
    private var targetHost: String = ""
    private var targetPort: Int = 0
    private var targetDeviceId: String = ""
    private var targetName: String = ""

    /** 当前配对会话 id（电脑端签发，轮询结果时回传） */
    private var pairingId: String = ""

    /** 最近一次扫描到的主机列表（用于点击 id 反查） */
    private var discovered: List<DiscoveredHost> = emptyList()

    private val repository get() = (requireActivity().application as BoxTvApp).repository

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance {
        // 标题保持静态（GuidedStepSupportFragment 无 guidance 刷新 API），
        // 动态状态信息由各步骤 actions 列表首行的「状态行」承载
        return GuidanceStylist.Guidance(
            "连接电脑端",
            "选择主机并发起配对，在电脑端点击「允许配对」即可完成连接",
            getString(R.string.app_name),
            null
        )
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        when (step) {
            STEP_SELECT -> buildSelectActions(actions, emptyList())
            STEP_WAIT -> buildWaitActions(actions)
            STEP_DONE -> buildDoneActions(actions)
        }
    }

    // ------------------------------------------------------------------
    // 步骤 1：选择主机（搜索局域网内可配对的电脑端）
    // ------------------------------------------------------------------

    private fun buildSelectActions(actions: MutableList<GuidedAction>, hosts: List<DiscoveredHost>) {
        actions.clear()
        // 状态行：当前步骤与动态信息（infoOnly 不可交互）
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_SCANNING)
                .title("步骤 1/2 · 选择要连接的电脑")
                .description(if (hosts.isEmpty()) "正在搜索局域网内的电脑端…" else "发现 ${hosts.size} 台电脑，选择后发送配对请求")
                .infoOnly(true)
                .build()
        )
        if (hosts.isNotEmpty()) {
            hosts.forEachIndexed { index, host ->
                actions.add(
                    GuidedAction.Builder(requireContext())
                        .id(ACTION_HOST_BASE + index)
                        .title(host.hostName)
                        .description("${host.host}:${host.httpPort}")
                        .build()
                )
            }
        }
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_MANUAL)
                .title("手动输入 IP")
                .description("格式：192.168.1.100:8964")
                .editable(true)
                .editInputType(InputType.TYPE_CLASS_TEXT)
                .build()
        )
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_RESCAN)
                .title("重新搜索")
                .build()
        )
    }

    /** 启动 UDP 发现扫描（静默收敛快速结束：发现主机后 1.2 秒无新应答即出结果，
     *  无应答时最长 10 秒兜底，细则 4.1/7）。
     *  已配对的主机从发现列表中过滤——同一台电脑不应被同一台电视重复配对
     *  （已连接的从主页进入浏览即可）。过滤前校验授权是否仍有效——
     *  电脑端可能已取消授权：
     *  - Token 有效（folders 返回成功）→ 仍过滤，不出现在可配对列表；
     *  - 返回 401（授权被取消）→ 本地移除失效主机，该电脑重新出现在可配对列表；
     *  - 网络错误（主机离线/防火墙）→ 仍过滤（离线主机点了也无法配对）。 */
    private fun startDiscovery() {
        val repo = (requireActivity().application as BoxTvApp).repository
        val paired = repo.pairedHosts.value
        // 2026-11-09 审查 W7 修复：发起新扫描前取消上一次未完成的扫描。
        // 此前「重新搜索」无防重入——10s 扫描窗口内连按 RESCAN 会并行跑两个
        // DiscoveryClient（阻塞式 receive 也无法被中断），两个协程都会 setActions
        // 重建列表：旧扫描晚完成会覆盖新扫描结果，且 setActions 重建会打断
        // 用户焦点（违反本项目「禁 setActions 重建」约束）。
        scanJob?.cancel()
        scanJob = viewLifecycleOwner.lifecycleScope.launch {
            val all = withContext(Dispatchers.IO) {
                com.example.boxtv.data.discovery.DiscoveryClient().discover(10_000L)
            }
            if (!isAdded) return@launch
            if (step == STEP_SELECT) {
                // 逐个校验"已被发现且本地已配对"的主机授权状态（仅对这些主机发起，数量少）
                paired.filter { ph -> all.any { it.hostDeviceId == ph.hostDeviceId } }
                    .forEach { ph ->
                        if (!isActive) return@launch // 扫描已被新请求取消：立即停止
                        val check = withContext(Dispatchers.IO) { repo.folders(ph) }
                        if (check is ApiResult.ApiError && check.isUnauthorized) {
                            // 授权已被电脑端取消：清理本地失效记录
                            withContext(Dispatchers.IO) { repo.removeHost(ph.hostDeviceId) }
                        }
                    }
                if (!isAdded || !isActive) return@launch
                // 以移除后的最新配对列表为准做过滤（被取消授权的主机不再被过滤）
                val pairedIds = repo.pairedHosts.value.map { it.hostDeviceId }.toSet()
                discovered = all.filter { it.hostDeviceId !in pairedIds }
                setActions(buildSelectList(discovered, pairedIds))
            }
        }
        // 立即显示搜索中占位
        discovered = emptyList()
        setActions(buildSelectList(emptyList(), paired.map { it.hostDeviceId }.toSet()))
    }

    /** 组装选择步骤的动作列表（含已配对主机过滤提示） */
    private fun buildSelectList(hosts: List<DiscoveredHost>, pairedIds: Set<String>): MutableList<GuidedAction> {
        val list = mutableListOf<GuidedAction>()
        buildSelectActions(list, hosts)
        // 所有发现的主机都已在配对列表时，提示用户直接从主页进入
        if (hosts.isEmpty() && pairedIds.isNotEmpty()) {
            list[0].description = "局域网内的电脑均已连接\n已连接的电脑请从主页「我的主机」进入"
        }
        return list
    }

    // ------------------------------------------------------------------
    // 步骤 2：等待电脑端允许（免输入：自动发请求 + 自动轮询结果）
    // ------------------------------------------------------------------

    private fun buildWaitActions(actions: MutableList<GuidedAction>) {
        actions.clear()
        // 状态行：目标主机信息与等待说明（动态状态由 updatePairStatus 原地刷新）
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_SCANNING)
                .title("步骤 2/2 · 等待电脑端允许")
                .description("已向「$targetName」发送配对请求\n请在电脑端点击「允许配对」（300 秒内有效，自动等待中）")
                .infoOnly(true)
                .build()
        )
        // 超时/拒绝/失败后的重试入口：重新发送配对请求
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_RESEND)
                .title("重新发起配对")
                .description("请求超时或被拒绝后，可重新向该电脑发送配对请求")
                .build()
        )
        // 放弃当前目标：返回步骤 1 重新选择主机
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_BACK)
                .title("返回重新选择主机")
                .build()
        )
    }

    /** 组装等待步骤动作列表（带当前状态回显） */
    private fun buildWaitList(): MutableList<GuidedAction> {
        val list = mutableListOf<GuidedAction>()
        buildWaitActions(list)
        return list
    }

    /**
     * 向目标主机发送配对请求（进入步骤 2 时调用）。
     * 电脑端收到后会弹出配对通知（设备名 + 请求发起时间 + 300 秒有效时长）；
     * 请求成功后立即启动 [waitForApproval] 自动轮询结果。
     */
    private fun requestPairing() {
        pendingPollJob?.cancel() // 重新发起：旧会话已作废，停止旧轮询
        updatePairStatus("正在发送配对请求…")
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val result = repository.pairRequest(
                targetHost,
                targetPort,
                deviceName = "boxTV-" + android.os.Build.MODEL,
                deviceModel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
            )
            if (!isAdded) return@launch
            when (result) {
                is ApiResult.Ok -> {
                    pairingId = result.data.pairingId
                    // 请求已被电脑端接收：启动轮询等待「允许配对」
                    withContext(Dispatchers.Main) { waitForApproval() }
                }
                is ApiResult.ApiError -> withContext(Dispatchers.Main) {
                    updatePairStatus("发起配对失败：${result.message}\n可点击「重新发起配对」重试")
                }
                is ApiResult.NetworkError -> withContext(Dispatchers.Main) {
                    updatePairStatus("网络错误：${result.reason}\n可点击「重新发起配对」重试，或返回重新选择主机")
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 步骤 3：配对成功
    // ------------------------------------------------------------------

    private fun buildDoneActions(actions: MutableList<GuidedAction>) {
        actions.clear()
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_SCANNING)
                .title("配对成功")
                .description("已连接到「$targetName」，现在可以在主页浏览其授权文件夹")
                .infoOnly(true)
                .build()
        )
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ACTION_FINISH)
                .title("完成")
                .build()
        )
    }

    // ------------------------------------------------------------------
    // 交互事件
    // ------------------------------------------------------------------

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            // 点击发现的主机卡片 → 发送配对请求并进入等待步骤
            in ACTION_HOST_BASE..(ACTION_HOST_BASE + discovered.size) -> {
                val index = (action.id - ACTION_HOST_BASE).toInt()
                discovered.getOrNull(index)?.let { selectDiscoveredHost(it) }
            }

            // 手动输入 IP 由 onGuidedActionEditedAndProceed 处理（可编辑动作回车确认）
            ACTION_MANUAL -> {}

            ACTION_RESCAN -> startDiscovery()

            // 超时/拒绝/失败后：向同一台电脑重新发送配对请求
            ACTION_RESEND -> requestPairing()

            // 放弃当前目标：返回步骤 1 并重新扫描
            ACTION_BACK -> {
                goToStep(STEP_SELECT)
                startDiscovery()
            }

            ACTION_FINISH -> requireActivity().finish()
        }
    }

    override fun onGuidedActionEditedAndProceed(action: GuidedAction): Long {
        when (action.id) {
            // 手动输入 IP：回车确认即解析并验证，成功后进入等待步骤
            ACTION_MANUAL -> {
                val text = action.editTitle?.toString()?.trim() ?: ""
                val parsed = parseManualAddress(text)
                if (parsed != null) {
                    resolveManualHost(parsed.first, parsed.second)
                }
                return GuidedAction.NO_ID
            }
        }
        return GuidedAction.NO_ID
    }

    /** 解析 "IP:端口" 输入 */
    private fun parseManualAddress(text: String): Pair<String, Int>? {
        val regex = Regex("^(\\d{1,3}(\\.\\d{1,3}){3}):(\\d{1,5})$")
        val m = regex.find(text) ?: return null
        val port = m.groupValues[4].toInt()
        if (port !in 1..65535) return null
        return m.groupValues[1] to port
    }

    /** 手动地址 → ping 验证可达性并获取主机信息，成功后进入等待步骤发起配对 */
    private fun resolveManualHost(ip: String, port: Int) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val result = repository.ping(ip, port)
            if (!isAdded) return@launch
            when (result) {
                is ApiResult.Ok -> {
                    targetHost = ip
                    targetPort = port
                    targetDeviceId = result.data.hostDeviceId
                    targetName = result.data.hostName
                    withContext(Dispatchers.Main) {
                        goToStep(STEP_WAIT)
                        requestPairing()
                    }
                }
                is ApiResult.ApiError -> withContext(Dispatchers.Main) { showSelectError("电脑端返回错误：${result.message}") }
                is ApiResult.NetworkError -> withContext(Dispatchers.Main) { showSelectError("无法连接 $ip:$port，请检查地址与防火墙") }
            }
        }
    }

    /** 选择发现的主机（点击卡片）：记录目标 → 进入等待步骤 → 发送配对请求 */
    private fun selectDiscoveredHost(host: DiscoveredHost) {
        targetHost = host.host
        targetPort = host.httpPort
        targetDeviceId = host.hostDeviceId
        targetName = host.hostName
        goToStep(STEP_WAIT)
        requestPairing()
    }

    /** 当前 UDP 扫描 Job（W7 修复：重新搜索先取消旧扫描，防止双扫描竞态覆盖结果） */
    private var scanJob: Job? = null

    /** 「等待电脑端允许」的轮询任务：请求发出后自动轮询直至成功/拒绝/超时 */
    private var pendingPollJob: Job? = null

    /**
     * 原地更新等待步骤状态行文字（notifyActionChanged 单行刷新）。
     * 关键约束：等待期间禁止 setActions 重建动作列表——重建会重置焦点、
     * 打断用户正在进行的操作；所有反馈一律原地刷新状态行。
     */
    private fun updatePairStatus(message: String) {
        val pos = findActionPositionById(ACTION_SCANNING)
        if (pos < 0) return
        val list = actions
        if (pos >= list.size) return
        list[pos].description = "目标：$targetName ($targetHost:$targetPort)\n$message"
        notifyActionChanged(pos)
    }

    /**
     * 等待电脑端「允许配对」：请求发出后立即开始轮询，每 [POLL_INTERVAL_MS] 重发一次
     * /api/pair/verify，直至以下三种结果之一：
     * - 成功（电脑端已允许）→ 保存 Token 进入完成步骤（电视端全程零输入）；
     * - 电脑端拒绝（40902）→ 提示已拒绝，停留在等待步骤可「重新发起配对」；
     * - 超时失效（40900 或本地总时限 [WAIT_TIMEOUT_MS] 到期）→ 提示超时。
     * 轮询期间网络抖动不中断（继续重试）；界面仅通过 updatePairStatus 原地刷新。
     */
    private fun waitForApproval() {
        pendingPollJob?.cancel()
        // 请求发起时间：本地总时限以此计算（电脑端 300 秒 + 10 秒余量）
        val requestStartedAt = System.currentTimeMillis()
        updatePairStatus("已向「$targetName」发送配对请求\n请在电脑端点击「允许配对」（300 秒内有效，自动等待中）")
        pendingPollJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                if (!isAdded) return@launch
                val r = repository.pairVerify(targetHost, targetPort, pairingId, targetDeviceId)
                if (!isAdded) return@launch
                when (r) {
                    is ApiResult.Ok -> {
                        // 电脑端已允许：Token 已由 Repository 持久化，进入完成步骤
                        targetName = r.data.name
                        withContext(Dispatchers.Main) { goToStep(STEP_DONE) }
                        return@launch
                    }
                    is ApiResult.ApiError -> when (r.code) {
                        // 仍为「等待允许」→ 继续轮询
                        ApiCodes.PAIR_PENDING -> Unit
                        // 电脑端点击了「拒绝配对」→ 停止轮询并提示
                        ApiCodes.PAIR_REJECTED -> {
                            withContext(Dispatchers.Main) {
                                updatePairStatus("电脑端已拒绝本次配对，可点击「重新发起配对」再次尝试")
                            }
                            return@launch
                        }
                        // 电脑端侧会话已失效（300 秒未处理被清理）→ 停止轮询并提示超时
                        ApiCodes.PAIR_FAILED -> {
                            withContext(Dispatchers.Main) {
                                updatePairStatus("配对请求已超时失效（电脑端 300 秒内未处理），可点击「重新发起配对」")
                            }
                            return@launch
                        }
                        // 其他业务错误：不中断等待（可能是瞬时异常），继续轮询
                        else -> Unit
                    }
                    // 网络抖动：继续轮询（电脑端允许后下一次即成功）
                    is ApiResult.NetworkError -> Unit
                }
                // 本地总时限兜底：电脑端 300 秒内始终未处理（且未返回失效结果）→ 提示超时
                if (System.currentTimeMillis() - requestStartedAt > WAIT_TIMEOUT_MS) {
                    withContext(Dispatchers.Main) {
                        updatePairStatus("配对请求已超时失效（电脑端 300 秒内未处理），可点击「重新发起配对」")
                    }
                    return@launch
                }
                kotlinx.coroutines.delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** 切换步骤（重建 actions，状态行承载步骤信息） */
    private fun goToStep(newStep: Int) {
        step = newStep
        pendingPollJob?.cancel() // 离开等待步骤时停止轮询
        val list = mutableListOf<GuidedAction>()
        onCreateActions(list, null)
        setActions(list)
    }

    /** 选择步骤错误提示（保留手动输入/重新搜索能力） */
    private fun showSelectError(message: String) {
        val pairedIds = (requireActivity().application as BoxTvApp)
            .repository.pairedHosts.value.map { it.hostDeviceId }.toSet()
        val list = buildSelectList(emptyList(), pairedIds)
        list[0].title = message
        // 2026-11-09：个别电视 WiFi 驱动/路由器会丢弃 UDP 广播包，搜索长期
        // 无结果时给出可操作的自查指引（单播兜底扫描已内置，此处覆盖仍失败的场景：
        // 多为路由器开了 AP 隔离/访客网络，或电脑端防火墙未放行 UDP 8965）
        list[0].description = "已尝试广播与网段扫描仍未发现电脑\n请检查电脑端防火墙已放行 8965/UDP，或手动输入 IP（格式 192.168.1.100:8964）"
        setActions(list)
    }

    /** 首次进入时启动主机搜索（仅一次，避免反复 onResume 重复扫描） */
    override fun onResume() {
        super.onResume()
        if (step == STEP_SELECT && !discoveryStarted) {
            discoveryStarted = true
            startDiscovery()
        }
    }

    private var discoveryStarted = false
}
