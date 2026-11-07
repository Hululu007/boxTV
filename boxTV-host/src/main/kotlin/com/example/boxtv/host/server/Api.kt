package com.example.boxtv.host.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * API 统一响应模块（server 层）
 *
 * 对应细则 4.3 节「统一响应包裹」：
 * 成功：{ "code": 0, "data": { ... } }
 * 失败：{ "code": 40100, "message": "Token 无效或已过期" }
 */

/** 业务错误码定义（遵循细则 4.3 节；M4 修订新增 40901/40902） */
object ApiCodes {
    const val OK = 0                    // 成功
    const val BAD_REQUEST = 40000       // 参数错误
    const val UNAUTHORIZED = 40100      // Token 无效或已过期
    const val FORBIDDEN = 40300         // 路径越界（安全防护触发）
    const val NOT_FOUND = 40400         // 资源不存在（文件夹/文件）
    const val PAIR_FAILED = 40900       // 配对请求无效或已超时失效
    const val PAIR_PENDING = 40901      // 等待电脑端点击「允许配对」
    const val PAIR_REJECTED = 40902     // 电脑端已拒绝本次配对
    const val INTERNAL_ERROR = 50000    // 服务端内部错误（2026-11-07 转码功能新增）
}

/** 统一响应信封 */
@Serializable
data class ApiEnvelope(
    val code: Int,
    val data: JsonElement? = null,
    val message: String? = null
)

/** 响应成功（code=0） */
suspend fun ApplicationCall.respondOk(data: JsonElement? = null) {
    respond(HttpStatusCode.OK, ApiEnvelope(code = ApiCodes.OK, data = data))
}

/** 响应失败（携带 HTTP 状态码 + 业务码 + 中文错误信息） */
suspend fun ApplicationCall.respondError(
    status: HttpStatusCode,
    code: Int,
    message: String
) {
    respond(status, ApiEnvelope(code = code, message = message))
}
