package com.mirage.engine.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 解析服务商返回的错误描述。
 *
 * 抽出来是因为错误可能从两个完全不同的位置到达，而解析逻辑是同一套：
 * 一是请求整体失败时的响应体，二是请求成功、但错误混在内容流里送回来。
 * 两处各写一份的话，新增一种错误形状就要改两个地方，必然漏掉一个。
 *
 * 解析的原则是**绝不猜测**：认得的就归类，认不得的就原样带出，
 * 不做任何「看起来像是某某错误」的推断。错误分类会直接影响界面提示用户
 * 该去充值还是该去改配置，猜错的代价比留一个「未知」大得多。
 */
object ProviderErrorParser {

    /** 排查用文本的截断长度，避免把超长响应整段留在内存或日志里。 */
    const val MAX_DIAGNOSTIC_LENGTH: Int = 512

    private val json = Json { isLenient = true }

    /**
     * 解析混在内容流里的错误节点。
     *
     * 错误标识字段有时是数字（就是 HTTP 状态码），有时是字符串标签，两种都要认：
     * 是数字就按状态码归类，是字符串就原样带出，让展示层自己决定怎么说。
     */
    fun fromErrorNode(node: JsonElement, rawPayload: String): StreamError {
        val obj = node as? JsonObject
            ?: return StreamError(
                code = StreamErrorCode.Unknown,
                diagnostic = rawPayload.take(MAX_DIAGNOSTIC_LENGTH),
            )

        val diagnostic = obj.textOrNull("message") ?: rawPayload.take(MAX_DIAGNOSTIC_LENGTH)
        val numericCode = obj.numberOrNull("code")

        return if (numericCode != null) {
            StreamError.fromHttpStatus(
                status = numericCode,
                providerCode = obj.textOrNull("type"),
                diagnostic = diagnostic,
            )
        } else {
            StreamError(
                code = StreamErrorCode.Unknown,
                providerCode = obj.textOrNull("code") ?: obj.textOrNull("type"),
                diagnostic = diagnostic,
            )
        }
    }

    /**
     * 解析请求失败时的响应体。
     *
     * 分类以 HTTP 状态码为准 —— 它是可靠的，而响应体的结构各家都不一样。
     * 响应体在这里只用来补充服务商自己的标识和可读描述。
     *
     * @param body 可能为空（读不到），也可能根本不是结构化内容（比如网关返回的 HTML）。
     *   两种情况都不能让解析失败。
     */
    fun fromFailureBody(status: Int, body: String?): StreamError {
        val fallback = StreamError.fromHttpStatus(
            status = status,
            diagnostic = body?.take(MAX_DIAGNOSTIC_LENGTH),
        )
        if (body.isNullOrBlank()) return fallback

        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return fallback
        val errorObj = root["error"] as? JsonObject ?: return fallback

        return StreamError.fromHttpStatus(
            status = status,
            providerCode = errorObj.textOrNull("code") ?: errorObj.textOrNull("type"),
            diagnostic = errorObj.textOrNull("message") ?: body.take(MAX_DIAGNOSTIC_LENGTH),
        )
    }
}

// ---------------------------------------------------------------------------
// 取值辅助。外部内容的字段可能缺失、可能是空值、也可能类型不符，
// 每处手写判空和类型检查会让主逻辑看不清，漏一处就会在异常内容上抛异常。
// 统一原则：取不到就返回 null，绝不抛、绝不猜。
// ---------------------------------------------------------------------------

internal fun JsonObject.textOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.numberOrNull(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.decimalOrNull(key: String): Double? =
    (this[key] as? JsonPrimitive)?.doubleOrNull
