package com.example.boxtv.host.server

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.request.receive
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.http.ContentType
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.io.File
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import com.example.boxtv.host.core.AccessLogger
import com.example.boxtv.host.core.AuthorizedFolder
import com.example.boxtv.host.core.ConfigStore
import com.example.boxtv.host.core.DeviceRegistry
import com.example.boxtv.host.core.PairedDevice
import com.example.boxtv.host.core.PairingSessionManager
import com.example.boxtv.host.core.UpdateStore

/**
 * HTTP 服务模块（server 层）
 *
 * 基于 Ktor Netty 引擎，承载细则 4.3 节定义的全部 REST API：
 * - 健康检查 /ping（无需鉴权，用于手动连接验证）
 * - 配对 /pair/…（无需鉴权，电视端发起请求 + 电脑端允许后签发 Token）
 * - 资源访问 /folders、/browse、/search、/video/…（需 Token 鉴权）
 *
 * 视频流式播放通过 install(PartialContent) 实现 HTTP Range 支持，
 * 电视端 ExoPlayer 依赖 Range 请求实现秒开与拖动进度条。
 */
class HostServer(
    private val store: ConfigStore,
    private val registry: DeviceRegistry,
    private val pairing: PairingSessionManager,
    private val logger: AccessLogger,
    private val thumbs: ThumbGenerator,
    private val updates: UpdateStore
) {

    companion object {
        /** 电脑端软件版本（发现应答与 /api/ping 返回；2026-11-08 免输入配对重构升级至 1.2.2） */
        const val VERSION = "1.2.2"

        /** Host 头合法性（仅允许 ip/域名:port 的安全字符，防止注入改写播放列表的绝对地址） */
        private val hostPattern = Regex("""^[A-Za-z0-9.\-:\[\]]+$""")
    }

    /** JSON 编解码配置：输出默认值、省略 null 字段 */
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    /** 实时转码器（2026-11-07 播放器升级：清晰度切换的 ffmpeg 边转边播） */
    private val transcoder = Transcoder()

    /** M3U8 智能识别与聚合器（2026-11-07 M3U8 播放支持：有效文件夹识别 + 播放列表改写） */
    private val m3u8 = M3u8Aggregator()

    private var engine: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    /** 启动 HTTP 服务（绑定所有网卡的指定端口） */
    fun start(port: Int) {
        engine = embeddedServer(Netty, port = port, host = "0.0.0.0") { module() }
        engine?.start(wait = false)
    }

    /** 停止 HTTP 服务 */
    fun stop() {
        engine?.stop(gracePeriodMillis = 1000, timeoutMillis = 2000)
    }

    /**
     * 热重启服务（设置界面修改端口时调用）。
     * 绑定失败抛出异常，由调用方负责回滚到旧端口。
     */
    fun restart(newPort: Int) {
        stop()
        start(newPort)
    }

    /** Ktor 应用装配：插件安装 + 路由注册 */
    private fun Application.module() {
        // JSON 内容协商：API 响应自动序列化为 UTF-8 JSON（细则 7 编码要求）
        install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) {
            json(json)
        }
        // HTTP Range 分段传输：视频流支持 Range 请求（细则 4.3 节 stream 接口强制要求）
        install(PartialContent)

        routing {
            routePing()
            routePairing()
            routeFolders()
            routeBrowse()
            routeSearch()
            routeVideo()
            routeUpdate()
        }
    }

    // ------------------------------------------------------------------
    // 鉴权辅助
    // ------------------------------------------------------------------

    /**
     * Token 鉴权（对应细则 4.2/4.3 节）：
     * 从请求头 X-BoxTV-Token 中读取令牌并到设备注册表校验。
     * 校验失败直接回写 401/40100 响应并返回 null（调用方立即 return）。
     */
    private suspend fun ApplicationCall.requireDevice(): PairedDevice? {
        val token = request.headers["X-BoxTV-Token"]
        val device = token?.let { registry.findByToken(it) }
        if (device == null) {
            respondError(HttpStatusCode.Unauthorized, ApiCodes.UNAUTHORIZED, "Token 无效或已过期")
        }
        return device
    }

    /** 按 id 查找已授权文件夹（不存在返回 null） */
    private fun findFolder(folderId: String): AuthorizedFolder? {
        return store.peek().folders.firstOrNull { it.id == folderId }
    }

    /** 校验并返回请求 Host 头（作为改写播放列表里绝对地址的 authority）；非法返回 null */
    private fun sanitizeAuthority(hostHeader: String?): String? =
        hostHeader?.takeIf { it.length <= 255 && hostPattern.matches(it) }

    // ------------------------------------------------------------------
    // API 响应 DTO
    // ------------------------------------------------------------------

    /** 配对请求入参（电视端上报自身标识） */
    @Serializable
    data class PairRequestReq(
        val deviceUuid: String,
        val deviceName: String,
        val deviceModel: String = ""
    )

    /**
     * 配对验证入参（2026-11-08 配对机制重构：移除配对码，
     * 电视端仅凭 pairingId 轮询配对结果——是否允许完全由电脑端用户决定）
     */
    @Serializable
    data class PairVerifyReq(val pairingId: String)

    /** /api/ping 响应数据（hostDeviceId：M2 补充，电视端手动连接时获取主机唯一标识） */
    @Serializable
    data class PingData(
        val hostName: String,
        val version: String,
        val requiresPair: Boolean,
        val hostDeviceId: String = ""
    )

    /** /api/pair/request 响应数据（2026-11-08 重构：移除配对码，电视端收到后自动轮询等待允许） */
    @Serializable
    data class PairRequestData(val pairingId: String, val expiresAt: Long)

    /** /api/pair/verify 响应数据 */
    @Serializable
    data class PairVerifyData(val token: String, val hostName: String)

    /** 授权文件夹条目（电视端只看到 id 与名称，不暴露真实路径，减少信息泄露） */
    @Serializable
    data class FolderDto(val id: String, val name: String)

    /**
     * 目录条目（浏览/搜索共用，细则 4.3 节 browse 响应结构）
     * type: "dir"（子文件夹）| "video"（可播放视频文件）
     * format: "file"（普通视频文件）| "m3u8"（电脑端聚合的 M3U8 虚拟视频条目，
     *         2026-11-07 M3U8 播放支持新增；带默认值，旧版电视端忽略未知字段，向后兼容）
     */
    @Serializable
    data class EntryDto(
        val name: String,
        val type: String,
        val size: Long = 0,
        val modifiedAt: Long = 0,
        val videoId: String? = null,
        val format: String = "file"
    )

    /** 浏览/搜索响应数据 */
    @Serializable
    data class EntriesData(val entries: List<EntryDto>)

    /** /api/video/meta 响应数据（视频元数据：时长/源片分辨率，2026-11-07 转码功能新增） */
    @Serializable
    data class VideoMetaDto(val durationMs: Long, val width: Int, val height: Int)

    /**
     * /api/update/check 响应数据（2026-11-07 局域网更新功能新增）
     * available=false 表示电脑端未发布新版本；versionCode 供电视端与
     * 自身 PackageInfo.versionCode 比较判断是否有更新；md5 用于下载后完整性校验。
     */
    @Serializable
    data class UpdateCheckData(
        val available: Boolean,
        val versionCode: Long = 0,
        val versionName: String = "",
        val changelog: String = "",
        val size: Long = 0,
        val md5: String = ""
    )

    // ------------------------------------------------------------------
    // 路由实现
    // ------------------------------------------------------------------

    /** GET /api/ping —— 健康检查 / 手动连接验证；携带有效 Token 时 requiresPair=false */
    private fun io.ktor.server.routing.Route.routePing() {
        get("/api/ping") {
            val token = call.request.headers["X-BoxTV-Token"]
            val device = token?.let { registry.findByToken(it) }
            val cfg = store.peek()
            call.respondOk(
                json.encodeToJsonElement(
                    PingData(
                        hostName = cfg.hostName.ifBlank { "boxTV Host" },
                        version = VERSION,
                        requiresPair = device == null,
                        hostDeviceId = cfg.hostDeviceId
                    )
                )
            )
        }
    }

    /** POST /api/pair/request —— 电视端发起配对请求，电脑端弹出通知等待允许（300 秒有效） */
    private fun io.ktor.server.routing.Route.routePairing() {
        post("/api/pair/request") {
            val req = try {
                call.receive<PairRequestReq>()
            } catch (_: Exception) {
                return@post call.respondError(
                    HttpStatusCode.BadRequest, ApiCodes.BAD_REQUEST, "请求参数不合法"
                )
            }
            val s = pairing.begin(req.deviceUuid, req.deviceName, req.deviceModel)
            logger.log(req.deviceName.ifBlank { "未知设备" }, "配对", "发起配对请求，等待电脑端允许（5 分钟内有效）")
            // 控制台使用 ASCII 标记格式，避免 Windows GBK 终端中文乱码影响识别
            println("[boxTV] PAIR_REQUEST device=${req.deviceName} (expiresAt=${s.expiresAt})")
            // 电视端收到 pairingId 后自动轮询 /api/pair/verify 等待电脑端允许
            call.respondOk(
                json.encodeToJsonElement(PairRequestData(s.pairingId, s.expiresAt))
            )
        }

        post("/api/pair/verify") {
            val req = try {
                call.receive<PairVerifyReq>()
            } catch (_: Exception) {
                return@post call.respondError(
                    HttpStatusCode.BadRequest, ApiCodes.BAD_REQUEST, "请求参数不合法"
                )
            }
            // 验证结果区分「成功 / 等待允许 / 已拒绝 / 失败」四种语义（细则 6.2 允许/拒绝交互）
            when (val outcome = pairing.verify(req.pairingId)) {
                is PairingSessionManager.VerifyOutcome.Approved -> {
                    // 验证通过：注册设备并签发 Token（立即持久化到 config.json）
                    val s = outcome.session
                    val device = registry.registerDevice(s.deviceUuid, s.deviceName, s.deviceModel)
                    logger.log(device.name, "配对", "配对成功，已签发访问令牌")
                    println("[boxTV] PAIRED device=${device.name}")
                    call.respondOk(
                        json.encodeToJsonElement(
                            PairVerifyData(token = device.token, hostName = store.peek().hostName.ifBlank { "boxTV Host" })
                        )
                    )
                }
                PairingSessionManager.VerifyOutcome.Pending -> {
                    // 电脑端尚未点击「允许配对」：电视端自动轮询重试
                    logger.log("未知设备", "配对", "等待电脑端允许配对")
                    call.respondError(
                        HttpStatusCode.Conflict, ApiCodes.PAIR_PENDING, "等待电脑端确认（请在电脑端点击「允许配对」）"
                    )
                }
                PairingSessionManager.VerifyOutcome.Rejected -> {
                    // 电脑端已拒绝本次配对：电视端提示用户可重新发起
                    logger.log("未知设备", "配对", "电脑端已拒绝本次配对请求")
                    call.respondError(
                        HttpStatusCode.Forbidden, ApiCodes.PAIR_REJECTED, "电脑端已拒绝本次配对"
                    )
                }
                PairingSessionManager.VerifyOutcome.Failed -> {
                    logger.log("未知设备", "配对", "配对请求无效或已超时失效")
                    call.respondError(
                        HttpStatusCode.BadRequest, ApiCodes.PAIR_FAILED, "配对请求无效或已超时失效"
                    )
                }
            }
        }
    }

    /** GET /api/folders —— 返回已授权文件夹列表（只暴露 id 与名称） */
    private fun io.ktor.server.routing.Route.routeFolders() {
        get("/api/folders") {
            val device = call.requireDevice() ?: return@get
            val folders = store.peek().folders.map { FolderDto(it.id, it.name) }
            logger.log(device.name, "浏览", "获取授权文件夹列表（共 ${folders.size} 个）")
            call.respondOk(json.encodeToJsonElement(folders))
        }
    }

    /**
     * GET /api/browse?folderId=&path= —— 浏览目录
     * 返回子文件夹与视频文件；文件夹在前、视频在后，名称升序（细则 4.3 规则 4）
     */
    private fun io.ktor.server.routing.Route.routeBrowse() {
        get("/api/browse") {
            val device = call.requireDevice() ?: return@get
            val folderId = call.request.queryParameters["folderId"]
            val path = call.request.queryParameters["path"] ?: ""

            if (folderId.isNullOrBlank()) {
                return@get call.respondError(HttpStatusCode.BadRequest, ApiCodes.BAD_REQUEST, "缺少 folderId 参数")
            }
            val folder = findFolder(folderId)
                ?: return@get call.respondError(HttpStatusCode.NotFound, ApiCodes.NOT_FOUND, "授权文件夹不存在")

            // P0 安全：路径越界防护（细则 4.3 规则 2）
            val dir = PathGuard.resolve(folder.path, path)
                ?: return@get call.respondError(HttpStatusCode.Forbidden, ApiCodes.FORBIDDEN, "路径越界")
            if (!dir.exists() || !dir.isDirectory) {
                return@get call.respondError(HttpStatusCode.NotFound, ApiCodes.NOT_FOUND, "目录不存在")
            }

            val root = File(folder.path).canonicalFile

            // 目录自身是有效 M3U8 影片目录（授权根直指影片，或用户已进入
            // 某个分集/影片子目录）：仅返回聚合条目，内部变体子目录/零散分片不再罗列
            m3u8.detect(dir)?.let { pl ->
                val entry = EntryDto(
                    name = dir.name,
                    type = "video",
                    modifiedAt = dir.lastModified(),
                    videoId = VideoIdCodec.encode(folder.id, pl.toRelativeString(root).replace('\\', '/')),
                    format = "m3u8"
                )
                logger.log(
                    device.name, "浏览",
                    "${folder.name}${if (path.isNotBlank()) "/$path" else ""}（M3U8 影片目录聚合）"
                )
                return@get call.respondOk(json.encodeToJsonElement(EntriesData(listOf(entry))))
            }

            val entries = dir.listFiles()
                ?.mapNotNull { f ->
                    if (!f.isDirectory) return@mapNotNull entryFor(root, folder.id, f)
                    // 子文件夹：一律以普通目录条目展示（2026-11-07 层级修复）。
                    // 即使是有效 M3U8 影片目录，也不在本层聚合为视频条目——
                    // 用户进入该文件夹后，由上方 detect(dir) 分支聚合成单个影片条目，
                    // 严格保证「总目录 → 分集子目录 → M3U8 影片条目」三级层级，
                    // 杜绝父级列表越级渲染子目录内的影片内容。
                    // detect(f) 仅预热识别缓存，让后续进入子目录时零扫描延迟。
                    m3u8.detect(f)
                    EntryDto(name = f.name, type = "dir", modifiedAt = f.lastModified())
                }
                ?.sortedWith(compareBy({ it.type != "dir" }, { it.name.lowercase() }))
                ?: emptyList()

            logger.log(device.name, "浏览", "${folder.name}${if (path.isNotBlank()) "/$path" else ""}")
            call.respondOk(json.encodeToJsonElement(EntriesData(entries)))
        }
    }

    /**
     * GET /api/search?folderId=&q= —— 在授权文件夹内递归搜索视频文件
     * 结果上限 500 条，防止超大目录拖垮响应。
     */
    private fun io.ktor.server.routing.Route.routeSearch() {
        get("/api/search") {
            val device = call.requireDevice() ?: return@get
            val folderId = call.request.queryParameters["folderId"]
            val q = call.request.queryParameters["q"]?.trim() ?: ""

            if (folderId.isNullOrBlank() || q.isEmpty()) {
                return@get call.respondError(HttpStatusCode.BadRequest, ApiCodes.BAD_REQUEST, "缺少 folderId 或 q 参数")
            }
            val folder = findFolder(folderId)
                ?: return@get call.respondError(HttpStatusCode.NotFound, ApiCodes.NOT_FOUND, "授权文件夹不存在")

            val root = File(folder.path).canonicalFile

            // M3U8 聚合（2026-11-07）：递归中同步识别有效影片目录，
            // 已聚合目录的内部（变体子目录/分片）整棵剪枝，搜索结果只保留虚拟条目
            val claimed = HashSet<String>() // 已聚合影片目录（绝对路径）
            val m3u8Entries = mutableListOf<EntryDto>()
            val files = root.walkTopDown()
                .onEnter { d ->
                    if (d == root) return@onEnter true
                    // 已聚合影片目录的内部：剪枝，分片不再出现在搜索结果里
                    if (claimed.any { d.absolutePath.startsWith(it + File.separator, ignoreCase = true) }) {
                        return@onEnter false
                    }
                    if (d.name.contains(q, ignoreCase = true)) {
                        m3u8.detect(d)?.let { pl ->
                            claimed += d.absolutePath
                            m3u8Entries += EntryDto(
                                name = d.name,
                                type = "video",
                                modifiedAt = d.lastModified(),
                                videoId = VideoIdCodec.encode(folder.id, pl.toRelativeString(root).replace('\\', '/')),
                                format = "m3u8"
                            )
                        }
                    }
                    true
                }
                .filter { f ->
                    f.isFile && PathGuard.isVideoFile(f.name) &&
                        f.name.contains(q, ignoreCase = true) &&
                        // 已聚合影片目录内部的零散分片文件不参与搜索
                        claimed.none { f.absolutePath.startsWith(it + File.separator, ignoreCase = true) }
                }
                .toList()
            val results = (files.mapNotNull { entryFor(root, folder.id, it) } + m3u8Entries).take(500)

            logger.log(device.name, "搜索", "在「${folder.name}」中搜索 \"$q\"（命中 ${results.size} 条）")
            call.respondOk(json.encodeToJsonElement(EntriesData(results)))
        }
    }

    /**
     * GET /api/video/stream?videoId=&quality=&start= —— 视频流（2026-11-07 播放器升级）
     *
     * 清晰度协议（与电视端约定）：
     * - quality 缺省 / "1080p"：默认档。源片高度 ≤ 1080 → 原画直连（respondFile，
     *   PartialContent 自动处理 Range，秒开与拖动零开销）；源片 > 1080（如 4K）
     *   → 转码压到 1080p，避免电视端硬解不动或流量过大；
     * - "720p" / "480p"：ffmpeg 实时转码推流（fMP4 管道，无 Range），
     *   流量随清晰度显著下降，适配弱网环境；
     * - start：转码流的起播位置（秒，转码无法 Range 拖动，电视端以「重新起播」
     *   模型对接——拖动进度条时携带新 start 重新请求，旧转码进程即时回收）。
     */
    private fun io.ktor.server.routing.Route.routeVideo() {
        get("/api/video/stream") {
            val device = call.requireDevice() ?: return@get
            val videoId = call.request.queryParameters["videoId"]
            if (videoId.isNullOrBlank()) {
                return@get call.respondError(HttpStatusCode.BadRequest, ApiCodes.BAD_REQUEST, "缺少 videoId 参数")
            }

            // 解码 videoId → (folderId, 相对路径)
            val decoded = VideoIdCodec.decode(videoId)
                ?: return@get call.respondError(HttpStatusCode.BadRequest, ApiCodes.BAD_REQUEST, "videoId 非法")
            val (folderId, relativePath) = decoded
            val folder = findFolder(folderId)
                ?: return@get call.respondError(HttpStatusCode.NotFound, ApiCodes.NOT_FOUND, "授权文件夹不存在")

            // P0 安全：路径越界防护（细则 4.3 规则 2）
            val file = PathGuard.resolve(folder.path, relativePath)
                ?: return@get call.respondError(HttpStatusCode.Forbidden, ApiCodes.FORBIDDEN, "路径越界")
            if (!file.exists() || !file.isFile) {
                return@get call.respondError(HttpStatusCode.NotFound, ApiCodes.NOT_FOUND, "文件不存在")
            }
            val root = File(folder.path).canonicalFile

            // ---- M3U8 虚拟条目：HLS 播放列表原画直连（2026-11-07 M3U8 播放支持）----
            // 电脑端把列表内所有相对 URI 改写为 /api/video/part 绝对地址后整体下发；
            // HLS 原生流不支持转码清晰度档（与电视端确认的协议），quality/start 参数忽略
            if (file.extension.equals("m3u8", ignoreCase = true)) {
                val authority = sanitizeAuthority(call.request.headers["Host"])
                    ?: return@get call.respondError(HttpStatusCode.BadRequest, ApiCodes.BAD_REQUEST, "Host 头缺失或非法")
                logger.log(device.name, "播放", "${folder.name}/$relativePath（HLS 原画）")
                call.respondText(
                    m3u8.rewritten(file, root, authority, videoId),
                    ContentType.parse("application/vnd.apple.mpegurl")
                )
                return@get
            }

            // ---- 清晰度档位 → 目标转码高度（0 = 原画直连）----
            val startSec = call.request.queryParameters["start"]?.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
            val targetHeight = when (call.request.queryParameters["quality"]) {
                null, "", "1080p", "original" -> {
                    // 1080P 默认档：源片超高清（> 1080）才降码率转码，否则原画直连
                    val sourceHeight = transcoder.meta(videoId, file)?.height ?: 0
                    if (sourceHeight > 1080) 1080 else 0
                }
                "720p" -> 720
                "480p" -> 480
                else -> 0 // 未知档位容错：按原画直连处理
            }

            logger.log(device.name, "播放", "${folder.name}/$relativePath" + if (targetHeight > 0) "（转码 ${targetHeight}p）" else "（原画）")
            if (targetHeight > 0) {
                // 转码推流分支：同设备同视频的旧转码进程由 Transcoder 负责回收
                transcoder.respondTranscode(
                    call = call,
                    key = "$videoId|${device.deviceUuid}",
                    file = file,
                    targetHeight = targetHeight,
                    startSec = startSec
                )
            } else {
                // 原画直连分支：respondFile + PartialContent 插件自动处理 Range 分段请求；
                // Content-Type 由文件扩展名自动推断（ExoPlayer 对视频 MIME 类型不敏感，兼容 Octet-Stream）
                call.respondFile(file)
            }
        }

        /**
         * GET /api/video/part?videoId=&u= —— M3U8 分片/子列表/密钥下发（2026-11-07 M3U8 播放支持新增）
         *
         * u = Base64URL(授权根目录相对路径)，由电脑端改写播放列表时生成。
         * 电视端 HLS 播放器逐个请求分片，本接口只做「解码 + 防越界 + 发送」三类轻操作：
         * - .m3u8 子列表（变体/音轨）：递归改写后以 vnd.apple.mpegurl 下发，
         *   master→variant 任意层级天然支持；
         * - 分片/密钥等资源：原文件下发，PartialContent 插件自动处理 Range 请求。
         */
        get("/api/video/part") {
            val device = call.requireDevice() ?: return@get
            val videoId = call.request.queryParameters["videoId"]
            val u = call.request.queryParameters["u"]
            if (videoId.isNullOrBlank() || u.isNullOrBlank()) {
                return@get call.respondError(HttpStatusCode.BadRequest, ApiCodes.BAD_REQUEST, "缺少 videoId 或 u 参数")
            }
            // videoId 仅用于定位授权文件夹（u 已携带完整相对路径）
            val decoded = VideoIdCodec.decode(videoId)
                ?: return@get call.respondError(HttpStatusCode.BadRequest, ApiCodes.BAD_REQUEST, "videoId 非法")
            val (folderId, _) = decoded
            val folder = findFolder(folderId)
                ?: return@get call.respondError(HttpStatusCode.NotFound, ApiCodes.NOT_FOUND, "授权文件夹不存在")

            // u 解码为根目录相对路径，统一走 PathGuard 防越界（P0 安全，杜绝目录穿越）
            val rel = try {
                String(Base64.getUrlDecoder().decode(u), Charsets.UTF_8)
            } catch (_: Exception) {
                return@get call.respondError(HttpStatusCode.BadRequest, ApiCodes.BAD_REQUEST, "u 参数非法")
            }
            val file = PathGuard.resolve(folder.path, rel)
                ?: return@get call.respondError(HttpStatusCode.Forbidden, ApiCodes.FORBIDDEN, "路径越界")
            if (!file.exists() || !file.isFile) {
                return@get call.respondError(HttpStatusCode.NotFound, ApiCodes.NOT_FOUND, "分片不存在")
            }

            if (file.extension.equals("m3u8", ignoreCase = true)) {
                // 子播放列表：递归改写其中的相对 URI 后下发
                val authority = sanitizeAuthority(call.request.headers["Host"])
                    ?: return@get call.respondError(HttpStatusCode.BadRequest, ApiCodes.BAD_REQUEST, "Host 头缺失或非法")
                call.respondText(
                    m3u8.rewritten(file, File(folder.path).canonicalFile, authority, videoId),
                    ContentType.parse("application/vnd.apple.mpegurl")
                )
            } else {
                // 视频分片 / AES 密钥 / 初始化段：原样下发（Range 由 PartialContent 处理）
                call.respondFile(file)
            }
        }

        /**
         * GET /api/video/meta?videoId= —— 视频元数据（2026-11-07 转码功能新增）
         * 返回 ffprobe 探测的时长与源片分辨率（带缓存）。电视端用途：
         * - 转码流本身不携带总时长，以此补偿进度条与进度保存；
         * - 决定 1080P 档位是否需要转码（源片 > 1080p）。
         * 工具缺失/探测失败返回 404，电视端降级为原画直连。
         */
        get("/api/video/meta") {
            val device = call.requireDevice() ?: return@get
            val videoId = call.request.queryParameters["videoId"]
            if (videoId.isNullOrBlank()) {
                return@get call.respondError(HttpStatusCode.BadRequest, ApiCodes.BAD_REQUEST, "缺少 videoId 参数")
            }
            val decoded = VideoIdCodec.decode(videoId)
                ?: return@get call.respondError(HttpStatusCode.BadRequest, ApiCodes.BAD_REQUEST, "videoId 非法")
            val (folderId, relativePath) = decoded
            val folder = findFolder(folderId)
                ?: return@get call.respondError(HttpStatusCode.NotFound, ApiCodes.NOT_FOUND, "授权文件夹不存在")
            val file = PathGuard.resolve(folder.path, relativePath)
                ?: return@get call.respondError(HttpStatusCode.Forbidden, ApiCodes.FORBIDDEN, "路径越界")
            if (!file.exists() || !file.isFile) {
                return@get call.respondError(HttpStatusCode.NotFound, ApiCodes.NOT_FOUND, "文件不存在")
            }

            val meta = transcoder.meta(videoId, file)
            if (meta == null) {
                return@get call.respondError(HttpStatusCode.NotFound, ApiCodes.NOT_FOUND, "无法读取视频元数据")
            }
            logger.log(device.name, "播放", "元数据 ${folder.name}/$relativePath（${meta.width}x${meta.height}）")
            call.respondOk(
                json.encodeToJsonElement(VideoMetaDto(meta.durationMs, meta.width, meta.height))
            )
        }

        /**
         * GET /api/video/thumb?videoId= —— 视频缩略图（M4 接入 ffmpeg，细则 4.4 节）
         * - 缓存命中直接返回 JPEG；
         * - 未命中现场生成（ffprobe 读时长 → ffmpeg 提取 10% 处帧 → 480px JPEG）；
         * - 工具缺失/生成失败统一 404 降级，电视端显示占位图标。
         */
        get("/api/video/thumb") {
            val device = call.requireDevice() ?: return@get
            val videoId = call.request.queryParameters["videoId"]
            if (videoId.isNullOrBlank()) {
                return@get call.respondError(HttpStatusCode.BadRequest, ApiCodes.BAD_REQUEST, "缺少 videoId 参数")
            }
            val decoded = VideoIdCodec.decode(videoId)
                ?: return@get call.respondError(HttpStatusCode.BadRequest, ApiCodes.BAD_REQUEST, "videoId 非法")
            val (folderId, relativePath) = decoded
            val folder = findFolder(folderId)
                ?: return@get call.respondError(HttpStatusCode.NotFound, ApiCodes.NOT_FOUND, "授权文件夹不存在")
            val file = PathGuard.resolve(folder.path, relativePath)
                ?: return@get call.respondError(HttpStatusCode.Forbidden, ApiCodes.FORBIDDEN, "路径越界")
            if (!file.exists() || !file.isFile) {
                return@get call.respondError(HttpStatusCode.NotFound, ApiCodes.NOT_FOUND, "文件不存在")
            }

            // 现场生成或缓存命中（生成失败/工具缺失 → null → 降级 404）
            val thumb = thumbs.getOrGenerate(videoId, file)
            if (thumb == null) {
                return@get call.respondError(HttpStatusCode.NotFound, ApiCodes.NOT_FOUND, "缩略图不可用")
            }
            logger.log(device.name, "缩略图", "${folder.name}/$relativePath")
            call.respondBytes(thumb.readBytes(), ContentType.Image.JPEG)
        }
    }

    // ------------------------------------------------------------------
    // 局域网应用更新（2026-11-07 局域网更新功能）
    // ------------------------------------------------------------------

    /**
     * 电视端应用更新接口（2026-11-07 新增，全程局域网、无公网依赖）：
     * - GET /api/update/check    —— 查询电脑端发布的最新版本（需 Token）；
     * - GET /api/update/download —— 下载已发布的 APK 安装包（需 Token）。
     *   下载走 respondFile + PartialContent 插件：电视端断点续传（Range 头）
     *   由插件自动处理，与视频流共用同一套 Range 能力。
     */
    private fun io.ktor.server.routing.Route.routeUpdate() {
        get("/api/update/check") {
            val device = call.requireDevice() ?: return@get
            val m = updates.meta.value
            logger.log(
                device.name, "更新检查",
                if (m == null) "检查更新（当前未发布新版本）" else "检查更新（已发布 v${m.versionName}）"
            )
            call.respondOk(
                json.encodeToJsonElement(
                    if (m == null) UpdateCheckData(available = false)
                    else UpdateCheckData(
                        available = true,
                        versionCode = m.versionCode,
                        versionName = m.versionName,
                        changelog = m.changelog,
                        size = m.size,
                        md5 = m.md5
                    )
                )
            )
        }

        get("/api/update/download") {
            val device = call.requireDevice() ?: return@get
            val m = updates.meta.value
                ?: return@get call.respondError(
                    HttpStatusCode.NotFound, ApiCodes.NOT_FOUND, "当前没有已发布的更新版本"
                )
            val apk = updates.apk
            if (!apk.exists() || !apk.isFile) {
                return@get call.respondError(
                    HttpStatusCode.NotFound, ApiCodes.NOT_FOUND, "安装包文件缺失，请在电脑端重新发布"
                )
            }
            logger.log(device.name, "更新下载", "下载 v${m.versionName} 安装包（${apk.length() / 1024 / 1024}MB）")
            // Range 断点续传由 PartialContent 插件自动协商（206 / Content-Range）
            call.respondFile(apk)
        }
    }

    // ------------------------------------------------------------------
    // 目录条目构建
    // ------------------------------------------------------------------

    /**
     * 将本地文件构建为响应条目 DTO：
     * - 子目录 → type="dir"，不携带 videoId
     * - 白名单视频文件 → type="video"，携带 videoId（细则 4.3 规则 1）
     * - 其他文件（如字幕、图片、nfo）→ 过滤不返回
     */
    private fun entryFor(root: File, folderId: String, f: File): EntryDto? {
        val rel = f.toRelativeString(root).replace('\\', '/')
        return when {
            f.isDirectory -> EntryDto(name = f.name, type = "dir", modifiedAt = f.lastModified())
            PathGuard.isVideoFile(f.name) -> EntryDto(
                name = f.name,
                type = "video",
                size = f.length(),
                modifiedAt = f.lastModified(),
                videoId = VideoIdCodec.encode(folderId, rel)
            )
            else -> null
        }
    }
}
