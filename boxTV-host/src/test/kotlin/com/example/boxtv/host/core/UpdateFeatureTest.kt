package com.example.boxtv.host.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * 局域网更新功能单元测试（2026-11-07）
 *
 * - ApkParser：真实交付 APK 的版本解析（versionCode/versionName/包名）、
 *   非 APK 文件与缺清单 APK 的容错；
 * - UpdateStore：发布（复制+MD5+元数据）、重复发布覆盖、取消发布生命周期。
 *
 * 测试 APK 来源：根构建 deliverables/ 下的交付 APK（boxTV-1.0.0.apk 等）；
 * 可用系统属性 boxtv.test.apk 显式指定。找不到时用 assumeTrue 跳过。
 */
class UpdateFeatureTest {

    /** 查找可用测试 APK：系统属性优先，其次 deliverables/ 交付产物 */
    private fun findTestApk(): File? {
        System.getProperty("boxtv.test.apk")?.let { p ->
            val f = File(p)
            if (f.isFile) return f
        }
        val deliverables = File("..", "deliverables")
        deliverables.listFiles { f -> f.extension.equals("apk", true) }
            ?.maxByOrNull { it.lastModified() }
            ?.let { if (it.length() > 0) return it }
        return null
    }

    // ------------------------------------------------------------------
    // ApkParser
    // ------------------------------------------------------------------

    @Test
    fun `解析真实交付APK的包名与版本`() {
        val apk = findTestApk()
        assumeTrue(apk != null, "deliverables 下无可用测试 APK，跳过")
        val info = ApkParser.parse(apk!!)
        assertNotNull(info, "解析真实 APK 不应失败")
        assertEquals("com.example.boxtv", info!!.packageName)
        assertTrue(info.versionCode > 0, "versionCode 应为正数，实际=${info.versionCode}")
        assertTrue(info.versionName.isNotBlank(), "versionName 不应为空")
        println("解析结果: $info (来自 ${apk.name})")
    }

    @Test
    fun `重复解析结果一致`() {
        val apk = findTestApk() ?: return assumeTrue(false, "无测试 APK，跳过")
        val a = ApkParser.parse(apk)
        val b = ApkParser.parse(apk)
        assertEquals(a, b)
    }

    @Test
    fun `非APK文件返回null`() {
        val tmp = Files.createTempFile("notapk", ".apk").toFile()
        try {
            tmp.writeBytes(ByteArray(1024) { it.toByte() })
            assertNull(ApkParser.parse(tmp))
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `缺清单的zip返回null`() {
        val tmp = Files.createTempFile("nomanifest", ".apk").toFile()
        try {
            java.util.zip.ZipOutputStream(tmp.outputStream()).use { zos ->
                zos.putNextEntry(java.util.zip.ZipEntry("classes.dex"))
                zos.write(ByteArray(16))
                zos.closeEntry()
            }
            assertNull(ApkParser.parse(tmp))
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `不存在的文件返回null`() {
        assertNull(ApkParser.parse(File("definitely/not/exist.apk")))
    }

    // ------------------------------------------------------------------
    // UpdateStore
    // ------------------------------------------------------------------

    private fun tempStore(): UpdateStore =
        UpdateStore(Files.createTempDirectory("boxtv-update-test").toFile())

    @Test
    fun `发布-校验MD5与元数据`() = runBlocking {
        val apk = findTestApk() ?: return@runBlocking assumeTrue(false, "无测试 APK，跳过")
        val store = tempStore()
        val meta = store.publish(apk, "修复若干问题").getOrThrow()

        assertEquals("com.example.boxtv", meta.packageName)
        assertTrue(meta.versionCode > 0)
        assertEquals(apk.length(), meta.size)
        assertEquals(UpdateStore.md5Of(apk), meta.md5)
        assertEquals("修复若干问题", meta.changelog)
        assertEquals(meta, store.meta.value, "发布后 StateFlow 应同步")
        assertEquals(meta.size, store.apk.length(), "安装包应完整复制")
        // 重启加载：新 store 实例从磁盘恢复元数据
        val reloaded = UpdateStore(store.apk.parentFile.parentFile)
        reloaded.load()
        assertEquals(meta, reloaded.meta.value)
    }

    @Test
    fun `重复发布覆盖旧版本`() = runBlocking {
        val apk = findTestApk() ?: return@runBlocking assumeTrue(false, "无测试 APK，跳过")
        val store = tempStore()
        val first = store.publish(apk, "v1").getOrThrow()
        // 修改副本内容再发布（模拟不同安装包，MD5 必然变化）
        val copy = Files.createTempFile("copy", ".apk").toFile()
        apk.copyTo(copy, overwrite = true)
        copy.appendBytes(ByteArray(1))
        val second = store.publish(copy, "v2").getOrThrow()
        assertEquals(first.md5 != second.md5, true, "新版本 MD5 应不同")
        assertEquals(second, store.meta.value)
        assertEquals(second.size, store.apk.length(), "安装包应被覆盖")
    }

    @Test
    fun `取消发布后检查不到版本`() = runBlocking {
        val apk = findTestApk() ?: return@runBlocking assumeTrue(false, "无测试 APK，跳过")
        val store = tempStore()
        store.publish(apk, "test")
        assertNotNull(store.meta.value)
        store.clear()
        assertNull(store.meta.value, "取消发布后应为 null")
        assertFalse(store.apk.exists(), "安装包应删除")
    }

    @Test
    fun `发布非APK文件被拒绝`() = runBlocking {
        val store = tempStore()
        val fake = Files.createTempFile("fake", ".apk").toFile()
        fake.writeText("这不是 APK")
        val result = store.publish(fake, "x")
        assertTrue(result.isFailure, "非 APK 应发布失败")
        assertNull(store.meta.value)
    }
}
