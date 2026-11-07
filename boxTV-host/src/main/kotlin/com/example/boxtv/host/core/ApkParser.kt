package com.example.boxtv.host.core

import java.io.File
import java.util.zip.ZipFile

/**
 * APK 版本信息解析器（core 层）—— 2026-11-07 局域网更新功能
 *
 * 上传新版本安装包时，电脑端自动从 APK 内的二进制 AndroidManifest.xml
 * （AXML 格式）中提取包名 / versionCode / versionName，用户无需手工填写版本号。
 *
 * AXML 格式要点（Android 二进制资源格式）：
 * - 文件由若干 Chunk 串联：StringPool(0x0001) → ResourceMap(0x0180) →
 *   StartElement(0x0102) / EndElement(0x0103) …；
 * - 每个 Chunk 头部固定 8 字节：type(2) headerSize(2) size(4)；
 * - 元素属性值是 TypedValue：dataType 0x03=字符串（data 为字符串池下标）、
 *   0x10=十进制整数（data 即值，versionCode 走这里）；
 * - 属性名直接是字符串池里的名字（如 "versionCode"/"package"），
 *   无需解析 ResourceMap，按名字匹配即可。
 */
object ApkParser {

    /** 解析结果：包名 + 版本号（versionCode 用于新旧版本比较，versionName 用于展示） */
    data class ApkInfo(
        val packageName: String,
        val versionCode: Long,
        val versionName: String
    )

    /** Chunk 类型常量（AXML） */
    private const val TYPE_STRING_POOL = 0x0001
    private const val TYPE_START_ELEMENT = 0x0102

    /** TypedValue dataType 常量 */
    private const val DATA_TYPE_STRING = 0x03
    private const val DATA_TYPE_INT_DEC = 0x10
    private const val DATA_TYPE_INT_HEX = 0x11

    /**
     * 解析 APK 文件的版本信息。
     * @return 解析失败（非 APK/缺少清单/格式异常）返回 null
     */
    fun parse(apk: File): ApkInfo? = runCatching {
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml") ?: return null
            val bytes = zip.getInputStream(entry).readBytes()
            parseManifest(bytes)
        }
    }.getOrNull()

    // ------------------------------------------------------------------
    // AXML 解析
    // ------------------------------------------------------------------

    /** 解析二进制清单：顺序扫描 Chunk，先取字符串池，再从 manifest 起始元素读属性 */
    fun parseManifest(b: ByteArray): ApkInfo? {
        if (b.size < 8) return null
        var pos = 8 // 跳过文件级头（type=0x0003 RES_XML_TYPE，8 字节）
        var strings: List<String> = emptyList()

        while (pos + 8 <= b.size) {
            val type = readUShort(b, pos)
            val size = readInt(b, pos + 4)
            if (size <= 0 || pos + size > b.size + 4) break // 防御：结构损坏时终止

            when (type) {
                TYPE_STRING_POOL -> strings = parseStringPool(b, pos)
                TYPE_START_ELEMENT -> {
                    val info = parseStartElement(b, pos, strings)
                    if (info != null) return info
                }
            }
            pos += size
        }
        return null
    }

    /**
     * 解析字符串池 Chunk（UTF-8 / UTF-16 两种编码自动识别）。
     * 头部 28 字节：stringCount / styleCount / flags / stringsStart / stylesStart
     * 之后是 stringCount 个 4 字节偏移，数据区从 chunk 起始 + stringsStart 开始。
     */
    private fun parseStringPool(b: ByteArray, pos: Int): List<String> {
        val stringCount = readInt(b, pos + 8)
        val flags = readInt(b, pos + 16)
        val stringsStart = readInt(b, pos + 20)
        val isUtf8 = flags and (1 shl 8) != 0
        val dataBase = pos + stringsStart
        val offsetsBase = pos + 28

        val out = ArrayList<String>(stringCount.coerceAtMost(100_000))
        for (i in 0 until stringCount) {
            val off = dataBase + readInt(b, offsetsBase + i * 4)
            out += runCatching { readPoolString(b, off, isUtf8) }.getOrDefault("")
        }
        return out
    }

    /** 读单个池字符串：UTF-8 池长度为 1~2 字节变长；UTF-16 池长度固定 2 字节（u16） */
    private fun readPoolString(b: ByteArray, off: Int, isUtf8: Boolean): String {
        if (isUtf8) {
            // UTF-8 池：两个长度字段（UTF-16 单元数、字节数），后者定位内容
            readVarLen(b, off)
            val p2 = if (b[off].toInt() and 0x80 != 0) off + 2 else off + 1
            val byteLen = readVarLen(b, p2)
            val p3 = if (b[p2].toInt() and 0x80 != 0) p2 + 2 else p2 + 1
            return String(b, p3, byteLen, Charsets.UTF_8)
        } else {
            // UTF-16 池：长度字段固定占 2 字节（u16 LE），最高位为 1 时再读一个 u16
            val first = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
            var p = off + 2
            val charLen = if (first and 0x8000 != 0) {
                val second = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)
                p += 2
                ((first and 0x7FFF) shl 16) or second
            } else first
            return String(b, p, charLen * 2, Charsets.UTF_16LE)
        }
    }

    /** 变长长度字段：单字节（<0x80）或双字节（高位 1：低 7 位 << 8 | 次字节） */
    private fun readVarLen(b: ByteArray, p: Int): Int {
        val first = b[p].toInt() and 0xFF
        return if (first and 0x80 != 0) ((first and 0x7F) shl 8) or (b[p + 1].toInt() and 0xFF) else first
    }

    /**
     * 解析起始元素 Chunk：仅关心 manifest 元素的三个属性
     * - package（无命名空间的普通属性，字符串值）
     * - android:versionCode（INT_DEC / INT_HEX 整数）
     * - android:versionName（字符串）
     *
     * Chunk 内布局：头 8 字节 + lineNumber(4) comment(4) + ns(4) name(4)
     * + attributeStart(2) attributeSize(2) attributeCount(2) …
     * 属性记录各 20 字节：ns(4) name(4) rawValue(4) + TypedValue(8：
     * size(2) res0(1) dataType(1) data(4))
     */
    private fun parseStartElement(b: ByteArray, pos: Int, strings: List<String>): ApkInfo? {
        if (pos + 36 > b.size) return null
        val nameIdx = readInt(b, pos + 20)
        val elemName = strings.getOrNull(nameIdx) ?: return null
        // 元素名大小写不定（aapt 生成 "Manifest"），按忽略大小写匹配
        if (!elemName.equals("manifest", ignoreCase = true)) return null

        val attributeStart = readUShort(b, pos + 24)
        val attributeSize = readUShort(b, pos + 26)
        val attributeCount = readUShort(b, pos + 28)
        if (attributeSize <= 0) return null

        var packageName: String? = null
        var versionCode = -1L
        var versionName: String? = null

        for (i in 0 until attributeCount) {
            // 属性数组偏移相对 attrExt 结构（pos+16），典型 attributeStart=20 → pos+36
            val a = pos + 16 + attributeStart + i * attributeSize
            if (a + 20 > b.size) break
            val attrName = strings.getOrNull(readInt(b, a + 4)) ?: continue
            val rawValueIdx = readInt(b, a + 8)
            val dataType = b[a + 15].toInt() and 0xFF
            val data = readInt(b, a + 16)

            when (attrName) {
                "package" -> packageName = resolveString(strings, rawValueIdx, dataType, data)
                "versionCode" -> versionCode = when (dataType) {
                    DATA_TYPE_INT_DEC, DATA_TYPE_INT_HEX -> data.toLong() and 0xFFFFFFFFL
                    else -> resolveString(strings, rawValueIdx, dataType, data)?.toLongOrNull() ?: -1L
                }
                "versionName" -> versionName = resolveString(strings, rawValueIdx, dataType, data)
            }
        }

        packageName ?: return null
        if (versionCode < 0) return null
        return ApkInfo(packageName, versionCode, versionName ?: "")
    }

    /** 属性字符串取值：优先 rawValue 字符串池下标，否则 TypedValue 的字符串 data */
    private fun resolveString(strings: List<String>, rawValueIdx: Int, dataType: Int, data: Int): String? {
        if (rawValueIdx >= 0) return strings.getOrNull(rawValueIdx)
        if (dataType == DATA_TYPE_STRING && data >= 0) return strings.getOrNull(data)
        return null
    }

    // ------------------------------------------------------------------
    // 字节序读取（AXML 全部小端）
    // ------------------------------------------------------------------

    private fun readUShort(b: ByteArray, p: Int): Int =
        (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)

    private fun readInt(b: ByteArray, p: Int): Int =
        (b[p].toInt() and 0xFF) or
            ((b[p + 1].toInt() and 0xFF) shl 8) or
            ((b[p + 2].toInt() and 0xFF) shl 16) or
            ((b[p + 3].toInt() and 0xFF) shl 24)
}
