package com.example.boxtv.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import com.example.boxtv.model.Folder
import com.example.boxtv.model.VideoEntry
import com.example.boxtv.model.VideoMeta

/**
 * HTTP API 客户端（data/remote 层）
 *
 * 对应细则 4.3 节 REST API 全表的电视端封装：
 * - Ktor Client（CIO 引擎），API 超时 10 秒（细则 7 要求）；
 * - 统一信封 {code, data, message} 解析，业务码 40100 表示 Token 失效需重新配对；
 * - 所有查询参数统一 UTF-8 URL 编码（中文路径，细则 7 编码要求）。
 */

/** 统一响应信封（与电脑端 ApiEnvelope 对应） */
@Serializable
data class ApiEnvelope(
    val code: Int = -1,
    val data: JsonElement? = null,
    val message: String? = null
)

/** 业务错误码（与电脑端 ApiCodes 对应；M4 修订新增 40901/40902） */
object ApiCodes {
    const val OK = 0
    const val UNAUTHORIZED = 40100   // Token 无效或已过期 → 需重新配对
    const val FORBIDDEN = 40300
    const val NOT_FOUND = 40400
    const val PAIR_FAILED = 40900    // 配对请求无效或已超时失效
    const val PAIR_PENDING = 40901   // 等待电脑端点击「允许配对」（电视端自动轮询）
    const val PAIR_REJECTED = 40902  // 电脑端已拒绝本次配对
}

/** API 调用结果封装：UI 层据此区分成功 / 授权失效 / 配对失败 / 网络错误 */
sealed class ApiResult<out T> {
    data class Ok<T>(val data: T) : ApiResult<T>()
    data class ApiError(val code: Int, val message: String) : ApiResult<Nothing>()
    data class NetworkError(val reason: String) : ApiResult<Nothing>()

    val isUnauthorized: Boolean get() = this is ApiError && code == ApiCodes.UNAUTHORIZED
}

// ------------------------------------------------------------------
// 各接口的 data 载荷 DTO（与电脑端响应结构一一对应）
// ------------------------------------------------------------------

/** /api/ping 响应数据 */
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

/** 文件夹 DTO */
@Serializable
data class FolderDto(val id: String, val name: String)

/** 目录条目 DTO（format：M3U8 聚合条目标记，2026-11-07 新增；默认值兼容旧电脑端） */
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
data class EntriesData(val entries: List<EntryDto> = emptyList())

/**
 * /api/update/check 响应数据（2026-11-07 局域网更新功能）。
 * available=false 表示电脑端未发布新版本；versionCode 与电视端自身
 * PackageInfo 的 versionCode 比较判断是否有更新；md5 用于下载后完整性校验。
 */
@Serializable
data class UpdateCheckData(
    val available: Boolean = false,
    val versionCode: Long = 0,
    val versionName: String = "",
    val changelog: String = "",
    val size: Long = 0,
    val md5: String = ""
)

// ------------------------------------------------------------------
// 配对请求体
// ------------------------------------------------------------------

/** 发起配对请求体 */
@Serializable
data class PairRequestReq(
    val deviceUuid: String,
    val deviceName: String,
    val deviceModel: String
)

/**
 * 验证配对请求体（2026-11-08 配对机制重构：移除配对码，
 * 电视端仅凭 pairingId 轮询配对结果——是否允许完全由电脑端用户决定）
 */
@Serializable
data class PairVerifyReq(val pairingId: String)

/**
 * HostApi：电视端访问电脑端的全部 HTTP 接口封装
 */
class HostApi {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val client = HttpClient(io.ktor.client.engine.cio.CIO) {
        // API 超时 10 秒（细则 7 非功能要求）
        install(HttpTimeout) {
            requestTimeoutMillis = 10_000
            connectTimeoutMillis = 5_000
        }
        install(ContentNegotiation) { json(json) }
    }

    /** GET /api/ping —— 健康检查 / 手动连接验证（无需鉴权） */
    suspend fun ping(host: String, port: Int): ApiResult<PingData> =
        execute("http://$host:$port/api/ping", token = null)

    /** POST /api/pair/request —— 发起配对 */
    suspend fun pairRequest(
        host: String,
        port: Int,
        deviceUuid: String,
        deviceName: String,
        deviceModel: String
    ): ApiResult<PairRequestData> =
        postRequest("http://$host:$port/api/pair/request", PairRequestReq(deviceUuid, deviceName, deviceModel))

    /** POST /api/pair/verify —— 轮询配对结果（电脑端允许后返回签发的 Token） */
    suspend fun pairVerify(
        host: String,
        port: Int,
        pairingId: String
    ): ApiResult<PairVerifyData> =
        postRequest("http://$host:$port/api/pair/verify", PairVerifyReq(pairingId))

    /** GET /api/folders —— 已授权文件夹列表（需 Token） */
    suspend fun folders(host: String, port: Int, token: String): ApiResult<List<Folder>> =
        when (val r = getListRequest<List<FolderDto>>("http://$host:$port/api/folders", token)) {
            is ApiResult.Ok -> ApiResult.Ok(r.data.map { Folder(it.id, it.name) })
            is ApiResult.ApiError -> r
            is ApiResult.NetworkError -> r
        }

    /** GET /api/browse —— 浏览目录（需 Token；path 为相对路径，统一 URL 编码） */
    suspend fun browse(
        host: String,
        port: Int,
        token: String,
        folderId: String,
        path: String
    ): ApiResult<List<VideoEntry>> =
        mapEntries(
            getListRequest<EntriesData>(
                "http://$host:$port/api/browse?folderId=${folderId.encodeURLParameter()}&path=${path.encodeURLParameter()}",
                token
            )
        )

    /** GET /api/search —— 递归搜索视频文件（需 Token） */
    suspend fun search(
        host: String,
        port: Int,
        token: String,
        folderId: String,
        query: String
    ): ApiResult<List<VideoEntry>> =
        mapEntries(
            getListRequest<EntriesData>(
                "http://$host:$port/api/search?folderId=${folderId.encodeURLParameter()}&q=${query.encodeURLParameter()}",
                token
            )
        )

    /**
     * 构造视频流地址（M3 播放使用；ExoPlayer 请求时需携带 X-BoxTV-Token 头）。
     * 2026-11-07 播放器升级扩展：
     * - quality：清晰度档位（"1080p"=原画直连[默认] / "720p" / "480p"=电脑端实时转码），
     *   传 null 时电脑端按 1080p 处理；
     * - startSec：转码流起播位置（秒）。转码流无法 Range 拖动，电视端以
     *   「重新起播」模型对接（拖动进度条/切清晰度时携带新 start 重新请求）。
     */
    fun streamUrl(host: String, port: Int, videoId: String, quality: String? = null, startSec: Double? = null): String {
        val sb = StringBuilder("http://$host:$port/api/video/stream?videoId=${videoId.encodeURLParameter()}")
        quality?.let { sb.append("&quality=").append(it.encodeURLParameter()) }
        startSec?.let { sb.append("&start=").append("%.3f".format(it)) }
        return sb.toString()
    }

    /** GET /api/video/meta —— 视频元数据（时长/分辨率；2026-11-07 转码功能配套） */
    suspend fun videoMeta(host: String, port: Int, token: String, videoId: String): ApiResult<VideoMeta> =
        execute("http://$host:$port/api/video/meta?videoId=${videoId.encodeURLParameter()}", token)

    /**
     * GET /api/update/check —— 检查电脑端发布的电视端新版本
     * （2026-11-07 局域网更新功能；需 Token，返回 available/versionCode/更新说明等）
     */
    suspend fun checkUpdate(host: String, port: Int, token: String): ApiResult<UpdateCheckData> =
        execute("http://$host:$port/api/update/check", token)

    /** 构造更新安装包下载地址（下载由 UpdateManager 流式执行，携带 Token 头） */
    fun updateDownloadUrl(host: String, port: Int): String =
        "http://$host:$port/api/update/download"

    /** 构造缩略图地址（M4 使用；404 时降级占位图） */
    fun thumbUrl(host: String, port: Int, videoId: String): String =
        "http://$host:$port/api/video/thumb?videoId=${videoId.encodeURLParameter()}"

    /** EntryDto → 展示模型 */
    private fun EntryDto.toModel() = VideoEntry(
        name = name,
        type = type,
        size = size,
        modifiedAt = modifiedAt,
        videoId = videoId,
        format = format
    )

    // ------------------------------------------------------------------
    // 内部：统一请求与信封解析
    // ------------------------------------------------------------------

    /** GET 请求（单对象响应）：信封 → data 解码为 T。
     *  2026-11-09 埋点：业务错误（含 40400）与网络异常统一落 logcat（tag: boxtv-api），
     *  偶现问题可通过 adb logcat 直接回溯失败接口与错误码。 */
    private suspend inline fun <reified T : Any> execute(url: String, token: String?): ApiResult<T> =
        runCatching {
            val envelope = client.get(url) {
                token?.let { header("X-BoxTV-Token", it) }
            }.body<ApiEnvelope>()
            logIfApiError(url, envelope.toResult { json.decodeFromJsonElement<T>(it) })
        }.getOrElse {
            android.util.Log.w("boxtv-api", "网络异常 $url: ${it.javaClass.simpleName}: ${it.message}")
            ApiResult.NetworkError(it.message ?: "网络请求失败")
        }

    /** POST 请求（单对象响应）：请求体 JSON 序列化 + 信封解析 */
    private suspend inline fun <reified T : Any> postRequest(url: String, body: Any): ApiResult<T> =
        runCatching {
            val envelope = client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }.body<ApiEnvelope>()
            logIfApiError(url, envelope.toResult { json.decodeFromJsonElement<T>(it) })
        }.getOrElse {
            android.util.Log.w("boxtv-api", "网络异常 $url: ${it.javaClass.simpleName}: ${it.message}")
            ApiResult.NetworkError(it.message ?: "网络请求失败")
        }

    /** GET 请求（单对象响应）：信封 → data 解码为 D */
    private suspend inline fun <reified D : Any> getListRequest(url: String, token: String): ApiResult<D> =
        runCatching {
            val envelope = client.get(url) {
                header("X-BoxTV-Token", token)
            }.body<ApiEnvelope>()
            logIfApiError(url, envelope.toResult { json.decodeFromJsonElement<D>(it) })
        }.getOrElse {
            android.util.Log.w("boxtv-api", "网络异常 $url: ${it.javaClass.simpleName}: ${it.message}")
            ApiResult.NetworkError(it.message ?: "网络请求失败")
        }

    /** 业务错误统一埋点：40400/40100 等错误码 + 完整 URL 落 logcat，成功静默 */
    private fun <T> logIfApiError(url: String, result: ApiResult<T>): ApiResult<T> {
        if (result is ApiResult.ApiError) {
            android.util.Log.w("boxtv-api", "接口错误 $url -> code=${result.code} message=${result.message}")
        }
        return result
    }

    /** EntriesData 响应 → 领域模型列表（错误码原样透传） */
    private fun mapEntries(r: ApiResult<EntriesData>): ApiResult<List<VideoEntry>> = when (r) {
        is ApiResult.Ok -> ApiResult.Ok(r.data.entries.map { it.toModel() })
        is ApiResult.ApiError -> r
        is ApiResult.NetworkError -> r
    }

    /** 信封解包：code=0 → Ok（data 解码）；否则 ApiError（40100 由 UI 层识别后引导重新配对） */
    private inline fun <T> ApiEnvelope.toResult(decode: (JsonElement) -> T): ApiResult<T> {
        return if (code == ApiCodes.OK && data != null) {
            ApiResult.Ok(decode(data))
        } else {
            ApiResult.ApiError(code, message ?: "未知错误")
        }
    }
}
