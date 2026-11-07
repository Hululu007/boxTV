package com.example.boxtv.host.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.boxtv.host.core.AccessLogger
import com.example.boxtv.host.core.ApkParser
import com.example.boxtv.host.core.AutoStartManager
import com.example.boxtv.host.core.AuthorizedFolder
import com.example.boxtv.host.core.ConfigStore
import com.example.boxtv.host.core.DeviceRegistry
import com.example.boxtv.host.core.FirewallManager
import com.example.boxtv.host.core.HostConfig
import com.example.boxtv.host.core.NetworkUtils
import com.example.boxtv.host.core.PairingSessionManager
import com.example.boxtv.host.core.UpdateMeta
import com.example.boxtv.host.core.UpdateStore
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JFileChooser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 电脑端主窗口（ui 层）
 *
 * 按细则 6.2 节布局实现完整交互 UI：
 * - 左栏「授权文件夹」：列表 + 添加 / 移除 / 在资源管理器中打开；
 * - 右栏三个 Tab：设备管理（断开授权）/ 访问日志 / 设置（端口·主机名·开机自启·托盘行为）；
 * - 底部状态栏：服务状态 / 局域网地址 / UDP 端口 / 已配对设备数。
 *
 * 配对确认弹窗按里程碑计划在 M4 交付（当前 M1/M2 为自动允许模式）。
 */

/** 通用时间格式化：yyyy-MM-dd HH:mm */
private val timeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

/** 日志时间格式化：HH:mm:ss */
private val logTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

/** 右栏 Tab 索引常量 */
private const val TAB_DEVICE = 0
private const val TAB_LOG = 1
private const val TAB_SETTINGS = 2

@Composable
fun HostApp(
    store: ConfigStore,
    registry: DeviceRegistry,
    logger: AccessLogger,
    pairing: PairingSessionManager,
    updates: UpdateStore,
    version: String,
    udpPort: Int,
    trayAvailable: Boolean,
    restartServer: (oldPort: Int, newPort: Int) -> String?
) {
    // 订阅配置响应式流（文件夹/设备/设置的任何变更都会触发刷新）
    val config by store.configFlow.collectAsState()
    // 订阅访问日志流
    val logs by logger.entries.collectAsState()
    // 订阅当前配对会话（电视端发起配对时，主窗口顶部实时显示配对请求通知）
    val activeSession by pairing.activeSession.collectAsState()
    // 协程作用域：执行 store.update 等挂起操作
    val scope = rememberCoroutineScope()
    // 顶部瞬时消息（操作结果提示，4 秒自动消失）
    var uiMessage by remember { mutableStateOf<String?>(null) }

    fun showMessage(text: String) {
        uiMessage = text
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {

                // ============ 标题栏：软件名 + 版本 + 服务状态 ============
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "boxTV 授权后台",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "v$version",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "● 服务运行中 :${config.port}",
                        color = Color(0xFF2E7D32),
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                // ============ 配对确认区（有活动配对会话时显示；M4：允许/拒绝交互）============
                activeSession?.let { session ->
                    Spacer(modifier = Modifier.height(10.dp))
                    PairingRequestCard(session = session, pairing = pairing, logger = logger)
                }

                Spacer(modifier = Modifier.height(12.dp))

                // ============ 主区域：左栏文件夹 + 右栏 Tabs ============
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {

                    // ---- 左栏：授权文件夹管理（占 38%）----
                    Card(modifier = Modifier.weight(0.38f).fillMaxHeight()) {
                        FolderPanel(
                            folders = config.folders,
                            scope = scope,
                            store = store,
                            showMessage = ::showMessage
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // ---- 右栏：设备管理 / 访问日志 / 设置（占 62%）----
                    Card(modifier = Modifier.weight(0.62f).fillMaxHeight()) {
                        RightTabsPanel(
                            config = config,
                            logs = logs,
                            registry = registry,
                            updates = updates,
                            udpPort = udpPort,
                            trayAvailable = trayAvailable,
                            restartServer = restartServer,
                            scope = scope,
                            store = store,
                            showMessage = ::showMessage
                        )
                    }
                }

                // ============ 瞬时消息条 ============
                uiMessage?.let { msg ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // ============ 底部状态栏 ============
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val lanAddrs = NetworkUtils.localLanAddresses()
                    Text(
                        text = if (lanAddrs.isEmpty()) "局域网地址：（未检测到）"
                        else lanAddrs.joinToString("  ") { "$it:${config.port}" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "UDP 发现 :$udpPort   已配对设备 ${config.devices.size} 台",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ======================================================================
// 配对请求通知区（2026-11-08 配对机制重构：免配对码，电脑端直接允许/拒绝）
// ======================================================================

/**
 * 配对请求通知卡片：电视端发起配对时显示在主窗口顶部（细则 6.2 配对弹窗的窗口内形态）。
 * 展示请求设备名 + 请求发起时间 + 300 秒剩余倒计时；提供 [允许配对] [拒绝配对] 两个操作——
 * 点击「允许配对」后电视端下一次轮询即拿到 Token 直接完成配对（全程无需电视端输入任何内容）；
 * 点击「拒绝配对」则会话立即作废并记录拒绝标记；300 秒内无操作请求自动失效，卡片自动消失。
 */
@Composable
private fun PairingRequestCard(
    session: PairingSessionManager.PairingSession,
    pairing: PairingSessionManager,
    logger: AccessLogger
) {
    // 每 500ms 刷新一次剩余秒数（倒计时动画）
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(session.pairingId) {
        while (System.currentTimeMillis() < session.expiresAt) {
            delay(500)
            nowMs = System.currentTimeMillis()
        }
        nowMs = System.currentTimeMillis() // 触发最后一次重组（卡片随之消失）
    }

    val remainSec = ((session.expiresAt - nowMs) / 1000).coerceAtLeast(0)
    if (remainSec <= 0) return // 过期后卡片消失

    // 请求发起时间（HH:mm:ss）：展示在通知卡片上，供电脑端用户核对
    val createdAtText = remember(session.pairingId) {
        Instant.ofEpochMilli(session.createdAt).atZone(ZoneId.systemDefault()).format(logTimeFormatter)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = Color(0xFF0D47A1)
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // 通知标题：明确显示请求配对的设备名称
                Text(
                    text = "电视设备「${session.deviceName}」请求配对",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFBBDEFB)
                )
                // 通知信息：请求发起时间 + 300 秒有效等待时长
                Text(
                    text = "请求发起时间：$createdAtText（300 秒内有效，超时自动失效）",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF90CAF9)
                )
                // 状态副行：已允许后提示等待电视端完成配对；未允许则提示及时处理
                Text(
                    text = if (session.approved) "已允许，正在完成配对…"
                    else "请在 300 秒内处理",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF90CAF9)
                )
                // 允许/拒绝操作区（已允许后隐藏按钮，仅显示等待提示）
                if (!session.approved) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row {
                        // 允许配对：标记会话已允许，电视端下一次轮询即直接完成配对
                        Button(
                            onClick = {
                                if (pairing.approve(session.pairingId)) {
                                    logger.log(session.deviceName, "配对", "电脑端已允许本次配对请求")
                                }
                            }
                        ) {
                            Text("允许配对")
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        // 拒绝配对：作废会话并记录拒绝标记（电视端下次轮询收到 40902）
                        androidx.compose.material3.OutlinedButton(
                            onClick = {
                                pairing.reject(session.pairingId)
                                logger.log(session.deviceName, "配对", "电脑端已拒绝本次配对请求")
                            },
                            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFFFFCDD2)
                            )
                        ) {
                            Text("拒绝配对")
                        }
                    }
                }
            }
            // 剩余有效时长倒计时
            Text(
                text = "剩余 ${remainSec}s",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFFFFC107)
            )
        }
    }
}

// ======================================================================
// 左栏：授权文件夹管理
// ======================================================================

@Composable
private fun FolderPanel(
    folders: List<AuthorizedFolder>,
    scope: CoroutineScope,
    store: ConfigStore,
    showMessage: (String) -> Unit
) {
    // 当前选中的文件夹 id（移除/打开操作的目标）
    var selectedId by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {

        // ---- 标题 + 操作按钮 ----
        Text(
            text = "授权文件夹（${folders.size}）",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row {
            Button(onClick = {
                // 弹出系统目录选择对话框，选中即授权
                val chooser = JFileChooser().apply {
                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                    dialogTitle = "选择要授权给电视端的文件夹"
                }
                val result = chooser.showOpenDialog(null)
                if (result == JFileChooser.APPROVE_OPTION) {
                    val dir = chooser.selectedFile
                    if (dir != null && dir.isDirectory) {
                        scope.launch(Dispatchers.Default) {
                            // 按路径去重：重复授权同一目录直接提示
                            val pathNorm = dir.canonicalPath
                            if (store.peek().folders.any { File(it.path).canonicalPath == pathNorm }) {
                                showMessage("该文件夹已在授权列表中")
                            } else {
                                store.update {
                                    it.copy(folders = it.folders + AuthorizedFolder(name = dir.name, path = pathNorm))
                                }
                                showMessage("已授权文件夹「${dir.name}」")
                            }
                        }
                    }
                }
            }) { Text("添加") }
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = {
                val target = folders.firstOrNull { it.id == selectedId }
                if (target == null) {
                    showMessage("请先在列表中选择一个文件夹")
                } else {
                    scope.launch(Dispatchers.Default) {
                        store.update { it.copy(folders = it.folders.filter { f -> f.id != target.id }) }
                        selectedId = null
                        showMessage("已移除授权「${target.name}」，对应电视端将立即失去访问权")
                    }
                }
            }) { Text("移除") }
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = {
                val target = folders.firstOrNull { it.id == selectedId }
                if (target == null) {
                    showMessage("请先在列表中选择一个文件夹")
                } else {
                    // 调用系统资源管理器打开目录（用于核对授权内容）
                    runCatching { java.awt.Desktop.getDesktop().open(File(target.path)) }
                        .onFailure { showMessage("打开失败：${it.message}") }
                }
            }) { Text("打开") }
        }

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(4.dp))

        // ---- 文件夹列表（点击选中）----
        if (folders.isEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "暂无授权文件夹。\n点击「添加」选择电脑中的文件夹，电视端即可访问其中的视频。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(folders, key = { it.id }) { folder ->
                    val selected = folder.id == selectedId
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer
                                else Color.Transparent
                            )
                            .clickable { selectedId = folder.id }
                            .padding(vertical = 8.dp, horizontal = 4.dp)
                    ) {
                        Text(
                            text = folder.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                        Text(
                            text = folder.path,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                }
            }
        }
    }
}

// ======================================================================
// 右栏：设备管理 / 访问日志 / 设置 三个 Tab
// ======================================================================

@Composable
private fun RightTabsPanel(
    config: HostConfig,
    logs: List<AccessLogger.AccessLogEntry>,
    registry: DeviceRegistry,
    updates: UpdateStore,
    udpPort: Int,
    trayAvailable: Boolean,
    restartServer: (oldPort: Int, newPort: Int) -> String?,
    scope: CoroutineScope,
    store: ConfigStore,
    showMessage: (String) -> Unit
) {
    var currentTab by remember { mutableStateOf(TAB_DEVICE) }
    val tabNames = listOf("设备管理", "访问日志", "设置")

    Column(modifier = Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = currentTab) {
            tabNames.forEachIndexed { index, name ->
                Tab(
                    selected = currentTab == index,
                    onClick = { currentTab = index },
                    text = { Text(name) }
                )
            }
        }
        when (currentTab) {
            TAB_DEVICE -> DevicePanel(config, registry, scope, showMessage)
            TAB_LOG -> LogPanel(logs)
            TAB_SETTINGS -> SettingsPanel(
                config = config,
                udpPort = udpPort,
                trayAvailable = trayAvailable,
                restartServer = restartServer,
                scope = scope,
                store = store,
                updates = updates,
                showMessage = showMessage
            )
        }
    }
}

// ======================================================================
// Tab1：设备管理
// ======================================================================

@Composable
private fun DevicePanel(
    config: HostConfig,
    registry: DeviceRegistry,
    scope: CoroutineScope,
    showMessage: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
        Text(
            text = "已配对设备（${config.devices.size}）",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "「断开授权」会删除该设备的访问令牌，电视端下一次请求将立即失效。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (config.devices.isEmpty()) {
            Text(
                text = "暂无已配对设备。\n电视端首次连接时会在此发起配对。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            // 表头
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text("设备", modifier = Modifier.weight(0.9f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                Text("配对时间", modifier = Modifier.weight(1.0f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                Text("最近连接", modifier = Modifier.weight(1.0f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                Text("操作", modifier = Modifier.weight(0.6f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            }
            HorizontalDivider()
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(config.devices, key = { it.deviceUuid }) { device ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(0.9f)) {
                            Text(device.name, style = MaterialTheme.typography.bodyLarge)
                            if (device.model.isNotBlank()) {
                                Text(
                                    device.model,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Text(
                            timeFormatter.format(Instant.ofEpochMilli(device.pairedAt)),
                            modifier = Modifier.weight(1.0f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            if (device.lastConnectedAt > 0) timeFormatter.format(Instant.ofEpochMilli(device.lastConnectedAt))
                            else "—",
                            modifier = Modifier.weight(1.0f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        TextButton(
                            onClick = {
                                scope.launch(Dispatchers.Default) {
                                    val removed = registry.removeDevice(device.deviceUuid)
                                    showMessage(
                                        if (removed) "已断开设备「${device.name}」的授权"
                                        else "设备不存在或已被移除"
                                    )
                                }
                            }
                        ) { Text("断开授权", color = MaterialTheme.colorScheme.error) }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                }
            }
        }
    }
}

// ======================================================================
// Tab2：访问日志
// ======================================================================

@Composable
private fun LogPanel(entries: List<AccessLogger.AccessLogEntry>) {
    Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
        Text(
            text = "访问日志（最近 ${entries.size} 条，内存保留 500 条）",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (entries.isEmpty()) {
            Text(
                text = "暂无访问记录。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            // entries 最新在前，直接按序展示
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(entries.size) { index ->
                    val e = entries[index]
                    Text(
                        text = "${logTimeFormatter.format(Instant.ofEpochMilli(e.timestamp))}  [${e.device}] ${e.action} · ${e.detail}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                }
            }
        }
    }
}

// ======================================================================
// Tab3：设置
// ======================================================================

@Composable
private fun SettingsPanel(
    config: HostConfig,
    udpPort: Int,
    trayAvailable: Boolean,
    restartServer: (oldPort: Int, newPort: Int) -> String?,
    scope: CoroutineScope,
    store: ConfigStore,
    updates: UpdateStore,
    showMessage: (String) -> Unit
) {
    // ---- 端口输入（配置外部变化时同步回显）----
    var portText by remember(config.port) { mutableStateOf(config.port.toString()) }
    // ---- 主机显示名输入 ----
    var hostNameText by remember(config.hostName) { mutableStateOf(config.hostName) }
    // ---- 开机自启状态（进入界面时读取注册表实际状态）----
    var autoStart by remember { mutableStateOf(AutoStartManager.isEnabled()) }
    // ---- 电视端应用更新：待发布 APK（选择文件并解析版本后弹更新说明对话框）----
    var pendingApk by remember { mutableStateOf<File?>(null) }
    var pendingInfo by remember { mutableStateOf<ApkParser.ApkInfo?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
    ) {
        // ============ 服务设置 ============
        Text("服务", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = portText,
                onValueChange = { portText = it },
                label = { Text("HTTP 服务端口") },
                singleLine = true,
                modifier = Modifier.width(180.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Button(onClick = {
                val newPort = portText.trim().toIntOrNull()
                when {
                    newPort == null || newPort !in 1024..65535 ->
                        showMessage("端口需为 1024 - 65535 之间的数字")
                    newPort == udpPort ->
                        showMessage("HTTP 端口不能与 UDP 发现端口 $udpPort 相同")
                    newPort == config.port ->
                        showMessage("端口未发生变化")
                    else -> {
                        val oldPort = config.port
                        // 热重启在后台线程执行（stop 等待旧连接关闭约 3 秒）
                        scope.launch(Dispatchers.Default) {
                            val error = restartServer(oldPort, newPort)
                            if (error == null) {
                                store.update { it.copy(port = newPort) }
                                showMessage("端口已改为 $newPort 并即时生效，电视端需更新连接地址")
                            } else {
                                showMessage(error)
                            }
                        }
                    }
                }
            }) { Text("应用端口") }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = hostNameText,
                onValueChange = { hostNameText = it },
                label = { Text("主机显示名（电视端发现列表中展示）") },
                singleLine = true,
                modifier = Modifier.width(300.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Button(onClick = {
                val name = hostNameText.trim()
                if (name.isEmpty()) {
                    showMessage("主机名不能为空")
                } else {
                    scope.launch(Dispatchers.Default) {
                        store.update { it.copy(hostName = name) }
                        showMessage("主机名已保存，UDP 发现应答即时生效")
                    }
                }
            }) { Text("保存名称") }
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))

        // ============ 启动与窗口行为 ============
        Text("启动与窗口", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = autoStart,
                onCheckedChange = { checked ->
                    val ok = if (checked) AutoStartManager.enable() else AutoStartManager.disable()
                    if (ok) {
                        autoStart = checked
                        showMessage(if (checked) "已开启开机自启（当前用户注册表）" else "已关闭开机自启")
                    } else {
                        showMessage("注册表操作失败，请检查系统权限")
                    }
                }
            )
            Text("开机自启（写入当前用户注册表 Run 键）")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = config.closeToTray,
                enabled = trayAvailable,
                onCheckedChange = { checked ->
                    scope.launch(Dispatchers.Default) {
                        store.update { it.copy(closeToTray = checked) }
                    }
                }
            )
            Text(
                text = if (trayAvailable) "关闭窗口时最小化到系统托盘"
                else "关闭窗口时最小化到系统托盘（当前系统不支持托盘）",
                color = if (trayAvailable) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))

        // ============ 电视端应用更新（2026-11-07 局域网更新功能）============
        // 上传/发布电视端 APK 新版本：电视端「检查更新」即从此处发布的
        // 安装包发现并下载新版本（全程局域网直连，无公网参与）。
        val publishedMeta by updates.meta.collectAsState()
        Text("电视端应用更新", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        val meta = publishedMeta
        if (meta == null) {
            Text(
                text = "当前未发布新版本。上传 APK 后，电视端「设置 → 检查更新」即可发现并安装。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                text = "已发布版本：v${meta.versionName}（versionCode ${meta.versionCode}）· " +
                    "${"%.1f".format(meta.size / 1024.0 / 1024.0)}MB · " +
                    "发布于 ${timeFormatter.format(Instant.ofEpochMilli(meta.publishedAt))}",
                style = MaterialTheme.typography.bodyMedium
            )
            if (meta.changelog.isNotBlank()) {
                Text(
                    text = "更新说明：${meta.changelog}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                // 弹出系统文件选择框（仅 .apk）：选中后先解析版本信息再弹说明输入
                val chooser = JFileChooser().apply {
                    dialogTitle = "选择电视端应用新版本安装包（.apk）"
                    fileSelectionMode = JFileChooser.FILES_ONLY
                    fileFilter = javax.swing.filechooser.FileNameExtensionFilter("APK 安装包 (*.apk)", "apk")
                }
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    val picked = chooser.selectedFile ?: return@Button
                    scope.launch(Dispatchers.Default) {
                        val info = ApkParser.parse(picked)
                        if (info == null) {
                            showMessage("无法解析该 APK 的版本信息，文件可能已损坏")
                        } else {
                            pendingInfo = info
                            pendingApk = picked
                        }
                    }
                }
            }) { Text("上传新版本") }
            Spacer(modifier = Modifier.width(10.dp))
            if (meta != null) {
                TextButton(onClick = {
                    scope.launch(Dispatchers.Default) {
                        updates.clear()
                        showMessage("已取消发布，电视端将检查不到新版本")
                    }
                }) { Text("取消发布") }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))

        // ============ Windows 防火墙（细则风险预案：引导放行 8964/8965）============
        // 电视端发现(UDP)与访问(HTTP)依赖入站放行；未放行时提供一键提权添加规则
        Text("Windows 防火墙", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        // 防火墙规则状态（null=检测中；端口变化时重新检测）
        var fwAllowed by remember(config.port) { mutableStateOf<Boolean?>(null) }
        LaunchedEffect(config.port) {
            fwAllowed = null
            fwAllowed = withContext(Dispatchers.Default) {
                FirewallManager(config.port, udpPort).isAllowed()
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (fwAllowed) {
                null -> Text("正在检测防火墙放行状态…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                true -> Text("已放行（入站规则 boxTV Host）", color = Color(0xFF2E7D32))
                false -> Text(
                    "未放行：电视端可能无法发现本机或访问视频（端口 ${config.port}/TCP、$udpPort/UDP）",
                    color = Color(0xFFE65100)
                )
            }
        }
        if (fwAllowed == false) {
            Spacer(modifier = Modifier.height(6.dp))
            Button(
                onClick = {
                    scope.launch(Dispatchers.Default) {
                        val launched = FirewallManager(config.port, udpPort).requestAllow()
                        // 重新检测（用户在 UAC 中确认后规则生效；取消则仍未放行）
                        val allowed = FirewallManager(config.port, udpPort).isAllowed()
                        fwAllowed = allowed
                        showMessage(
                            when {
                                allowed -> "防火墙已放行（TCP ${config.port} / UDP $udpPort）"
                                launched -> "未见放行规则：可能已在 UAC 弹窗中取消，可重试或手动放行"
                                else -> "提权请求发起失败，请参照使用说明手动放行防火墙"
                            }
                        )
                    }
                }
            ) { Text("一键放行（弹出系统授权确认）") }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "若一键放行失败，可手动执行：netsh advfirewall firewall add rule " +
                "name=\"boxTV Host\" dir=in action=allow protocol=TCP localport=${config.port}（UDP 同理）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "提示：端口修改即时生效；主机名即时生效；文件夹授权在左栏管理。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    // ---- 发布确认对话框：展示解析到的版本信息，输入更新说明后确认发布 ----
    val apk = pendingApk
    val info = pendingInfo
    if (apk != null && info != null) {
        var changelogText by remember(apk.absolutePath) { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingApk = null; pendingInfo = null },
            title = { Text("发布电视端新版本") },
            text = {
                Column {
                    Text(
                        text = "${apk.name}\n" +
                            "版本：v${info.versionName}（versionCode ${info.versionCode}）\n" +
                            "包名：${info.packageName}\n" +
                            "大小：${"%.1f".format(apk.length() / 1024.0 / 1024.0)}MB",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = changelogText,
                        onValueChange = { changelogText = it },
                        label = { Text("更新说明（展示在电视端更新弹窗）") },
                        minLines = 3,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val file = apk
                    pendingApk = null
                    pendingInfo = null
                    scope.launch(Dispatchers.Default) {
                        updates.publish(file, changelogText)
                            .onSuccess { showMessage("已发布 v${it.versionName}，电视端「检查更新」即可获取") }
                            .onFailure { showMessage("发布失败：${it.message}") }
                    }
                }) { Text("确认发布") }
            },
            dismissButton = {
                TextButton(onClick = { pendingApk = null; pendingInfo = null }) { Text("取消") }
            }
        )
    }
}
