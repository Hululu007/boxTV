package com.example.boxtv.host

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.example.boxtv.host.core.AccessLogger
import com.example.boxtv.host.core.ConfigStore
import com.example.boxtv.host.core.DeviceRegistry
import com.example.boxtv.host.core.PairingSessionManager
import com.example.boxtv.host.core.UpdateStore
import com.example.boxtv.host.discovery.DiscoveryResponder
import com.example.boxtv.host.server.HostServer
import com.example.boxtv.host.server.ThumbGenerator
import com.example.boxtv.host.ui.HostApp
import com.example.boxtv.host.ui.TrayManager
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * boxTV Host 程序入口
 *
 * 启动流程（细则 6.4 节「启动即服务」）：
 * 1. 初始化 ConfigStore 并加载 %APPDATA%/boxTV/config.json；
 * 2. 启动 Ktor HTTP 服务（8964，端口可配置）与 UDP 发现应答器（8965）；
 * 3. 打开 Compose 桌面窗口（左栏授权管理 / 右栏设备·日志·设置）；
 * 4. 关闭窗口时按「关闭行为」设置处理：
 *    - closeToTray=true 且系统支持托盘：窗口隐藏，托盘常驻（细则 6.4）；
 *    - 否则：停止 UDP/HTTP/协程后退出进程。
 */
fun main() {
    // ---- 1. 初始化数据目录与配置存储 ----
    // Windows 优先使用 %APPDATA%，无该环境变量时回退到用户主目录下的 .boxTV
    val appDataRoot = System.getenv("APPDATA") ?: System.getProperty("user.home")
    val dataDir: Path = Path.of(appDataRoot, "boxTV")
    val configFile = dataDir.resolve("config.json")

    val store = ConfigStore(configFile)
    val logger = AccessLogger()
    val registry = DeviceRegistry(store)
    val pairing = PairingSessionManager()
    // M4 缩略图生成器：缓存目录 %APPDATA%/boxTV/thumbCache（细则 4.4 节）
    val thumbs = ThumbGenerator(dataDir.resolve("thumbCache").toFile())
    // 2026-11-07 局域网更新：电视端 APK 发布管理（%APPDATA%/boxTV/update/）
    val updates = UpdateStore(dataDir.toFile())

    // 阻塞式加载配置（首次运行会生成默认配置文件）
    runBlocking {
        store.load()
        updates.load()
    }

    // 全局协程作用域：承载配置自动落盘等后台任务
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    store.startAutoFlush(scope)

    // ---- 2. 启动 HTTP 服务 ----
    val server = HostServer(store, registry, pairing, logger, thumbs, updates)
    try {
        server.start(store.peek().port)
    } catch (e: Exception) {
        println("[boxTV] HTTP service start failed on port ${store.peek().port}: ${e.message}")
        exitProcess(1)
    }
    val udpPort = 8965

    // ---- 3. 启动 UDP 发现应答器（固定 8965，细则 4.1 节）----
    val discovery = DiscoveryResponder(udpPort) {
        DiscoveryResponder.DiscoveryInfo(
            hostName = store.peek().hostName.ifBlank { "boxTV Host" },
            httpPort = store.peek().port,
            hostDeviceId = store.peek().hostDeviceId,
            version = HostServer.VERSION
        )
    }
    try {
        discovery.start()
    } catch (e: Exception) {
        println("[boxTV] UDP discovery start failed on port $udpPort: ${e.message}")
        server.stop()
        exitProcess(1)
    }

    logger.log("系统", "启动", "HTTP 服务 :${store.peek().port}，UDP 发现 :$udpPort 已就绪")

    // ---- 4. 打开桌面窗口（阻塞主线程直到应用退出）----
    application {
        val appScope = this
        // 主窗口引用（onCloseRequest 由 AWT 线程触发，需通过此引用控制隐藏/退出）
        var mainWindow by remember { mutableStateOf<java.awt.Window?>(null) }
        // 托盘是否安装成功（不支持托盘的系统禁用「最小化到托盘」行为）
        var trayInstalled by remember { mutableStateOf(false) }

        // 完整退出流程：停止各组件后结束 Compose 应用循环
        fun exitAll() {
            logger.log("系统", "关闭", "boxTV Host 正在退出")
            discovery.stop()
            server.stop()
            scope.cancel()
            appScope.exitApplication()
        }

        Window(
            onCloseRequest = {
                // 细则 6.4：关闭窗口默认最小化到托盘（可在设置 Tab 修改）
                if (store.peek().closeToTray && trayInstalled) {
                    mainWindow?.isVisible = false
                } else {
                    exitAll()
                }
            },
            title = "boxTV 授权后台",
            state = rememberWindowState(width = 1040.dp, height = 680.dp)
        ) {
            LaunchedEffect(Unit) {
                mainWindow = window
                trayInstalled = TrayManager.install(
                    window = window,
                    showWindow = {
                        window.isVisible = true
                        window.toFront()
                    },
                    exitApp = { exitAll() }
                )
            }
            HostApp(
                store = store,
                registry = registry,
                logger = logger,
                pairing = pairing,
                updates = updates,
                version = HostServer.VERSION,
                udpPort = udpPort,
                trayAvailable = trayInstalled,
                restartServer = { oldPort, newPort ->
                    try {
                        server.restart(newPort)
                        null // 成功返回 null
                    } catch (e: Exception) {
                        // 新端口绑定失败：回滚到旧端口并返回错误信息
                        try {
                            server.restart(oldPort)
                        } catch (_: Exception) {
                        }
                        "新端口 $newPort 启动失败，已回滚：${e.message}"
                    }
                }
            )
        }
    }

    // 窗口关闭、exitApplication 返回后兜底退出
    exitProcess(0)
}
