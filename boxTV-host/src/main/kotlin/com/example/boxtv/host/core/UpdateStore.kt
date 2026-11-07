package com.example.boxtv.host.core

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 电视端应用更新发布管理（core 层）—— 2026-11-07 局域网更新功能
 *
 * 职责：
 * - 管理「已发布的电视端新版本安装包」：%APPDATA%/boxTV/update/update.apk + meta.json；
 * - publish()：选择 APK → 解析版本信息（ApkParser）→ 计算 MD5 →
 *   原子替换安装包与元数据（同一时刻最多发布一个最新版本，电视端只需比较
 *   versionCode 即可判断是否有更新）；
 * - clear()：取消发布（删除安装包与元数据）；
 * - meta 流供主窗口 UI 实时展示当前发布状态。
 *
 * 持久化采用「临时文件 + 原子替换」，与 ConfigStore 的策略一致，
 * 避免写入中途异常导致元数据损坏。
 */

/** 已发布版本的元数据（meta.json 内容；电视端 /api/update/check 响应的数据源） */
@Serializable
data class UpdateMeta(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val changelog: String = "",
    val size: Long,
    val md5: String,
    val publishedAt: Long = System.currentTimeMillis()
)

class UpdateStore(dataDir: File) {

    /** 安装包与元数据落盘目录： %APPDATA%/boxTV/update/ */
    private val dir = File(dataDir, "update").apply { mkdirs() }
    private val apkFile = File(dir, "update.apk")
    private val metaFile = File(dir, "meta.json")
    private val tmpApk = File(dir, "update.apk.tmp")
    private val tmpMeta = File(dir, "meta.json.tmp")

    private val mutex = Mutex()
    // encodeDefaults=true：publishedAt 等带默认值的字段必须落盘，
    // 否则重启加载时会被重新赋当前时间导致元数据不一致
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    private val _meta = MutableStateFlow<UpdateMeta?>(null)

    /** 当前已发布版本（null = 未发布；主窗口订阅实时刷新） */
    val meta: StateFlow<UpdateMeta?> = _meta

    /** APK 下载实体文件（HostServer /api/update/download 直接下发） */
    val apk: File get() = apkFile

    /** 启动时加载：元数据存在且安装包在位才视为有效发布 */
    suspend fun load() {
        mutex.withLock {
            _meta.value = readMeta()
        }
    }

    /**
     * 发布新版本：解析版本信息 + 校验 + 计算 MD5 + 原子落盘。
     * @param source 用户选择的 APK 文件（任意位置）
     * @param changelog 更新说明（展示在电视端更新弹窗中）
     * @return 成功返回元数据；失败返回中文错误原因（UI 直接展示）
     */
    suspend fun publish(source: File, changelog: String): Result<UpdateMeta> = mutex.withLock {
        when {
            !source.exists() || !source.isFile -> return Result.failure(Exception("所选文件不存在"))
            source.length() == 0L -> return Result.failure(Exception("所选文件为空"))
            !source.extension.equals("apk", ignoreCase = true) ->
                return Result.failure(Exception("请选择 .apk 安装包文件"))
        }
        // 解析版本信息（非 APK / 清单异常 → 拒绝发布）
        val info = ApkParser.parse(source)
            ?: return Result.failure(Exception("无法解析该 APK 的版本信息，文件可能已损坏"))

        return runCatching {
            // 先复制到临时文件，再原子替换（避免下载请求拿到写了一半的文件）
            source.copyTo(tmpApk, overwrite = true)
            if (!tmpApk.renameTo(apkFile)) {
                apkFile.delete()
                check(tmpApk.renameTo(apkFile)) { "安装包落盘失败" }
            }
            val meta = UpdateMeta(
                packageName = info.packageName,
                versionCode = info.versionCode,
                versionName = info.versionName,
                changelog = changelog.trim(),
                size = apkFile.length(),
                md5 = md5Of(apkFile)
            )
            tmpMeta.writeText(json.encodeToString(UpdateMeta.serializer(), meta))
            if (!tmpMeta.renameTo(metaFile)) {
                metaFile.delete()
                tmpMeta.renameTo(metaFile)
            }
            _meta.value = meta
            meta
        }
    }

    /** 取消发布：删除安装包与元数据，电视端检查更新将返回「无可用更新」 */
    suspend fun clear() = mutex.withLock {
        runCatching { apkFile.delete() }
        runCatching { metaFile.delete() }
        _meta.value = null
    }

    /** 读取并校验元数据（安装包缺失视为未发布） */
    private fun readMeta(): UpdateMeta? {
        if (!metaFile.exists() || !apkFile.exists()) return null
        return runCatching {
            val m = json.decodeFromString(UpdateMeta.serializer(), metaFile.readText())
            // 安装包被手工删除/变动时元数据失效
            if (m.size != apkFile.length()) null else m
        }.getOrNull()
    }

    companion object {
        /** 计算文件 MD5（流式读取，安装包约 20MB 无内存压力） */
        fun md5Of(file: File): String {
            val digest = MessageDigest.getInstance("MD5")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
