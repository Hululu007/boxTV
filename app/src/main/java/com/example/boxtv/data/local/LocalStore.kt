package com.example.boxtv.data.local

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.example.boxtv.model.PairedHost
import com.example.boxtv.model.PlaybackProgress

/**
 * 本地持久化模块（data/local 层）
 *
 * 对应细则 5.3 节数据库设计（paired_host / playback_progress 两张表）：
 * 使用 JSON 文件持久化（filesDir/boxtv_data.json），写入采用
 * 「临时文件 + 原子替换」，避免写入中途崩溃导致数据损坏。
 *
 * 说明：细则原定 Room 数据库，因 AGP 9.2.1 内置 Kotlin 与 KSP 构建工具
 * 不兼容（详见 gradle.properties 注释）改用 JSON 文件方案，数据语义一致。
 */

/** 持久化根结构 */
@Serializable
data class TvData(
    val deviceUuid: String = UUID.randomUUID().toString(),
    val hosts: List<PairedHost> = emptyList(),
    val progress: List<PlaybackProgress> = emptyList()
)

class LocalStore(context: Context) {

    private val file = File(context.filesDir, "boxtv_data.json")
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /**
     * 设备 UUID 独立持久层（SharedPreferences）。
     *
     * 设计目的（2026-11-06 修复「同一电视重复配对产生多个 ID」）：
     * boxtv_data.json 损坏/重建时，deviceUuid 必须保持不变——否则电脑端
     * 会把同一台电视识别为新设备，设备列表出现多个 ID。
     * UUID 写入 SharedPreferences（独立于主数据文件），主数据重建时继承。
     */
    private val prefs = context.getSharedPreferences("boxtv_device", Context.MODE_PRIVATE)

    private companion object {
        /** SharedPreferences 中设备 UUID 的键 */
        const val KEY_DEVICE_UUID = "device_uuid"

        /**
         * 播放进度记录上限（2026-11-09 审查 W4 修复）。
         * 进度列表此前无上限增长：每看一部视频新增一条，播放中每 5 秒还要
         * 全量重写整个 JSON——低端电视 eMMC 写放大、文件持续膨胀。
         * 封顶 500 条，超出时按 updatedAt 淘汰最旧记录（历史页展示的
         * 恰是最近观看，被淘汰的即最不常用条目，语义无损）。
         */
        const val MAX_PROGRESS_ENTRIES = 500
    }

    @Volatile
    private var data: TvData = TvData()

    /** 已配对主机响应式流（主页「我的主机」行订阅刷新） */
    private val _hosts = MutableStateFlow<List<PairedHost>>(emptyList())
    val hosts: StateFlow<List<PairedHost>> = _hosts

    /** 启动时加载；首次运行生成默认数据（含设备 UUID）。
     *  UUID 策略：优先继承 SharedPreferences 中已持久化的 UUID；
     *  主数据损坏时先备份再回退默认，但 UUID 仍保持稳定。 */
    suspend fun load() {
        mutex.withLock {
            var loaded: TvData? = null
            if (file.exists()) {
                try {
                    loaded = json.decodeFromString(TvData.serializer(), file.readText())
                } catch (e: Exception) {
                    // 备份损坏文件，避免覆盖丢失配对数据
                    val bak = File(file.parentFile, file.name + ".corrupt-" + System.currentTimeMillis())
                    runCatching { file.copyTo(bak, overwrite = true) }
                    println("[boxTV] data file corrupted, backup to ${bak.name}: ${e.message}")
                    loaded = null
                }
            }
            data = loaded ?: TvData()

            // UUID 稳定性保证：SharedPreferences 优先（数据文件重建时身份不变）
            val stableUuid = prefs.getString(KEY_DEVICE_UUID, null)
            if (stableUuid != null && stableUuid != data.deviceUuid) {
                // 主数据重建/丢失：继承历史 UUID，保持电视端身份唯一
                data = data.copy(deviceUuid = stableUuid)
            }
            if (stableUuid == null) {
                prefs.edit().putString(KEY_DEVICE_UUID, data.deviceUuid).apply()
            }

            saveLocked()
            _hosts.value = data.hosts
        }
    }

    /** 电视端自身唯一标识（配对请求上报给电脑端） */
    val deviceUuid: String get() = data.deviceUuid

    /** 添加/覆盖已配对主机（配对成功后调用，按 hostDeviceId 去重） */
    suspend fun addHost(host: PairedHost) {
        mutex.withLock {
            data = data.copy(hosts = data.hosts.filter { it.hostDeviceId != host.hostDeviceId } + host)
            saveLocked()
            _hosts.value = data.hosts
        }
    }

    /** 移除已配对主机 */
    suspend fun removeHost(hostDeviceId: String) {
        mutex.withLock {
            data = data.copy(
                hosts = data.hosts.filter { it.hostDeviceId != hostDeviceId },
                progress = data.progress.filter { it.hostDeviceId != hostDeviceId }
            )
            saveLocked()
            _hosts.value = data.hosts
        }
    }

    /** 刷新主机最近连接时间（鉴权成功后调用） */
    suspend fun touchHost(hostDeviceId: String) {
        mutex.withLock {
            data = data.copy(
                hosts = data.hosts.map {
                    if (it.hostDeviceId == hostDeviceId) it.copy(lastConnectedAt = System.currentTimeMillis())
                    else it
                }
            )
            saveLocked()
            _hosts.value = data.hosts
        }
    }

    /** 保存播放进度（每 5 秒节流 + 退出播放页时调用） */
    suspend fun saveProgress(progress: PlaybackProgress) {
        mutex.withLock {
            val key = progress.hostDeviceId to progress.videoPath
            // W4 修复：按「其余记录（去掉本条）+ 新记录」重排，再按记录时间从新到旧
            // 排序后截断到上限（淘汰最旧记录），防止列表无上限膨胀
            val others = data.progress.filter { (it.hostDeviceId to it.videoPath) != key }
            val merged = (others + progress)
                .sortedByDescending { it.updatedAt }
                .take(MAX_PROGRESS_ENTRIES)
            data = data.copy(progress = merged)
            saveLocked()
        }
    }

    /** 查询播放进度（断点续播） */
    fun getProgress(hostDeviceId: String, videoPath: String): PlaybackProgress? {
        return data.progress.firstOrNull {
            it.hostDeviceId == hostDeviceId && it.videoPath == videoPath
        }
    }

    /**
     * 读取全部播放记录快照（2026-11-08 历史记录页新增）。
     * 返回按记录时间从新到旧排序的列表副本，供历史页遍历校验使用；
     * 不对外发流——历史页采用「进入页面 / 手动刷新」时按需拉取的模式，
     * 避免播放中每 5 秒落盘触发历史页无意义的重载。
     */
    fun getAllProgress(): List<PlaybackProgress> =
        data.progress.sortedByDescending { it.updatedAt }

    /** 调用方必须已持有 [mutex]；原子写盘 */
    private fun saveLocked() {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(TvData.serializer(), data))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
