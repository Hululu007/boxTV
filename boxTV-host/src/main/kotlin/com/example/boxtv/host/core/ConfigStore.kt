package com.example.boxtv.host.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * 配置存储模块（core 层）
 *
 * 负责 HostConfig 的加载、修改与持久化（写入 %APPDATA%/boxTV/config.json）。
 * 设计要点：
 * 1. 所有磁盘写入使用「临时文件 + 原子移动」，避免写入中途崩溃导致配置损坏；
 * 2. 高频操作（如记录 lastConnectedAt）只改内存并打脏标记，由 [startAutoFlush]
 *    定时批量落盘，避免每次浏览都写文件；
 * 3. 低频结构性修改（授权文件夹增删、设备配对/撤销）调用 [update] 立即落盘。
 */
class ConfigStore(private val file: Path) {

    private val mutex = Mutex() // 保护内存配置与磁盘写入的互斥锁
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /** 内存中的当前配置（读取请使用 [peek]，修改请走 [update] 或 [touchDeviceLastSeen]） */
    @Volatile
    var config: HostConfig = HostConfig()
        private set

    /** 配置响应式流：结构性变更（文件夹/设备/设置）后发布，UI 订阅实时刷新 */
    private val _configFlow = MutableStateFlow(HostConfig())
    val configFlow: StateFlow<HostConfig> = _configFlow

    /** 内存改动未落盘标记（配合定时自动落盘使用） */
    @Volatile
    private var dirty = false

    /** 读取内存中的当前配置快照 */
    fun peek(): HostConfig = config

    /**
     * 启动时加载配置；文件不存在或解析失败时，创建默认配置并落盘。
     * 默认主机名取电脑的计算机名。
     * 兼容性：容忍 UTF-8 BOM 头（Windows 记事本/PowerShell 编辑配置文件时可能带 BOM）。
     * 加固：解析失败（损坏）时先备份损坏文件再回退默认，避免配对数据被静默覆盖。
     */
    suspend fun load() {
        mutex.withLock {
            var loaded: HostConfig? = null
            if (Files.exists(file)) {
                try {
                    val text = Files.readString(file)
                        .removePrefix("\uFEFF") // 去除 UTF-8 BOM，避免解析失败
                    loaded = json.decodeFromString(HostConfig.serializer(), text)
                } catch (e: Exception) {
                    // 备份损坏配置文件（可人工恢复配对设备/授权数据）
                    val bak = file.resolveSibling("config.corrupt-" + System.currentTimeMillis() + ".json")
                    runCatching { Files.copy(file, bak) }
                    println("[boxTV] config corrupted, backup to ${bak.fileName}: ${e.message}")
                    loaded = null
                }
            }
            config = loaded ?: HostConfig(
                hostName = java.net.InetAddress.getLocalHost().hostName
            )
            saveLocked() // 确保首次运行立即生成配置文件
            _configFlow.value = config
        }
    }

    /**
     * 结构性修改：在互斥锁内修改配置并立即持久化。
     * 用于：添加/移除授权文件夹、设备配对/撤销、修改端口等。
     */
    suspend fun update(block: (HostConfig) -> HostConfig): HostConfig {
        return mutex.withLock {
            config = block(config)
            saveLocked()
            _configFlow.value = config
            config
        }
    }

    /**
     * 高频操作专用：仅更新内存中设备的 lastConnectedAt 并打脏标记，不立即写盘。
     * 由 [startAutoFlush] 定时批量落盘。
     *
     * 2026-11-09 并发加固：本方法在「每个携带 Token 的请求」上都会被调用
     * （DeviceRegistry.findByToken），必须与结构性修改 [update] 持同一把互斥锁。
     * 旧实现无锁读改写整个 config 对象，与 update() 并发时会把结构性变更
     * （授权文件夹/设备记录）整体回退，导致电视端偶发 40400「授权文件夹不存在」。
     */
    suspend fun touchDeviceLastSeen(deviceUuid: String) {
        mutex.withLock {
            // 浅拷贝 devices 列表，替换目标设备的 lastConnectedAt
            val devices = config.devices.map {
                if (it.deviceUuid == deviceUuid && it.lastConnectedAt != now()) {
                    it.copy(lastConnectedAt = now())
                } else it
            }
            if (devices != config.devices) {
                config = config.copy(devices = devices)
                dirty = true
            }
        }
    }

    /** 启动定时落盘协程：每 30 秒检查一次脏标记，有脏数据则写盘。 */
    fun startAutoFlush(scope: CoroutineScope) {
        scope.launch {
            while (true) {
                delay(30_000)
                if (dirty) {
                    mutex.withLock {
                        if (dirty) {
                            saveLocked()
                            dirty = false
                        }
                    }
                }
            }
        }
    }

    /** 调用方必须已持有 [mutex]；执行实际的原子写盘。 */
    private fun saveLocked() {
        Files.createDirectories(file.parent)
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.writeString(tmp, json.encodeToString(HostConfig.serializer(), config))
        Files.move(
            tmp, file,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE
        )
    }

    private fun now(): Long = System.currentTimeMillis()
}

/**
 * 网络工具模块（core 层）
 *
 * 枚举本机所有非回环 IPv4 局域网地址，供 UI 状态栏展示与 UDP 发现应答使用。
 */
object NetworkUtils {

    /**
     * 获取本机所有可用的局域网 IPv4 地址字符串列表。
     * 过滤条件：接口已启用、非回环、地址为 IPv4 且是站点本地地址（192.168.x.x 等）。
     */
    fun localLanAddresses(): List<String> {
        return runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filter { !it.isLoopbackAddress && it is java.net.Inet4Address && it.isSiteLocalAddress }
                .map { (it as java.net.Inet4Address).hostAddress }
                .toList()
        }.getOrDefault(emptyList())
    }
}
