package com.mirage.engine.llm

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 把调用参数和接入点配置组装成请求体。
 *
 * 直接构造 JSON 结构而不是用可序列化的数据类，是为了精确控制**哪些键会出现**。
 * 用数据类的话，「不发某个键」依赖序列化配置去省略空值，
 * 这个行为既容易被全局配置改掉，也难以在测试里断言 ——
 * 而「某个键在不在」恰恰是这里最关键、出错代价最高的事。
 *
 * 组装规则按接入点配置分支，所有差异都收敛在这一个地方。
 * 散到各处的话，新增一家服务商就要改好几处，必然漏。
 */
object ChatRequestBodyBuilder {

    /**
     * 组装请求体。
     *
     * 会先做发送前自检，不通过直接抛 —— 宁可在本地立刻失败，
     * 也不要把一个拼错的请求发出去等服务端返回一个笼统的错误。
     */
    fun build(profile: ProviderProfile, request: ChatCompletionRequest): JsonObject {
        request.requireWellFormed()

        return buildJsonObject {
            put("model", request.modelId)

            putJsonArray("messages") {
                request.messages.forEach { message ->
                    add(
                        buildJsonObject {
                            put("role", message.role.wireValue())
                            put("content", message.content)
                        },
                    )
                }
            }

            put("stream", request.stream)

            // 以下几项只在显式给了值时才出现，为空代表「用服务商默认」
            request.temperature?.let { put("temperature", it) }
            request.topP?.let { put("top_p", it) }
            request.maxOutputTokens?.let { put("max_tokens", it) }

            putReasoningControl(profile, request.reasoningEnabled)
            putUsageRequest(profile, request.stream)
        }
    }

    /**
     * 写入思考过程的控制参数。
     *
     * 支持的情况下**无论开还是关都会把键写出去**，不做「关就不写」的省略。
     * 因为多数服务商的默认状态是开启，省略等于保持开启 ——
     * 那正是我们想避免的、开销最高的状态。显式写出来才能保证行为确定。
     */
    private fun JsonObjectBuilder.putReasoningControl(
        profile: ProviderProfile,
        enabled: Boolean,
    ) {
        when (profile.reasoningControl) {
            // 不知道对方的参数形状时什么都不发：发一个对方不认识的键
            // 有被整个请求拒绝的风险，代价比「思考没关掉」更大
            ReasoningControl.Unsupported -> Unit

            ReasoningControl.ThinkingTypeObject -> putJsonObject("thinking") {
                put("type", if (enabled) "enabled" else "disabled")
            }

            ReasoningControl.ReasoningEnabledObject -> putJsonObject("reasoning") {
                put("enabled", enabled)
            }
        }
    }

    /**
     * 写入索要用量数据的参数。
     *
     * 只在流式时有意义：非流式响应本来就会把用量放在结果里。
     */
    private fun JsonObjectBuilder.putUsageRequest(
        profile: ProviderProfile,
        stream: Boolean,
    ) {
        if (!stream) return
        when (profile.usageRequestStyle) {
            UsageRequestStyle.NotRequested -> Unit

            UsageRequestStyle.StreamOptionsFlag -> putJsonObject("stream_options") {
                put("include_usage", true)
            }

            UsageRequestStyle.UsageIncludeObject -> putJsonObject("usage") {
                put("include", true)
            }
        }
    }
}
