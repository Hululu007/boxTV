package com.example.boxtv.host.server

import java.io.File
import java.net.URLDecoder
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * M3U8 智能识别与聚合模块（server 层）—— 2026-11-07 M3U8 播放支持新增
 *
 * 背景：用户按「一部影片 = 一个文件夹（内含 m3u8 播放列表 + 全部视频分片）」
 * 的方式存放内容。本模块在电脑端完成全部识别/解析/改写（电视端零高负载运算）：
 *
 * 1. 识别：扫描文件夹内（限深）的 *.m3u8，解析播放列表并逐一校验所有被引用的
 *    本地资源（视频分片 / 子播放列表 / EXT-X-KEY·EXT-X-MAP 的密钥与初始化段）
 *    真实存在——全部齐全才算「有效 M3U8 文件夹」，排除下载不完整的残缺目录；
 * 2. 聚合：用户浏览进入有效文件夹内部时，以「单个虚拟视频条目」呈现
 *    （名称 = 文件夹名，如文件夹 9.M3U8 → 视频条目 9.M3U8），
 *    内部零散分片完全隐藏，用户点击条目即播放完整序列。
 *    上级列表中有效文件夹仍以普通文件夹展示（2026-11-07 层级修复：
 *    保证「总目录 → 分集子目录 → 影片条目」三级层级，不越级渲染）；
 *    搜索结果中有效文件夹直接聚合为可播放条目；
 * 3. 改写：对外服务播放列表时，把其中所有相对 URI（分片行、子播放列表行、
 *    KEY/MAP/MEDIA 属性）改写为指向电脑端 /api/video/part 的绝对地址，
 *    电视端 ExoPlayer 原生 HLS 播放器即可直接播放——秒开、原生精准 seek，
 *    电脑端不参与分片转发，零转码 CPU 开销。
 *
 * 结构支持：
 * - 单层媒体列表：影片/index.m3u8 + seg*.ts；
 * - 主列表 + 变体（多码率）：影片/master.m3u8 + v0/prog_index.m3u8 + 分片
 *   （校验取最高 BANDWIDTH 变体；服务时优先返回主列表，变体/音轨子列表经
 *   part 接口递归改写，天然支持任意层级嵌套）；
 * - AES-128 本地密钥：#EXT-X-KEY 的密钥文件同样经 part 接口下发。
 */
class M3u8Aggregator {

    companion object {
        /** HLS 主列表标记行（含此行 = master playlist，其后跟变体子列表 URI） */
        private const val TAG_STREAM_INF = "#EXT-X-STREAM-INF"

        /** 变体带宽属性（master 多码率时选取最高码率变体做完整性校验） */
        private val BANDWIDTH = Regex("""BANDWIDTH=(\d+)""")

        /** 属性 URI 捕获（EXT-X-KEY / EXT-X-MAP / EXT-X-MEDIA 等行内的 URI="..."） */
        private val URI_ATTR = Regex("""URI="([^"]+)"""")

        /** 单个文件夹最多校验的播放列表数（防极端目录拖慢首次扫描） */
        private const val MAX_CANDIDATES = 8

        /** 改写模板占位符：Host/videoId 每次请求才替换，模板本体可跨请求缓存 */
        private const val PH_AUTHORITY = "%AUTHORITY%"
        private const val PH_VIDEO_ID = "%VIDEOID%"
        private const val PART_PATH = "/api/video/part?videoId="
    }

    // ------------------------------------------------------------------
    // 识别（带缓存）
    // ------------------------------------------------------------------

    /**
     * 识别缓存：key = 文件夹绝对路径，value = (文件夹修改时间, 结果)。
     * 浏览/搜索每次刷新都要扫描子文件夹，缓存把重复扫描降为 O(1)；
     * 文件夹修改时间变化（分片下载新增/删除）自动失效重扫。
     */
    private val detectCache = ConcurrentHashMap<String, Pair<Long, File?>>()

    /** 改写模板缓存：key = 播放列表绝对路径|修改时间，value = (修改时间, 模板文本) */
    private val rewriteCache = ConcurrentHashMap<String, Pair<Long, String>>()

    /**
     * 识别「文件夹自身是否为包含完整分片的有效 M3U8 影片文件夹」
     * （进入影片目录后的聚合 / 授权根直指影片目录 / 搜索聚合，三个场景共用本判定）。
     *
     * 只认文件夹**直属（深度1）**的 *.m3u8 作为影片主列表（2026-11-07 修复层级误判）：
     * - 影片/index.m3u8 + 影片/seg*.ts（媒体列表在根）→ 有效；
     * - 影片/master.m3u8 + 影片/v0/prog_index.m3u8（主列表在根；变体子列表
     *   按 master 中的 URI 直接解析校验，不受扫描层级限制）→ 有效且 master 优先；
     * - 分类文件夹/影片文件夹/index.m3u8（列表藏在深度2，目录直属层无任何
     *   m3u8）→ **不是影片目录**——避免把「装着多部影片的分类文件夹」误聚合成
     *   其中某一集；此时应继续下钻，由直属的各影片文件夹分别聚合为视频条目。
     *
     * @return 应对外服务的播放列表文件（master 优先）；无效返回 null
     */
    fun detect(dir: File): File? {
        if (!dir.isDirectory) return null
        val key = dir.absolutePath
        detectCache[key]?.let { (mtime, hit) ->
            if (mtime == dir.lastModified()) return hit?.takeIf { it.isFile }
        }
        val result = scan(dir, maxDepth = 1)
        detectCache[key] = dir.lastModified() to result
        return result
    }

    /** 收集候选（深度升序）→ 逐个校验 → 返回第一个有效者 */
    private fun scan(dir: File, maxDepth: Int): File? {
        val candidates = mutableListOf<Pair<Int, File>>()
        collect(dir, 1, maxDepth, candidates)
        candidates.sortWith(compareBy({ it.first }, { it.second.name.lowercase() }))
        val scope = dir.canonicalFile
        // 主列表严格优先：目录内存在 master 时只认 master——
        // 最高码率变体残缺 = 影片不完整，整目录排除（不回退到深层变体子列表，
        // 避免把「下载到一半」的目录误判成可播的低码率影片）
        val masters = candidates.filter { isMasterPlaylist(it.second) }
        if (masters.isNotEmpty()) {
            for ((_, pl) in masters) {
                if (validate(pl, scope)) return pl
            }
            return null
        }
        for ((_, pl) in candidates) {
            if (validate(pl, scope)) return pl
        }
        return null
    }

    /** 是否为 master 播放列表（含 EXT-X-STREAM-INF 行） */
    private fun isMasterPlaylist(f: File): Boolean = try {
        f.readLines(Charsets.UTF_8).any { it.trim().startsWith(TAG_STREAM_INF) }
    } catch (_: Exception) {
        false
    }

    /** 限深收集 *.m3u8 候选：depth=1 为目录直属，depth=2 为一级子目录内 */
    private fun collect(dir: File, depth: Int, maxDepth: Int, out: MutableList<Pair<Int, File>>) {
        if (out.size >= MAX_CANDIDATES) return
        val children = dir.listFiles() ?: return
        for (f in children) {
            if (f.isFile && f.extension.equals("m3u8", ignoreCase = true)) {
                out += depth to f
                if (out.size >= MAX_CANDIDATES) return
            }
        }
        if (depth < maxDepth) {
            for (f in children) {
                if (f.isDirectory) collect(f, depth + 1, maxDepth, out)
            }
        }
    }

    /**
     * 校验播放列表完整性：媒体列表要求所有分片/密钥资源存在，
     * 主列表要求最高码率变体子列表及其分片全部存在。
     * 所有被引用资源必须落在 scope（影片文件夹）内——目录穿越引用一律判无效。
     */
    private fun validate(playlist: File, scope: File): Boolean = try {
        val lines = playlist.readLines(Charsets.UTF_8)
        when {
            // 非法/损坏文件连 #EXTM3U 头都没有，直接排除
            lines.none { it.trim().startsWith("#EXTM3U") } -> false
            // 主列表：校验最高码率变体
            lines.any { it.trim().startsWith(TAG_STREAM_INF) } -> validateMaster(lines, playlist, scope)
            // 媒体列表：校验全部分片与 KEY/MAP 资源
            else -> validateMedia(lines, playlist, scope)
        }
    } catch (_: Exception) {
        false // 读取失败（权限/编码异常）视为无效
    }

    /** 主列表校验：取最高 BANDWIDTH 变体子列表，递归按媒体列表校验其完整性 */
    private fun validateMaster(lines: List<String>, playlist: File, scope: File): Boolean {
        var bestBw = -1L
        var bestUri: String? = null
        var pendingBw = 0L
        for (raw in lines) {
            val line = raw.trim()
            when {
                // STREAM-INF 行记录带宽，与紧随其后的变体 URI 行配对
                line.startsWith(TAG_STREAM_INF) ->
                    pendingBw = BANDWIDTH.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                line.isNotEmpty() && !line.startsWith("#") -> {
                    if (pendingBw > bestBw) {
                        bestBw = pendingBw
                        bestUri = line
                    }
                    pendingBw = 0L
                }
            }
        }
        val uri = bestUri ?: return false
        if (isAbsoluteHttp(uri)) return false // 依赖外部流：不属于本地完整影片
        val variant = resolveScoped(uri, playlist.parentFile, scope) ?: return false
        if (!variant.isFile) return false
        val variantLines = try {
            variant.readLines(Charsets.UTF_8)
        } catch (_: Exception) {
            return false
        }
        return validateMedia(variantLines, variant, scope)
    }

    /** 媒体列表校验：每个分片行与属性行 URI（KEY/MAP 密钥等）都必须本地存在 */
    private fun validateMedia(lines: List<String>, playlist: File, scope: File): Boolean {
        var segments = 0
        for (raw in lines) {
            val line = raw.trim()
            when {
                line.isEmpty() -> {}
                line.startsWith("#") -> {
                    // AES-128 密钥 / EXT-X-MAP 初始化段等：同样要求本地齐全
                    for (m in URI_ATTR.findAll(line)) {
                        val f = resolveScoped(m.groupValues[1], playlist.parentFile, scope) ?: return false
                        if (!f.isFile) return false
                    }
                }
                else -> {
                    segments++
                    val f = resolveScoped(line, playlist.parentFile, scope) ?: return false
                    if (!f.isFile) return false
                }
            }
        }
        return segments > 0 // 没有任何分片的空列表无效
    }

    // ------------------------------------------------------------------
    // 播放列表改写（对外服务时调用）
    // ------------------------------------------------------------------

    /**
     * 将播放列表中所有本地相对 URI 改写为指向 /api/video/part 的绝对地址。
     *
     * @param playlist  本地播放列表文件（主列表或子列表）
     * @param root      授权文件夹根目录（u 参数统一编码「根目录相对路径」，
     *                  part 接口据此定位文件并做防越界校验）
     * @param authority 请求 Host 头（ip:port，已由调用方校验合法性）
     * @param videoId   虚拟条目的 videoId（原样透传给 part 接口做文件夹定位）
     * @return 改写后的播放列表文本
     */
    fun rewritten(playlist: File, root: File, authority: String, videoId: String): String {
        val template = rewriteTemplate(playlist, root)
        return template.replace(PH_AUTHORITY, authority).replace(PH_VIDEO_ID, videoId)
    }

    /** 生成（并缓存）与 Host/videoId 无关的改写模板，Host 变化不击穿缓存 */
    private fun rewriteTemplate(playlist: File, root: File): String {
        val key = "${playlist.absolutePath}|${playlist.lastModified()}"
        rewriteCache[key]?.let { return it.second }
        val canonicalRoot = root.canonicalFile
        val baseDir = playlist.parentFile ?: canonicalRoot
        val sb = StringBuilder()
        for (raw in playlist.readLines(Charsets.UTF_8)) {
            val line = raw.trim()
            when {
                line.isEmpty() -> sb.append('\n')
                line.startsWith("#") ->
                    // 属性行：仅替换其中 URI="..."（KEY/MAP/MEDIA 等），其余属性原样保留。
                    // 注意 Regex.replace 的 lambda 替换的是「整个匹配」，必须把 URI=" 和
                    // 收尾引号补回去，否则 EXT-X-KEY 行会被破坏（播放器无法解析密钥）
                    sb.append(
                        URI_ATTR.replace(line) { m ->
                            partUrlFor(m.groupValues[1], baseDir, canonicalRoot)
                                ?.let { url -> "URI=\"$url\"" } ?: m.value
                        }
                    ).append('\n')
                else ->
                    // 分片/子列表行：整行替换为 part 绝对地址
                    sb.append(partUrlFor(line, baseDir, canonicalRoot) ?: line).append('\n')
            }
        }
        val template = sb.toString()
        if (rewriteCache.size > 128) rewriteCache.clear() // 简单容量上限，防极端场景无限增长
        rewriteCache[key] = playlist.lastModified() to template
        return template
    }

    /** 相对 URI → part 绝对地址；外部地址/越界引用返回 null（保持原样，part 接口会拦截） */
    private fun partUrlFor(uri: String, baseDir: File, canonicalRoot: File): String? {
        if (isAbsoluteHttp(uri)) return null
        val f = resolveScoped(uri, baseDir, canonicalRoot) ?: return null
        val rel = f.toRelativeString(canonicalRoot).replace('\\', '/')
        val u = Base64.getUrlEncoder().withoutPadding().encodeToString(rel.toByteArray(Charsets.UTF_8))
        return "http://$PH_AUTHORITY$PART_PATH$PH_VIDEO_ID&u=$u"
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /**
     * 解析相对 URI 并校验结果仍在 scope 内（防目录穿越，P0 安全）。
     * 解析顺序：原样 → URL 解码（下载器常对中文/空格百分号编码）→ 去查询串。
     * 外部 http(s) 地址返回 null（不属于本地完整影片的范畴）。
     */
    private fun resolveScoped(uri: String, base: File, scope: File): File? {
        if (isAbsoluteHttp(uri)) return null
        val f = resolveLocal(base, uri) ?: return null
        return if (isInside(f, scope)) f.canonicalFile else null
    }

    /** 相对 URI → 本地 File：原样 → URL 解码 → 去查询串，三级尝试；都不存在返回 null */
    private fun resolveLocal(baseDir: File, uri: String): File? {
        val direct = File(baseDir, uri)
        if (direct.isFile) return direct
        val decoded = runCatching { URLDecoder.decode(uri, Charsets.UTF_8.name()) }.getOrNull() ?: uri
        val viaDecode = File(baseDir, decoded)
        if (viaDecode.isFile) return viaDecode
        val noQuery = File(baseDir, decoded.substringBefore('?'))
        return if (noQuery.isFile) noQuery else null
    }

    /** 目标是否位于 scope 目录内部（含相等；Windows 大小写不敏感） */
    private fun isInside(f: File, scope: File): Boolean {
        val c = f.canonicalFile.path
        val s = scope.canonicalFile.path
        return c.equals(s, ignoreCase = true) || c.startsWith(s + File.separator, ignoreCase = true)
    }

    /** 是否为外部 http(s) 绝对地址 */
    private fun isAbsoluteHttp(uri: String): Boolean {
        val lower = uri.lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://")
    }
}
