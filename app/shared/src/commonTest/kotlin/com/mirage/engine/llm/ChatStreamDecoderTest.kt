package com.mirage.engine.llm

import com.mirage.engine.llm.sse.SseFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 解码器的测试。
 *
 * 负载样本的形状来自对真实服务商响应的观察，不是凭想象构造的 ——
 * 几个最关键的分支（停止原因出现两次、用量分帧到达、工具参数分片数量差异大）
 * 都是只在真实响应里才会暴露的，自己编的样本一个都碰不到。
 */
class ChatStreamDecoderTest {

    private fun frame(data: String) = SseFrame(data = data)

    /** 把多帧依次喂进去，收集全部事件，便于按序断言。 */
    private fun ChatStreamDecoder.decodeAll(vararg payloads: String): List<ChatStreamEvent> =
        payloads.flatMap { decode(frame(it)) }

    // ------------------------------------------------------------------
    // 正文
    // ------------------------------------------------------------------

    @Test
    fun 正文分片应按序产出() {
        val decoder = ChatStreamDecoder()
        val events = decoder.decodeAll(
            """{"choices":[{"delta":{"content":"你"}}]}""",
            """{"choices":[{"delta":{"content":"好"}}]}""",
        )
        assertEquals(
            listOf("你", "好"),
            events.filterIsInstance<ChatStreamEvent.TextDelta>().map { it.text },
        )
    }

    @Test
    fun 正文为空字符串不应产出事件() {
        // 首帧常常只用来宣告角色，正文是空串。放过去会让界面收到一堆无意义刷新
        val decoder = ChatStreamDecoder()
        val events = decoder.decodeAll("""{"choices":[{"delta":{"role":"assistant","content":""}}]}""")
        assertTrue(events.isEmpty())
    }

    @Test
    fun 正文为空值不应产出事件也不应崩() {
        val decoder = ChatStreamDecoder()
        val events = decoder.decodeAll("""{"choices":[{"delta":{"content":null}}]}""")
        assertTrue(events.isEmpty())
    }

    // ------------------------------------------------------------------
    // 结束判定
    // ------------------------------------------------------------------

    @Test
    fun 结束标记应产出结束事件并标记终止() {
        val decoder = ChatStreamDecoder()
        val events = decoder.decodeAll("[DONE]")
        assertIs<ChatStreamEvent.Completed>(events.single())
        assertTrue(decoder.isTerminated)
    }

    @Test
    fun 收到停止原因不应终止流() {
        // 这是最关键的一条：停止原因之后还可能有携带用量的帧
        val decoder = ChatStreamDecoder()
        decoder.decodeAll("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""")
        assertFalse(
            decoder.isTerminated,
            "在第一个停止原因处就终止，会把后面携带用量和费用的帧全部丢掉",
        )
    }

    @Test
    fun 停止原因出现两次且用量在第二次时必须拿到用量() {
        // 真实场景：先来一帧带停止原因，再来一帧把停止原因重复一遍并附上用量
        val decoder = ChatStreamDecoder()
        val events = decoder.decodeAll(
            """{"choices":[{"delta":{"content":"完"},"finish_reason":"stop"}]}""",
            """{"choices":[{"delta":{},"finish_reason":"stop"}],""" +
                """"usage":{"prompt_tokens":11,"completion_tokens":22,"cost":0.00031}}""",
            "[DONE]",
        )

        val stops = events.filterIsInstance<ChatStreamEvent.StopSignalled>()
        assertEquals(2, stops.size, "两次停止原因都应如实上报")

        val completed = events.filterIsInstance<ChatStreamEvent.Completed>().single()
        assertEquals(FinishReason.Stop, completed.finishReason?.reason)
        assertEquals(11, completed.usage.promptTokens)
        assertEquals(22, completed.usage.completionTokens)
        assertEquals(0.00031, completed.usage.cost)
        assertEquals(33, completed.usage.totalTokens, "服务端没给总数时应由两侧相加补出")
    }

    @Test
    fun 没有结束标记时收尾应补出结束事件() {
        // 兜住「连接正常关闭但没发结束标记」的情况，否则调用方永远等不到结束
        val decoder = ChatStreamDecoder()
        decoder.decodeAll("""{"choices":[{"delta":{"content":"x"}}]}""")
        val completed = decoder.finish().single()
        assertIs<ChatStreamEvent.Completed>(completed)
        assertTrue(decoder.isTerminated)
        assertTrue(decoder.finish().isEmpty(), "补交过一次之后不该重复产出")
    }

    @Test
    fun 终止之后喂入的帧应被忽略() {
        val decoder = ChatStreamDecoder()
        decoder.decodeAll("[DONE]")
        assertTrue(decoder.decodeAll("""{"choices":[{"delta":{"content":"迟到"}}]}""").isEmpty())
    }

    // ------------------------------------------------------------------
    // 用量
    // ------------------------------------------------------------------

    @Test
    fun 用量分散在多帧时应合并而不是覆盖() {
        val decoder = ChatStreamDecoder()
        val events = decoder.decodeAll(
            """{"usage":{"prompt_tokens":100}}""",
            """{"usage":{"completion_tokens":200}}""",
            "[DONE]",
        )
        val usage = events.filterIsInstance<ChatStreamEvent.Completed>().single().usage
        assertEquals(100, usage.promptTokens, "后到的帧只带部分字段，不能把先前的数据抹掉")
        assertEquals(200, usage.completionTokens)
    }

    @Test
    fun 思考消耗应单独取出() {
        val decoder = ChatStreamDecoder()
        val events = decoder.decodeAll(
            """{"usage":{"completion_tokens":891,""" +
                """"completion_tokens_details":{"reasoning_tokens":859}}}""",
            "[DONE]",
        )
        val usage = events.filterIsInstance<ChatStreamEvent.Completed>().single().usage
        assertEquals(859, usage.reasoningTokens)
    }

    @Test
    fun 服务端给了总数就不再自行相加() {
        val decoder = ChatStreamDecoder()
        val events = decoder.decodeAll(
            """{"usage":{"prompt_tokens":10,"completion_tokens":20,"total_tokens":99}}""",
            "[DONE]",
        )
        val usage = events.filterIsInstance<ChatStreamEvent.Completed>().single().usage
        assertEquals(99, usage.totalTokens, "服务端口径优先，我们不替它算")
    }

    @Test
    fun 完全没有用量时应为空而不是一堆零() {
        // 「没拿到数据」和「真的是 0」在展示费用时含义完全不同，必须能区分
        val decoder = ChatStreamDecoder()
        val events = decoder.decodeAll("""{"choices":[{"delta":{"content":"x"}}]}""", "[DONE]")
        val usage = events.filterIsInstance<ChatStreamEvent.Completed>().single().usage
        assertTrue(usage.isEmpty)
        assertNull(usage.totalTokens)
    }

    // ------------------------------------------------------------------
    // 思考过程
    // ------------------------------------------------------------------

    @Test
    fun 三种思考字段命名都应认得() {
        listOf("reasoning_content", "reasoning", "thinking").forEach { field ->
            val decoder = ChatStreamDecoder()
            val events = decoder.decodeAll("""{"choices":[{"delta":{"$field":"想一下"}}]}""")
            val delta = events.filterIsInstance<ChatStreamEvent.ReasoningDelta>().singleOrNull()
            assertNotNull(delta, "字段名为 $field 时应识别为思考过程")
            assertEquals("想一下", delta.text)
        }
    }

    @Test
    fun 思考过程不应混进正文() {
        val decoder = ChatStreamDecoder()
        val events = decoder.decodeAll("""{"choices":[{"delta":{"reasoning_content":"推理"}}]}""")
        assertTrue(
            events.filterIsInstance<ChatStreamEvent.TextDelta>().isEmpty(),
            "思考过程混进正文会直接显示给用户看",
        )
    }

    // ------------------------------------------------------------------
    // 工具调用
    // ------------------------------------------------------------------

    @Test
    fun 工具名只在首片出现时应被取到而后续片仍能归集() {
        val decoder = ChatStreamDecoder()
        val events = decoder.decodeAll(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1",""" +
                """"function":{"name":"submit","arguments":"{\"a\""}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,""" +
                """"function":{"arguments":":1}"}}]}}]}""",
        )
        val calls = events.filterIsInstance<ChatStreamEvent.ToolCallDelta>()
        assertEquals(2, calls.size)
        assertEquals("submit", calls[0].name)
        assertEquals("c1", calls[0].callId)
        assertNull(calls[1].name, "后续分片本来就不带名字，不该凭空补一个")
        assertEquals(0, calls[1].index, "靠下标才能把分片归集到同一次调用上")
        assertEquals(
            """{"a":1}""",
            calls.joinToString("") { it.argumentsFragment },
            "参数必须原样顺序拼接，中间不能做任何加工",
        )
    }

    @Test
    fun 缺少下标时应退回数组位置保证归集键存在() {
        val decoder = ChatStreamDecoder()
        val events = decoder.decodeAll(
            """{"choices":[{"delta":{"tool_calls":[""" +
                """{"function":{"arguments":"x"}},{"function":{"arguments":"y"}}]}}]}""",
        )
        val calls = events.filterIsInstance<ChatStreamEvent.ToolCallDelta>()
        assertEquals(listOf(0, 1), calls.map { it.index })
    }

    // ------------------------------------------------------------------
    // 噪声与异常
    // ------------------------------------------------------------------

    @Test
    fun 空负载不应产出事件() {
        val decoder = ChatStreamDecoder()
        assertTrue(decoder.decodeAll("", "   ").isEmpty())
    }

    @Test
    fun 只有元数据的帧不应产出事件() {
        val decoder = ChatStreamDecoder()
        assertTrue(decoder.decodeAll("""{"id":"x","model":"m","object":"chunk"}""").isEmpty())
    }

    @Test
    fun 畸形负载应上报但不终止整条流() {
        // 一次生成可能上千帧，为一帧坏数据丢掉全部已产出内容不划算
        val decoder = ChatStreamDecoder()
        val bad = decoder.decodeAll("{这不是合法结构")
        val failure = bad.filterIsInstance<ChatStreamEvent.Failed>().single()
        assertEquals(StreamErrorCode.MalformedPayload, failure.error.code)
        assertFalse(decoder.isTerminated)

        // 坏帧之后仍应能正常解码
        val good = decoder.decodeAll("""{"choices":[{"delta":{"content":"继续"}}]}""")
        assertEquals("继续", good.filterIsInstance<ChatStreamEvent.TextDelta>().single().text)
    }

    @Test
    fun 流内错误带数字标识时应按状态码归类() {
        val decoder = ChatStreamDecoder()
        val events = decoder.decodeAll("""{"error":{"code":402,"message":"insufficient balance"}}""")
        val failure = events.filterIsInstance<ChatStreamEvent.Failed>().single()
        assertEquals(
            StreamErrorCode.QuotaExhausted, failure.error.code,
            "额度耗尽要和普通请求错误分开，因为重试没有意义",
        )
        assertEquals(402, failure.error.httpStatus)
        assertEquals("insufficient balance", failure.error.diagnostic)
    }

    @Test
    fun 流内错误带文字标识时应原样带出不猜分类() {
        val decoder = ChatStreamDecoder()
        val events = decoder.decodeAll(
            """{"error":{"code":"model_overloaded","message":"too busy"}}""",
        )
        val failure = events.filterIsInstance<ChatStreamEvent.Failed>().single()
        assertEquals(StreamErrorCode.Unknown, failure.error.code)
        assertEquals("model_overloaded", failure.error.providerCode)
    }

    @Test
    fun 承接方标识应被记录() {
        // 同一个模型两次调用表现差异很大时，这是唯一能用来排查的线索
        val decoder = ChatStreamDecoder()
        val events = decoder.decodeAll("""{"provider":"SomeVendor","choices":[]}""", "[DONE]")
        val completed = events.filterIsInstance<ChatStreamEvent.Completed>().single()
        assertEquals("SomeVendor", completed.servingProvider)
    }

    // ------------------------------------------------------------------
    // 停止原因映射
    // ------------------------------------------------------------------

    @Test
    fun 停止原因的几种写法应映射正确() {
        assertEquals(FinishReason.Stop, ParsedFinishReason.parse("stop").reason)
        assertEquals(FinishReason.Length, ParsedFinishReason.parse("length").reason)
        assertEquals(FinishReason.Length, ParsedFinishReason.parse("max_tokens").reason)
        assertEquals(FinishReason.ToolCalls, ParsedFinishReason.parse("tool_calls").reason)
        assertEquals(FinishReason.ContentFilter, ParsedFinishReason.parse("content_filter").reason)
    }

    @Test
    fun 大小写和空白应容错() {
        assertEquals(FinishReason.Stop, ParsedFinishReason.parse(" STOP ").reason)
    }

    @Test
    fun 不认识的停止原因应保留原文而不是丢弃() {
        val parsed = ParsedFinishReason.parse("some_new_reason")
        assertEquals(FinishReason.Unknown, parsed.reason)
        assertEquals("some_new_reason", parsed.raw, "排查时唯一有用的就是这个原始值")
    }
}
