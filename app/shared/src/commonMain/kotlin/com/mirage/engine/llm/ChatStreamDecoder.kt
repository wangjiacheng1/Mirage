package com.mirage.engine.llm

import com.mirage.engine.llm.sse.SseFrame
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 把推送帧解码成语义事件。
 *
 * 这里是整条流式链路上最容易出错的一环，几条设计都是针对具体踩过的坑：
 *
 * 1. **收到停止原因不等于流结束。** 有的服务端会在带停止原因的那一帧之后，
 *    再补发一帧只装用量数据的帧，而且那一帧往往把停止原因又带了一遍。
 *    如果在第一次看到停止原因时就停止读取，用量和费用就永久丢失了。
 *    所以结束只认结束标记，停止原因只是记下来。
 *
 * 2. **用量数据要合并而不是替换。** 它可能分散在多帧里先后到达，
 *    后到的那帧常常只带其中几个字段。整体替换会把先拿到的数据抹掉。
 *
 * 3. **单帧解析失败不终止整条流。** 一次生成可能持续很久、包含上千帧，
 *    为一帧畸形负载丢掉全部已产出内容是不划算的。坏帧会上报，但流继续。
 *
 * 4. **字段名在不同服务商之间不一致**，尤其是思考过程那一项。
 *    这里按已知的几种命名依次尝试，而不是假定某一种。
 *
 * 实例是有状态的，一个实例只能解一条流。
 */
class ChatStreamDecoder {

    /** 流是否已经走到结束。为真之后不应再喂新帧。 */
    var isTerminated: Boolean = false
        private set

    /** 最后一次看到的停止原因。多次出现时后者覆盖前者。 */
    private var lastFinishReason: ParsedFinishReason? = null

    /** 跨帧累积合并出来的用量。 */
    private var usage: TokenUsage = TokenUsage()

    /** 实际承接请求的服务方标识，有的接入点会带。 */
    private var servingProvider: String? = null

    /**
     * 解码一帧。
     *
     * @return 这一帧产生的事件，可能为空（心跳、空负载、纯元数据帧都会是空）。
     */
    fun decode(frame: SseFrame): List<ChatStreamEvent> {
        if (isTerminated) return emptyList()

        val payload = frame.data.trim()
        if (payload.isEmpty()) return emptyList()

        // 结束标记。这是判断流正常走完的唯一依据
        if (payload == END_OF_STREAM_MARKER) {
            isTerminated = true
            return listOf(buildCompleted())
        }

        val root = runCatching { json.parseToJsonElement(payload) }
            .getOrNull() as? JsonObject
            ?: return listOf(
                ChatStreamEvent.Failed(
                    StreamError(
                        code = StreamErrorCode.MalformedPayload,
                        diagnostic = payload.take(ProviderErrorParser.MAX_DIAGNOSTIC_LENGTH),
                    ),
                ),
            )

        // 错误可能直接出现在流里，而不是以 HTTP 状态码的形式返回
        root["error"]?.let { errorNode ->
            return listOf(
                ChatStreamEvent.Failed(
                    ProviderErrorParser.fromErrorNode(errorNode, payload),
                ),
            )
        }

        val events = mutableListOf<ChatStreamEvent>()

        root.textOrNull("provider")?.let { servingProvider = it }
        root["usage"]?.let { usage = usage.mergeWith(parseUsage(it)) }

        (root["choices"] as? JsonArray)?.forEach { choiceNode ->
            val choice = choiceNode as? JsonObject ?: return@forEach
            collectDeltaEvents(choice, events)

            // 停止原因只记录，不终止读取
            choice.textOrNull("finish_reason")?.let { raw ->
                val parsed = ParsedFinishReason.parse(raw)
                lastFinishReason = parsed
                events += ChatStreamEvent.StopSignalled(parsed)
            }
        }

        return events
    }

    /**
     * 流读完后调用，补一个结束事件。
     *
     * 存在的意义是兜住「连接正常关闭但没收到结束标记」的情况。
     * 少了它，这种流会让调用方永远等不到结束，也拿不到已累积的用量。
     * 已经通过结束标记结束过的流再调用这个方法不会重复产生事件。
     */
    fun finish(): List<ChatStreamEvent> {
        if (isTerminated) return emptyList()
        isTerminated = true
        return listOf(buildCompleted())
    }

    private fun buildCompleted() = ChatStreamEvent.Completed(
        finishReason = lastFinishReason,
        usage = usage.withDerivedTotal(),
        servingProvider = servingProvider,
    )

    /** 从一个 choice 节点里取出正文、思考过程和工具调用分片。 */
    private fun collectDeltaEvents(choice: JsonObject, sink: MutableList<ChatStreamEvent>) {
        val delta = choice["delta"] as? JsonObject ?: return

        delta.textOrNull("content")
            ?.takeIf { it.isNotEmpty() }
            ?.let { sink += ChatStreamEvent.TextDelta(it) }

        // 思考过程的字段名各家不同，依次尝试已知的几种
        REASONING_FIELD_NAMES
            .firstNotNullOfOrNull { delta.textOrNull(it)?.takeIf(String::isNotEmpty) }
            ?.let { sink += ChatStreamEvent.ReasoningDelta(it) }

        (delta["tool_calls"] as? JsonArray)?.forEachIndexed { positionInFrame, callNode ->
            val call = callNode as? JsonObject ?: return@forEachIndexed
            val function = call["function"] as? JsonObject
            sink += ChatStreamEvent.ToolCallDelta(
                // 没给下标时退回它在本帧数组里的位置，保证归集键始终存在
                index = call.numberOrNull("index") ?: positionInFrame,
                callId = call.textOrNull("id"),
                name = function?.textOrNull("name"),
                argumentsFragment = function?.textOrNull("arguments").orEmpty(),
            )
        }
    }

    private fun parseUsage(node: JsonElement): TokenUsage {
        val obj = node as? JsonObject ?: return TokenUsage()
        val reasoningTokens = (obj["completion_tokens_details"] as? JsonObject)
            ?.numberOrNull("reasoning_tokens")
        return TokenUsage(
            promptTokens = obj.numberOrNull("prompt_tokens"),
            completionTokens = obj.numberOrNull("completion_tokens"),
            totalTokens = obj.numberOrNull("total_tokens"),
            reasoningTokens = reasoningTokens,
            cost = obj.decimalOrNull("cost"),
        )
    }

    private companion object {
        /** 流结束标记。 */
        const val END_OF_STREAM_MARKER = "[DONE]"

        /** 思考过程字段的几种已知命名，按出现频率排序。 */
        val REASONING_FIELD_NAMES = listOf("reasoning_content", "reasoning", "thinking")

        val json = Json { isLenient = true }
    }
}
