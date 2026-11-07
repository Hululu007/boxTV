package com.example.boxtv.ui.main

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.boxtv.BoxTvApp
import com.example.boxtv.R
import com.example.boxtv.data.remote.ApiResult
import com.example.boxtv.data.remote.UpdateCheckData
import com.example.boxtv.data.update.UpdateManager
import com.example.boxtv.model.PairedHost
import com.example.boxtv.ui.browser.HostBrowserActivity
import com.example.boxtv.ui.common.NetworkName
import com.example.boxtv.ui.common.bindFocusHighlight
import com.example.boxtv.ui.common.bindSidebarFocusScale
import com.example.boxtv.ui.common.resetFocusHighlight
import com.example.boxtv.ui.pairing.PairingActivity
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主页（ui/main 层）—— 2026-11-07 参考 UI 设计稿改版
 *
 * 布局结构（参照设计稿复刻）：
 * - 左侧白色侧边栏：boxTV 标识 + 「主页 / 设置」菜单（view_sidebar 共享布局）；
 *   菜单项选中态 = 黑色描边胶囊，遥控器聚焦态 = 实心黑胶囊光标 + 轻微放大；
 * - 头部状态栏：当前网络名称 / 电脑主机名称 / 连接状态 + 刷新胶囊按钮；
 *   其中「电脑主机名称 / 连接状态」跟随网格焦点实时联动；
 * - 内容区：已配对电脑缩略图网格（5 列），
 *   遥控器聚焦时出现白色光标描边 + 轻微放大（见 FocusHighlight.kt）；
 * - 设置页（2026-11-07 落地）：「添加主机」按钮 + 当前连接主机名称 /
 *   连接状态 / 当前网络（与头部状态栏同源数据）。
 *
 * 功能与入口变化（2026-11-07）：
 * - 「搜索」按钮已从主页移除：搜索 = 浏览页内搜索当前文件夹内容；
 * - 「添加主机」入口移入设置页（原头部搜索按钮职责）。
 *
 * 功能保留：
 * - 单击主机卡片 → 进入浏览页；长按（OK 长按）→ 删除确认；
 * - 刷新按钮 → 逐台 ping 检测在线状态；
 * - 主机列表订阅 LocalStore.hosts 流实时刷新。
 */
class MainActivity : FragmentActivity() {

    // ------------------------------------------------------------------
    // 视图引用
    // ------------------------------------------------------------------
    private lateinit var homeItem: TextView
    private lateinit var settingsItem: TextView
    private lateinit var homePanel: View
    private lateinit var devPanel: View
    private lateinit var tvNetwork: TextView
    private lateinit var tvHostName: TextView
    private lateinit var tvConnection: TextView
    private lateinit var emptyHint: TextView
    private lateinit var hostGrid: RecyclerView

    // 设置面板（参照设计稿二：添加主机 + 连接信息 + 软件版本）
    private lateinit var settingsHostName: TextView
    private lateinit var settingsConnection: TextView
    private lateinit var settingsNetwork: TextView
    private lateinit var settingsVersion: TextView

    // 设置面板（2026-11-07 局域网更新）：检查更新状态行
    private lateinit var settingsUpdateStatus: TextView

    /** 头部当前展示的主机名（设置面板「当前连接主机名称」同源复用） */
    private var headerHostName: String? = null

    /** 头部当前连接状态文案（设置面板同源复用） */
    private var settingsConnectionText: String? = null

    private lateinit var hostAdapter: HostGridAdapter

    // ------------------------------------------------------------------
    // 运行状态
    // ------------------------------------------------------------------

    /** 主机在线状态缓存（deviceId → 是否可达；空 = 尚未检测，刷新/聚焦检测后更新） */
    private var onlineStatus: Map<String, Boolean> = emptyMap()

    /** 手动刷新进行中标记（防止重复点击并发 ping） */
    private var refreshing = false

    /** 单台静默检测进行中集合（聚焦联动 ping 防抖：同一主机不重复发） */
    private val pinging = mutableSetOf<String>()

    /** 头部状态栏当前展示的主机 id（网格焦点 / 列表首台驱动） */
    private var headerHostId: String? = null

    /** 当前侧边栏页签 */
    private var currentTab = TAB_HOME

    /** 首次收到主机列表时是否已做过静默在线检测 */
    private var autoPingDone = false

    companion object {
        /** 网格列数（参照设计稿 5 列） */
        private const val GRID_SPAN = 5

        /** 侧边栏页签：主页 */
        const val TAB_HOME = 0

        /** 侧边栏页签：设置 */
        const val TAB_SETTINGS = 1

        /** Intent Extra：主页初始页签（浏览页侧边栏跳设置页用） */
        const val EXTRA_OPEN_TAB = "openTab"

        /** Intent Extra：更新完成后的首启标记（UpdateDoneReceiver 自动拉起时携带） */
        const val EXTRA_JUST_UPDATED = "justUpdated"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        homeItem = findViewById(R.id.sidebar_home)
        settingsItem = findViewById(R.id.sidebar_settings)
        homePanel = findViewById(R.id.home_panel)
        devPanel = findViewById(R.id.dev_panel)
        tvNetwork = findViewById(R.id.tv_network)
        tvHostName = findViewById(R.id.tv_host_name)
        tvConnection = findViewById(R.id.tv_connection)
        emptyHint = findViewById(R.id.empty_hint)
        hostGrid = findViewById(R.id.host_grid)
        settingsHostName = findViewById(R.id.settings_host_name)
        settingsConnection = findViewById(R.id.settings_connection)
        settingsNetwork = findViewById(R.id.settings_network)
        settingsVersion = findViewById(R.id.settings_version)

        // ---- 侧边栏菜单：聚焦缩放 + 点击切换页签 ----
        homeItem.bindSidebarFocusScale()
        settingsItem.bindSidebarFocusScale()
        homeItem.setOnClickListener { if (currentTab != TAB_HOME) selectTab(TAB_HOME) }
        settingsItem.setOnClickListener { if (currentTab != TAB_SETTINGS) selectTab(TAB_SETTINGS) }

        // ---- 设置面板：「添加主机」→ 配对引导（2026-11-07 设置页落地） ----
        findViewById<TextView>(R.id.btn_add_host).apply {
            bindFocusHighlight()
            setOnClickListener { openPairing() }
        }

        // ---- 设置面板：「检查更新」（2026-11-07 局域网更新功能） ----
        settingsUpdateStatus = findViewById(R.id.settings_update_status)
        findViewById<TextView>(R.id.btn_check_update).apply {
            bindFocusHighlight()
            setOnClickListener { checkForUpdate() }
        }

        // ---- 头部按钮：刷新 → 检测各电脑在线状态 ----
        //（搜索按钮已移除：搜索 = 浏览页内搜索当前文件夹内容；添加主机在设置页）
        findViewById<TextView>(R.id.btn_refresh).apply {
            bindFocusHighlight()
            setOnClickListener { refreshHosts(quiet = false) }
        }

        // ---- 空态提示：可聚焦，按 OK 前往设置页添加主机（首启无触摸屏场景） ----
        emptyHint.bindFocusHighlight()
        emptyHint.setOnClickListener {
            if (currentTab != TAB_SETTINGS) selectTab(TAB_SETTINGS)
        }

        // ---- 内容网格：5 列（参照设计稿），焦点联动头部状态栏 ----
        hostAdapter = HostGridAdapter(
            onClick = { host -> openBrowser(host) },
            onLongClick = { host -> confirmRemoveHost(host) },
            onFocus = { host -> onGridItemFocused(host) }
        )
        hostGrid.apply {
            layoutManager = GridLayoutManager(this@MainActivity, GRID_SPAN)
            adapter = hostAdapter
        }

        // ---- 订阅已配对主机流 → 网格与头部实时刷新 ----
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                (application as BoxTvApp).repository.pairedHosts.collect { hosts ->
                    hostAdapter.submit(hosts)
                    emptyHint.isVisible = hosts.isEmpty()
                    refreshHeaderForList(hosts)
                    // 首次拿到主机列表：静默做一轮在线检测，头部「连接状态」即可用
                    if (!autoPingDone && hosts.isNotEmpty()) {
                        autoPingDone = true
                        refreshHosts(quiet = true)
                    }
                }
            }
        }

        // ---- 启动时解析当前网络名称（Wi-Fi SSID / 以太网） ----
        loadNetworkName()

        // ---- 设置面板：显示当前软件版本号（2026-11-07 需求） ----
        loadAppVersion()

        // ---- 更新完成后的自动重启首启（UpdateDoneReceiver 携带标记）：
        //      Toast 引导确认版本迭代已完成（需求 6） ----
        if (intent.getBooleanExtra(EXTRA_JUST_UPDATED, false)) {
            Toast.makeText(this, "boxTV 已更新到 v${currentVersionName()}", Toast.LENGTH_LONG).show()
        }

        // ---- 初始页签：主页（或外部指定，如浏览页侧边栏跳转设置页）；
        //      并预置焦点到当前页签菜单（遥控器首按体验） ----
        val initialTab = intent.getIntExtra(EXTRA_OPEN_TAB, TAB_HOME)
        selectTab(initialTab)
        val initialItem = if (initialTab == TAB_SETTINGS) settingsItem else homeItem
        initialItem.post { initialItem.requestFocus() }
    }

    // ==================================================================
    // 侧边栏页签
    // ==================================================================

    /**
     * 切换页签：菜单选中态（描边胶囊）+ 右侧面板（主页面板 / 设置面板）
     */
    private fun selectTab(tab: Int) {
        currentTab = tab
        val isHome = tab == TAB_HOME
        homeItem.isSelected = isHome
        settingsItem.isSelected = !isHome
        homePanel.isVisible = isHome
        devPanel.isVisible = !isHome
        // 设置面板信息与头部状态栏同源，切到设置页时同步一次
        if (!isHome) syncSettingsPanel()
    }

    // ==================================================================
    // 头部状态栏（当前网络 / 电脑主机名称 / 连接状态）+ 设置面板（同源数据）
    // ==================================================================

    /** 已解析的当前网络名称（异步回填，设置面板与头部共用） */
    private var networkName: String? = null

    /** 启动时解析当前网络名称（子线程），头部与设置面板共用结果 */
    private fun loadNetworkName() {
        lifecycleScope.launch(Dispatchers.IO) {
            val name = NetworkName.resolve(this@MainActivity)
            withContext(Dispatchers.Main) {
                networkName = name
                tvNetwork.text = "当前网络：$name"
                syncSettingsPanel()
            }
        }
    }

    /**
     * 读取并展示当前软件版本号（PackageManager 的 versionName，
     * 与 build.gradle.kts 的 versionName 同源，无需额外维护常量）。
     */
    private fun loadAppVersion() {
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull()
        settingsVersion.text = "软件版本：${version ?: "未知"}"
    }

    /** 网格卡片聚焦 → 头部「电脑主机名称」联动 + 触发该主机静默在线检测 */
    private fun onGridItemFocused(host: PairedHost) {
        headerHostId = host.hostDeviceId
        headerHostName = host.name
        tvHostName.text = "电脑主机名称：${host.name}"
        updateConnectionText(host)
        pingQuiet(host)
    }

    /** 主机列表更新后刷新头部：优先保持当前聚焦主机，否则回落到第一台 */
    private fun refreshHeaderForList(hosts: List<PairedHost>) {
        val current = hosts.find { it.hostDeviceId == headerHostId }
        val host = current ?: hosts.firstOrNull()
        headerHostId = host?.hostDeviceId
        headerHostName = host?.name
        tvHostName.text = "电脑主机名称：${host?.name ?: "—"}"
        updateConnectionText(host)
    }

    /** 头部「连接状态」文案：正常 / 已断开 / 检测中… / 未检测 / 未连接 */
    private fun updateConnectionText(host: PairedHost?) {
        val status = when {
            host == null -> "未连接"
            onlineStatus[host.hostDeviceId] == true -> "正常"
            onlineStatus[host.hostDeviceId] == false -> "已断开"
            refreshing || host.hostDeviceId in pinging -> "检测中…"
            else -> "未检测"
        }
        tvConnection.text = "连接状态：$status"
        // 设置面板「连接状态」与头部同源
        settingsConnectionText = status
        syncSettingsPanel()
    }

    /**
     * 同步设置面板文案（与头部状态栏同源数据，参照设计稿二）：
     * 添加主机按钮 + 当前连接主机名称 / 连接状态 / 当前网络。
     */
    private fun syncSettingsPanel() {
        if (!this::settingsHostName.isInitialized) return // onCreate 绑定前调用的兜底
        settingsHostName.text = "当前连接主机名称：${headerHostName ?: "—"}"
        settingsConnection.text = "连接状态：${settingsConnectionText ?: "未连接"}"
        settingsNetwork.text = "当前网络：${networkName ?: "检测中…"}"
    }

    /**
     * 静默单台检测：网格聚焦到未检测过的主机时 ping 一次，
     * 结果写回 onlineStatus 并刷新头部状态（不弹 Toast，不打扰浏览）。
     */
    private fun pingQuiet(host: PairedHost) {
        // 已有结果：仅刷新文案即可
        if (onlineStatus.containsKey(host.hostDeviceId)) {
            updateConnectionText(host)
            return
        }
        // 同一主机检测中：防抖，不重复发
        if (!pinging.add(host.hostDeviceId)) return
        updateConnectionText(host) // 显示「检测中…」
        lifecycleScope.launch {
            val ok = (application as BoxTvApp).repository.ping(host.host, host.port) is ApiResult.Ok
            onlineStatus = onlineStatus + (host.hostDeviceId to ok)
            pinging.remove(host.hostDeviceId)
            // 该主机仍是头部展示主机时才刷新文案（焦点可能已移走）
            if (headerHostId == host.hostDeviceId) updateConnectionText(host)
        }
    }

    // ==================================================================
    // 在线状态刷新（头部「刷新」按钮 / 首次进入静默触发）
    // ==================================================================

    /**
     * 刷新主机在线状态：逐台 ping 后更新缓存与头部状态。
     * @param quiet true = 静默检测（无 Toast，启动时自动触发用）；
     *              false = 手动刷新（Toast 反馈过程与结果）。
     */
    private fun refreshHosts(quiet: Boolean) {
        if (refreshing) return
        refreshing = true
        val repo = (application as BoxTvApp).repository
        lifecycleScope.launch {
            val hosts = repo.pairedHosts.value
            if (hosts.isEmpty()) {
                refreshing = false
                updateConnectionText(null)
                if (!quiet) Toast.makeText(this@MainActivity, "没有已配对的主机", Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (!quiet) Toast.makeText(this@MainActivity, "正在刷新主机状态…", Toast.LENGTH_SHORT).show()
            // 逐台 ping（ping 无需鉴权，Ktor 内部为异步 IO）
            val status = hosts.associate { h ->
                h.hostDeviceId to (repo.ping(h.host, h.port) is ApiResult.Ok)
            }
            onlineStatus = status
            // 头部展示中的主机状态即时刷新
            hosts.find { it.hostDeviceId == headerHostId }?.let { updateConnectionText(it) }
            if (!quiet) {
                val online = status.values.count { it }
                Toast.makeText(
                    this@MainActivity,
                    "刷新完成：$online/${hosts.size} 台在线",
                    Toast.LENGTH_SHORT
                ).show()
            }
            refreshing = false
        }
    }

    // ==================================================================
    // 局域网应用更新（2026-11-07 需求，全程局域网、无公网参与）
    // ==================================================================

    /** 更新检查进行中标记（防重复点击并发扫描） */
    private var checkingUpdate = false

    /** 下载弹窗引用（状态流回调里刷新 UI / 判重） */
    private var updateDialog: AlertDialog? = null

    /** 当前电视端 versionCode（与电脑端发布版本的 versionCode 比较） */
    private fun currentVersionCode(): Long = runCatching {
        PackageInfoCompat.getLongVersionCode(packageManager.getPackageInfo(packageName, 0))
    }.getOrDefault(0L)

    /** 当前电视端 versionName（弹窗与提示展示用） */
    private fun currentVersionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    }.getOrDefault("")

    /**
     * 检查更新入口（设置页「检查更新」按钮，遥控器 OK 触发）：
     * 自动扫描局域网内全部已配对电脑（需求 3），汇总各自发布的版本，
     * 取 versionCode 最高且高于本机版本的新版本进入更新弹窗（需求 4）。
     * 全部不可达时给出错误提示与重试入口（需求 8）。
     */
    private fun checkForUpdate() {
        if (checkingUpdate) return
        val repo = (application as BoxTvApp).repository
        val hosts = repo.pairedHosts.value
        if (hosts.isEmpty()) {
            Toast.makeText(this, "尚未配对电脑主机，请先添加主机", Toast.LENGTH_SHORT).show()
            return
        }
        checkingUpdate = true
        setUpdateStatus("正在扫描局域网内的电脑（0/${hosts.size}）…")
        lifecycleScope.launch {
            var found: Pair<PairedHost, UpdateCheckData>? = null
            var reachable = 0
            var lastError = ""
            hosts.forEachIndexed { index, host ->
                setUpdateStatus("正在扫描局域网内的电脑（${index + 1}/${hosts.size}）…")
                when (val r = repo.checkUpdate(host)) {
                    is ApiResult.Ok -> {
                        reachable++
                        val d = r.data
                        // 只接受「已发布 + versionCode 高于本机」的版本，多台电脑取最高
                        if (d.available && d.versionCode > currentVersionCode()) {
                            val cur = found
                            if (cur == null || d.versionCode > cur.second.versionCode) {
                                found = host to d
                            }
                        }
                    }
                    is ApiResult.ApiError -> lastError =
                        if (r.isUnauthorized) "「${host.name}」授权已失效，请重新配对"
                        else "「${host.name}」：${r.message}"
                    is ApiResult.NetworkError -> lastError = "「${host.name}」无法连接"
                }
            }
            checkingUpdate = false
            val target = found
            when {
                target != null -> {
                    setUpdateStatus("发现新版本 v${target.second.versionName}（来自「${target.first.name}」）")
                    showUpdateAvailableDialog(target.first, target.second)
                }
                reachable > 0 -> {
                    // 电脑端在线但未发布新版本 / 发布版本不高于本机
                    setUpdateStatus("已是最新版本 v${currentVersionName()}")
                    infoDialog("检查更新", "当前版本 v${currentVersionName()} 已是最新。")
                }
                else -> {
                    // 需求 8：局域网内电脑端不在线 → 错误提示 + 重试
                    setUpdateStatus("检查更新失败：无法连接局域网内的电脑")
                    AlertDialog.Builder(this@MainActivity, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
                        .setTitle("检查更新失败")
                        .setMessage("无法连接局域网内的电脑，请确认电脑端 boxTV 已启动。\n${lastError}")
                        .setPositiveButton("重试") { _, _ -> checkForUpdate() }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
        }
    }

    /** 更新提示弹窗（需求 4）：展示版本号 / 大小 / 更新说明，
     *  「立即安装」进入下载，「稍后提醒」仅关闭弹窗。 */
    private fun showUpdateAvailableDialog(host: PairedHost, meta: UpdateCheckData) {
        val sizeMb = String.format(Locale.US, "%.1f", meta.size / 1024.0 / 1024.0)
        val message = buildString {
            append("新版本：v${meta.versionName}（${sizeMb}MB）\n")
            append("当前版本：v${currentVersionName()}\n")
            append("来源主机：${host.name}")
            if (meta.changelog.isNotBlank()) append("\n\n更新说明：\n${meta.changelog}")
        }
        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("发现新版本")
            .setMessage(message)
            .setPositiveButton("立即安装") { _, _ -> startDownloadFlow(host, meta) }
            .setNegativeButton("稍后提醒", null)
            .show()
    }

    /**
     * 下载流程（需求 5）：进度条弹窗订阅 UpdateManager 状态流实时刷新，
     * 支持 暂停/继续 与 取消；失败态切换为「重试」按钮（断点续传或整包重下）。
     */
    private fun startDownloadFlow(host: PairedHost, meta: UpdateCheckData) {
        val app = application as BoxTvApp
        val repo = app.repository
        // 已有校验通过的安装包（如安装被中断、授权后返回）：版本一致才跳过下载直接安装；
        // 残留旧版本安装包（新版已装成功但未清理的异常场景）→ 清理后走正常下载，
        // 保证更新检测只识别当前有效安装包（2026-11-07 安装包自动清理）
        app.updateManager.readyApk?.let { ready ->
            if (app.updateManager.readyApkVersion == meta.versionName) {
                triggerInstall(ready, meta)
                return
            }
            app.updateManager.cleanupInstalledPackage()
        }

        val view = layoutInflater.inflate(R.layout.dialog_update_download, null)
        val versionText = view.findViewById<TextView>(R.id.dlg_update_version)
        val progress = view.findViewById<ProgressBar>(R.id.dlg_update_progress)
        val detail = view.findViewById<TextView>(R.id.dlg_update_detail)
        val statusText = view.findViewById<TextView>(R.id.dlg_update_status)
        versionText.text = "正在下载 v${meta.versionName}"

        val dialog = AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("下载更新")
            .setView(view)
            .setPositiveButton("暂停", null)
            .setNegativeButton("取消", null)
            .create()
        updateDialog = dialog

        var lastState: UpdateManager.DownloadState = UpdateManager.DownloadState.Idle
        var collectJob: Job? = null
        dialog.setOnShowListener {
            // 按钮行为随状态切换：暂停 / 继续 / 重试（取消固定为关闭并终止下载）
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                when (lastState) {
                    is UpdateManager.DownloadState.Downloading -> app.updateManager.pause()
                    is UpdateManager.DownloadState.Paused,
                    is UpdateManager.DownloadState.Failed -> app.updateManager.resume()
                    else -> {}
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                app.updateManager.cancel()
                dialog.dismiss()
            }
        }
        dialog.setOnDismissListener {
            collectJob?.cancel()
            if (updateDialog === dialog) updateDialog = null
        }
        dialog.show()

        collectJob = lifecycleScope.launch {
            app.updateManager.state.collect { s ->
                lastState = s
                val posBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                when (s) {
                    is UpdateManager.DownloadState.Downloading -> {
                        posBtn.text = "暂停"
                        posBtn.isEnabled = true
                        val total = s.total
                        if (total > 0) {
                            progress.progress = ((s.downloaded * 100) / total).toInt()
                            detail.text = String.format(
                                Locale.US, "%d%%（%.1fMB / %.1fMB）",
                                progress.progress, s.downloaded / 1048576.0, total / 1048576.0
                            )
                        } else {
                            progress.isIndeterminate = true
                            detail.text = String.format(Locale.US, "%.1fMB", s.downloaded / 1048576.0)
                        }
                        statusText.text = ""
                    }
                    is UpdateManager.DownloadState.Paused -> {
                        posBtn.text = "继续"
                        posBtn.isEnabled = true
                        statusText.text = "已暂停（保留下载进度），按「继续」断点续传"
                    }
                    UpdateManager.DownloadState.Verifying -> {
                        posBtn.isEnabled = false
                        statusText.text = "正在校验安装包完整性…"
                    }
                    is UpdateManager.DownloadState.Success -> {
                        dialog.dismiss()
                        triggerInstall(s.apk, meta)
                    }
                    is UpdateManager.DownloadState.Failed -> {
                        posBtn.text = "重试"
                        posBtn.isEnabled = true
                        statusText.text = s.message +
                            if (s.canResume) "（按「重试」从断点继续）" else "（按「重试」重新下载）"
                    }
                    UpdateManager.DownloadState.Idle -> {}
                }
            }
        }

        // 发起下载（url 由 repository 构造，Token 由 UpdateManager 携带）
        app.updateManager.start(
            host, repo.updateDownloadUrl(host), host.token, meta
        )
    }

    /**
     * 下载完成 → 触发安装（需求 6）。安装由系统安装器接管，覆盖安装会结束
     * 本应用进程；新版本安装完成后由 UpdateDoneReceiver（MY_PACKAGE_REPLACED）
     * 自动重启应用并提示，完成版本迭代。
     */
    private fun triggerInstall(apk: File, meta: UpdateCheckData) {
        setUpdateStatus("安装包已就绪，正在启动安装…")
        val ok = (application as BoxTvApp).updateManager.install(this)
        if (ok) {
            AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
                .setTitle("准备安装 v${meta.versionName}")
                .setMessage(
                    "即将开始安装，安装过程中应用会自动关闭。\n" +
                        "安装完成后 boxTV 将自动重新启动，无需其他操作。"
                )
                .setPositiveButton("开始安装", null)
                .show()
        } else {
            // Android 8+ 未授权「安装未知应用」：已跳转系统设置页，授权后重试即可
            AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
                .setTitle("需要安装授权")
                .setMessage("请在接下来的页面允许 boxTV「安装未知应用」。\n授权返回后，再次点击「检查更新 → 立即安装」即可继续（安装包已保留，无需重新下载）。")
                .setPositiveButton("知道了", null)
                .show()
            setUpdateStatus("安装包已就绪：等待「安装未知应用」授权")
        }
    }

    /** 设置面板「检查更新」状态行刷新 */
    private fun setUpdateStatus(text: String) {
        settingsUpdateStatus.isVisible = text.isNotEmpty()
        settingsUpdateStatus.text = text
    }

    /** 通用信息对话框（应用主题为 Theme.Leanback，必须显式传 AppCompat 主题） */
    private fun infoDialog(title: String, message: String) {
        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("确定", null)
            .show()
    }

    // ==================================================================
    // 页面跳转 / 主机管理（功能保留）
    // ==================================================================

    /** 进入主机浏览页 */
    private fun openBrowser(host: PairedHost) {
        startActivity(
            Intent(this, HostBrowserActivity::class.java)
                .putExtra(HostBrowserActivity.EXTRA_HOST_DEVICE_ID, host.hostDeviceId)
        )
    }

    /** 进入配对引导页（头部「搜索」按钮 / 空态提示的入口） */
    private fun openPairing() {
        startActivity(Intent(this, PairingActivity::class.java))
    }

    /**
     * 长按主机 → 删除确认对话框（功能保留）。
     * 删除后电视端失去该电脑的访问授权记录，需重新配对才能再次访问；
     * 电脑端的设备与 Token 同步失效（下次请求返回 401，电脑端可清理）。
     */
    private fun confirmRemoveHost(host: PairedHost) {
        // 应用主题为 Theme.Leanback（非 AppCompat），androidx 对话框必须显式传入
        // AppCompat 主题，否则 show() 时抛 IllegalStateException
        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("删除主机")
            .setMessage("确定删除「${host.name}」吗？\n删除后需要重新配对才能访问该电脑。")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    (application as BoxTvApp).repository.removeHost(host.hostDeviceId)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity,
                            "已删除「${host.name}」，需要重新配对",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================================================================
    // 内容网格适配器：缩略图图块（焦点光标）+ 主机名称
    // ==================================================================

    private class HostGridAdapter(
        private val onClick: (PairedHost) -> Unit,
        private val onLongClick: (PairedHost) -> Unit,
        private val onFocus: (PairedHost) -> Unit
    ) : RecyclerView.Adapter<HostGridAdapter.VH>() {

        private var hosts: List<PairedHost> = emptyList()

        fun submit(list: List<PairedHost>) {
            hosts = list
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = hosts.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val root = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_host_card, parent, false)
            val tile = root.findViewById<FrameLayout>(R.id.tile)
            // 遥控器焦点光标：白框 + 轻微放大（白色光标环由图块背景
            // bg_content_tile 的聚焦态提供，这里只做缩放动效，
            // 光标环不包含下方名称 —— 与设计稿光标选中图一致）；
            // 聚焦同时联动头部状态栏（主机名称/连接状态）
            tile.bindFocusHighlight(withForeground = false) { v, hasFocus ->
                if (hasFocus) (v.tag as? PairedHost)?.let(onFocus)
            }
            return VH(root)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val host = hosts[position]
            // 复用重置：清掉上一个数据残留的放大/抬升状态
            holder.tile.resetFocusHighlight()
            // tag 携带当前绑定主机：焦点回调从 tag 取数，避免 position 复用错位
            holder.tile.tag = host
            holder.name.text = host.name
            // 第 0 行卡片：上键显式直达头部「刷新」按钮。
            // 不设置时 DPAD_UP 的几何焦点搜索会落回侧边栏（候选与卡片无水平重叠
            // 时排序不稳定），导致头部按钮永远无法到达。
            holder.tile.nextFocusUpId =
                if (position < GRID_SPAN) R.id.btn_refresh else View.NO_ID
            holder.tile.setOnClickListener { onClick(host) }
            // 长按（遥控器 OK 长按 / 触摸长按）→ 删除确认对话框
            holder.tile.setOnLongClickListener {
                onLongClick(host)
                true
            }
        }

        class VH(root: View) : RecyclerView.ViewHolder(root) {
            val tile: FrameLayout = root.findViewById(R.id.tile)
            val name: TextView = root.findViewById(R.id.tv_name)
        }
    }
}
