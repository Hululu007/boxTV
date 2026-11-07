package com.example.boxtv.host.server

import java.io.File
import java.util.Base64
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * M3U8 智能识别与聚合模块单元测试（2026-11-07 M3U8 播放支持）
 *
 * 覆盖场景：
 * - 有效媒体列表目录识别（单层结构：index.m3u8 + 分片）；
 * - 缺分片的残缺目录排除；
 * - master → variant 多码率结构（最高码率校验 / 残缺变体排除）；
 * - AES-128 本地密钥（KEY 齐全有效 / 缺密钥无效）；
 * - 目录穿越引用（../ 与 URL 编码穿越）一律判无效；
 * - 播放列表改写正确性（分片行 / KEY 属性行 → part 绝对地址，u 可反解）；
 * - 层级判定：只认直属（深度1）m3u8，剧集总目录/分类文件夹不越级误判。
 */
class M3u8AggregatorTest {

    companion object {
        /** 两分片媒体列表模板（endlist 结尾的标准 VOD 结构；{i} 为分片序号占位） */
        private fun mediaPlaylist(segPattern: String, count: Int, keyUri: String? = null): String =
            buildString {
                appendLine("#EXTM3U")
                appendLine("#EXT-X-VERSION:3")
                appendLine("#EXT-X-TARGETDURATION:10")
                appendLine("#EXT-X-MEDIA-SEQUENCE:0")
                if (keyUri != null) {
                    appendLine("""#EXT-X-KEY:METHOD=AES-128,URI="$keyUri",IV=0x9c7db8778570d05c3177c349fd9236aa""")
                }
                repeat(count) { i ->
                    appendLine("#EXTINF:9.0,")
                    appendLine(segPattern.replace("{i}", i.toString()))
                }
                appendLine("#EXT-X-ENDLIST")
            }

        /** 主列表模板：两个变体（低码率在前，验证取最高码率校验） */
        private fun masterPlaylist(v0: String, v1: String): String = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-STREAM-INF:BANDWIDTH=1280000,RESOLUTION=640x360")
            appendLine(v0)
            appendLine("#EXT-X-STREAM-INF:BANDWIDTH=2560000,RESOLUTION=1280x720")
            appendLine(v1)
        }

        /** 写文件（自动建父目录），内容统一 LF 行尾 */
        private fun write(base: File, rel: String, content: String): File {
            val f = File(base, rel)
            f.parentFile.mkdirs()
            f.writeText(content)
            return f
        }

        /** 解码改写结果中的 u 参数 → 根目录相对路径（属性行内以引号结尾，需截断） */
        private fun decodeU(partUrl: String): String {
            val u = partUrl.substringAfter("&u=").substringBefore('"')
            return String(Base64.getUrlDecoder().decode(u), Charsets.UTF_8)
        }
    }

    private val aggregator = M3u8Aggregator()

    /** 测试根目录（模拟授权文件夹根） */
    @TempDir
    lateinit var root: File

    // ------------------------------------------------------------------
    // 识别：有效 / 无效目录
    // ------------------------------------------------------------------

    @Test
    fun `单层媒体列表目录识别为有效`() {
        val movie = File(root, "9.M3U8")
        write(movie, "index.m3u8", mediaPlaylist("seg{i}.ts", 2))
        write(movie, "seg0.ts", "x")
        write(movie, "seg1.ts", "x")

        val pl = aggregator.detect(movie)
        assertNotNull(pl, "分片齐全的目录应被识别为有效 M3U8 文件夹")
        assertEquals(File(movie, "index.m3u8").canonicalFile, pl!!.canonicalFile)
    }

    @Test
    fun `缺分片的残缺目录被排除`() {
        val movie = File(root, "broken")
        write(movie, "index.m3u8", mediaPlaylist("seg{i}.ts", 3))
        write(movie, "seg0.ts", "x")
        // seg1/seg2 缺失：下载不完整的目录

        assertNull(aggregator.detect(movie), "分片不齐全的目录不应被识别")
    }

    @Test
    fun `目录无m3u8文件时识别为null`() {
        val plain = File(root, "plain")
        write(plain, "a.mp4", "x")
        assertNull(aggregator.detect(plain))
    }

    // ------------------------------------------------------------------
    // 识别：master → variant 多码率结构
    // ------------------------------------------------------------------

    @Test
    fun `master加完整变体识别有效且优先返回master`() {
        val movie = File(root, "movie")
        write(movie, "master.m3u8", masterPlaylist("v0/prog_index.m3u8", "v1/prog_index.m3u8"))
        write(movie, "v0/prog_index.m3u8", mediaPlaylist("seg{i}.ts", 2))
        write(movie, "v0/seg0.ts", "x")
        write(movie, "v0/seg1.ts", "x")
        write(movie, "v1/prog_index.m3u8", mediaPlaylist("seg{i}.ts", 2))
        write(movie, "v1/seg0.ts", "x")
        write(movie, "v1/seg1.ts", "x")

        val pl = aggregator.detect(movie)
        assertNotNull(pl)
        assertEquals(File(movie, "master.m3u8").canonicalFile, pl!!.canonicalFile, "master 优先作为服务对象")
    }

    @Test
    fun `最高码率变体残缺时整目录判无效`() {
        val movie = File(root, "movie2")
        write(movie, "master.m3u8", masterPlaylist("v0/prog_index.m3u8", "v1/prog_index.m3u8"))
        write(movie, "v0/prog_index.m3u8", mediaPlaylist("seg{i}.ts", 2))
        write(movie, "v0/seg0.ts", "x")
        write(movie, "v0/seg1.ts", "x")
        write(movie, "v1/prog_index.m3u8", mediaPlaylist("seg{i}.ts", 2))
        write(movie, "v1/seg0.ts", "x")
        // v1/seg1.ts 缺失（最高码率变体残缺）

        assertNull(aggregator.detect(movie), "最高码率变体不完整 → 按残缺目录排除")
    }

    // ------------------------------------------------------------------
    // 识别：AES-128 本地密钥
    // ------------------------------------------------------------------

    @Test
    fun `AES密钥齐全的加密目录识别有效`() {
        val movie = File(root, "aes")
        write(movie, "index.m3u8", mediaPlaylist("seg{i}.ts", 2, keyUri = "enc.key"))
        write(movie, "seg0.ts", "x")
        write(movie, "seg1.ts", "x")
        write(movie, "enc.key", "0123456789abcdef")

        val pl = aggregator.detect(movie)
        assertNotNull(pl, "密钥文件存在 → 加密目录有效")
        assertEquals(File(movie, "index.m3u8").canonicalFile, pl!!.canonicalFile)
    }

    @Test
    fun `AES密钥缺失的加密目录被排除`() {
        val movie = File(root, "aes2")
        write(movie, "index.m3u8", mediaPlaylist("seg{i}.ts", 2, keyUri = "enc.key"))
        write(movie, "seg0.ts", "x")
        write(movie, "seg1.ts", "x")
        // enc.key 缺失

        assertNull(aggregator.detect(movie), "密钥缺失 → 视为不完整目录")
    }

    // ------------------------------------------------------------------
    // 识别：目录穿越防护
    // ------------------------------------------------------------------

    @Test
    fun `明文目录穿越引用判无效`() {
        // 影片目录引用 ../ 分片：即使文件真实存在也必须判无效（防越界）
        write(root, "outside.ts", "x")
        val movie = File(root, "traversal")
        write(movie, "index.m3u8", mediaPlaylist("../outside.ts", 1))

        assertNull(aggregator.detect(movie), "引用影片文件夹之外的分片应判无效")
    }

    @Test
    fun `URL编码的目录穿越引用判无效`() {
        write(root, "outside2.ts", "x")
        val movie = File(root, "traversal2")
        // %2e%2e%2f = ../（下载器/恶意列表常见的编码穿越形态）
        write(movie, "index.m3u8", mediaPlaylist("%2e%2e%2foutside2.ts", 1))

        assertNull(aggregator.detect(movie), "URL 编码穿越应被解码识别并拒绝")
    }

    // ------------------------------------------------------------------
    // 识别：层级判定（分类文件夹 vs 影片文件夹）
    // ------------------------------------------------------------------

    @Test
    fun `分类文件夹不会误判为影片目录`() {
        // 用户实际场景回归：A/B/1.M3U8/index.m3u8（列表在 B 下深度2）。
        // B 是装着多部影片的分类文件夹，直属层没有任何 m3u8，
        // 不得把 B 聚合成「其中某一集」
        val b = File(root, "B")
        write(b, "1.M3U8/index.m3u8", mediaPlaylist("seg{i}.ts", 2))
        write(b, "1.M3U8/seg0.ts", "x")
        write(b, "1.M3U8/seg1.ts", "x")
        write(b, "2.M3U8/index.m3u8", mediaPlaylist("seg{i}.ts", 2))
        write(b, "2.M3U8/seg0.ts", "x")
        write(b, "2.M3U8/seg1.ts", "x")
        write(b, "3.M3U8/index.m3u8", mediaPlaylist("seg{i}.ts", 2))
        write(b, "3.M3U8/seg0.ts", "x")
        write(b, "3.M3U8/seg1.ts", "x")

        assertNull(aggregator.detect(b), "分类文件夹不应被误聚合成其中某一集")
        // 点开 B 后，直属的各影片文件夹分别聚合为独立视频条目
        assertNotNull(aggregator.detect(File(b, "1.M3U8")), "影片文件夹 1.M3U8 应被识别")
        assertNotNull(aggregator.detect(File(b, "2.M3U8")), "影片文件夹 2.M3U8 应被识别")
        assertNotNull(aggregator.detect(File(b, "3.M3U8")), "影片文件夹 3.M3U8 应被识别")
    }

    @Test
    fun `多级剧集总目录不越级识别`() {
        // 用户实际场景回归（2026-11-07 层级误判修复）：
        // 视频/行尸走肉第二季/10.m3u8/index.m3u8——播放列表在深度3，
        // 行尸走肉第二季 是剧集总目录（直属层只有分集子文件夹，无任何 m3u8），
        // 不得把总目录聚合为其中某一集；分集文件夹自身进入后才聚合
        val series = File(root, "行尸走肉第二季")
        write(series, "9.m3u8/index.m3u8", mediaPlaylist("seg{i}.ts", 2))
        write(series, "9.m3u8/seg0.ts", "x")
        write(series, "9.m3u8/seg1.ts", "x")
        write(series, "10.m3u8/index.m3u8", mediaPlaylist("seg{i}.ts", 2))
        write(series, "10.m3u8/seg0.ts", "x")
        write(series, "10.m3u8/seg1.ts", "x")

        assertNull(aggregator.detect(series), "剧集总目录不应被聚合成分集中某一集")
        assertNotNull(aggregator.detect(File(series, "9.m3u8")), "分集文件夹进入后应识别为影片目录")
        assertNotNull(aggregator.detect(File(series, "10.m3u8")), "分集文件夹进入后应识别为影片目录")
    }

    @Test
    fun `master在根而变体在子目录时识别有效`() {
        // master 结构：影片/master.m3u8（深度1）+ 影片/v0/prog_index.m3u8（变体）。
        // 变体子列表按 master 中的 URI 直接解析校验，不受扫描层级限制
        val movie = File(root, "mv2")
        write(movie, "master.m3u8", masterPlaylist("v0/prog_index.m3u8", "v1/prog_index.m3u8"))
        write(movie, "v0/prog_index.m3u8", mediaPlaylist("seg{i}.ts", 2))
        write(movie, "v0/seg0.ts", "x")
        write(movie, "v0/seg1.ts", "x")
        write(movie, "v1/prog_index.m3u8", mediaPlaylist("seg{i}.ts", 2))
        write(movie, "v1/seg0.ts", "x")
        write(movie, "v1/seg1.ts", "x")

        val pl = aggregator.detect(movie)
        assertNotNull(pl, "主列表在根的 master 结构应被识别")
        assertEquals(File(movie, "master.m3u8").canonicalFile, pl!!.canonicalFile)
    }

    // ------------------------------------------------------------------
    // 改写正确性
    // ------------------------------------------------------------------

    @Test
    fun `播放列表改写为part绝对地址且u可反解`() {
        val authority = "192.168.1.5:8964"
        val videoId = VideoIdCodec.encode("f1", "aes/index.m3u8")
        val playlist = write(root, "aes/index.m3u8", mediaPlaylist("seg{i}.ts", 2, keyUri = "enc.key"))
        write(root, "aes/seg0.ts", "x")
        write(root, "aes/seg1.ts", "x")
        write(root, "aes/enc.key", "0123456789abcdef")

        val out = aggregator.rewritten(playlist, root, authority, videoId)
        val lines = out.lines()

        // 分片行 → http://{authority}/api/video/part?videoId=..&u=..
        // （KEY 属性行以 # 开头不计入，单独校验其 URI 替换）
        val segLines = lines.filter { it.startsWith("http://") }
        assertEquals(2, segLines.size, "2 个分片应改写为 part 地址")
        segLines.forEach { url ->
            assertTrue(url.startsWith("http://$authority/api/video/part?videoId=$videoId&u="), "part 地址格式：$url")
        }
        // u 反解 → 授权根目录相对路径（'/' 分隔）
        assertEquals("aes/seg0.ts", decodeU(segLines[0]))
        assertEquals("aes/seg1.ts", decodeU(segLines[1]))
        // 非资源行原样保留（注释标签不被破坏）
        assertTrue(lines.contains("#EXTM3U"))
        assertTrue(lines.any { it == "#EXTINF:9.0," })
        // KEY 属性行：仅 URI="..." 部分被替换为 part 地址，其余属性原样保留
        val keyLine = lines.first { it.startsWith("#EXT-X-KEY") }
        assertTrue(
            keyLine.startsWith("#EXT-X-KEY:METHOD=AES-128") && keyLine.contains("/api/video/part"),
            "KEY 属性行应仅替换 URI 部分：$keyLine"
        )
        assertTrue(keyLine.contains("IV=0x9c7db877"), "IV 等其余属性不得被改动")
        assertEquals("aes/enc.key", decodeU(keyLine))
    }

    @Test
    fun `改写支持中文分片名与URL编码分片名`() {
        val playlist = write(root, "cn/index.m3u8", mediaPlaylist("片段{i}.ts", 1))
        write(root, "cn/片段0.ts", "x")
        val out = aggregator.rewritten(playlist, root, "10.0.0.2:80", "vid")
        assertEquals("cn/片段0.ts", decodeU(out.lines().first { it.startsWith("http://") }))
    }

    @Test
    fun `变体子列表同样改写且相对自身目录解析`() {
        // master + v0 变体：v0 内部分片相对 v0 目录解析为根相对路径 v0/segN.ts
        write(root, "mv/master.m3u8", masterPlaylist("v0/prog_index.m3u8", "v0/prog_index.m3u8"))
        write(root, "mv/v0/prog_index.m3u8", mediaPlaylist("seg{i}.ts", 2))
        write(root, "mv/v0/seg0.ts", "x")
        write(root, "mv/v0/seg1.ts", "x")

        val variant = File(root, "mv/v0/prog_index.m3u8")
        val out = aggregator.rewritten(variant, root, "h:1", "vid")
        assertEquals("mv/v0/seg0.ts", decodeU(out.lines().first { it.startsWith("http://") }))
    }

    @Test
    fun `CRLF行尾的列表正常改写`() {
        val playlist = write(root, "crlf/index.m3u8", mediaPlaylist("seg{i}.ts", 2).replace("\n", "\r\n"))
        write(root, "crlf/seg0.ts", "x")
        write(root, "crlf/seg1.ts", "x")
        val out = aggregator.rewritten(playlist, root, "h:2", "vid")
        assertTrue(out.lines().count { it.startsWith("http://") } == 2, "CRLF 不应影响改写")
    }

    @Test
    fun `改写结果对同一playlist跨请求稳定且Host替换生效`() {
        val playlist = write(root, "stable/index.m3u8", mediaPlaylist("seg{i}.ts", 1))
        write(root, "stable/seg0.ts", "x")
        val vid = VideoIdCodec.encode("f1", "stable/index.m3u8")
        val a = aggregator.rewritten(playlist, root, "1.1.1.1:80", vid)
        val b = aggregator.rewritten(playlist, root, "2.2.2.2:80", vid)
        // 不同 Host 头各自生效（缓存模板 + 请求时替换）
        assertTrue(a.contains("http://1.1.1.1:80/"))
        assertTrue(b.contains("http://2.2.2.2:80/"))
        assertNotEquals(a, b)
    }
}
