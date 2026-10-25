package com.mirage.engine.llm

import com.mirage.engine.net.HttpStreamEvent
import com.mirage.engine.net.ScriptedHttpStreamTransport
import com.mirage.engine.net.TransportFailureKind
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 编排逻辑的测试。
 *
 * 用一个假的传输实现，好处是能精确构造真实网络里很难复现的时序 ——
 * 比如「最后一帧没有空行收尾」「刚连上就返回错误」「读到一半超时」。
 * 这些恰恰是最容易出错、而真机调试几乎碰不到的分支。
 */
class ChatCompletionClientTest {

    private fun lines(vararg text: String): List<HttpStreamEvent> =
        ScriptedHttpStreamTransport.lines(*text)

    private val profile = ProviderProfile(
        id = "p",
        name = "P",
        baseUrl = "https://example.com/v1",
        reasoningControl = ReasoningControl.ThinkingTypeObject,
    )

    private val request = ChatCompletionRequest(
        modelId = "m",
        messages = listOf(ChatMessage(ChatRole.User, "你好")),
    )

    private suspend fun run(
        script: List<HttpStreamEvent>,
        credential: String = "secret",
    ): Pair<List<ChatStreamEvent>, ScriptedHttpStreamTransport> {
        val transport = ScriptedHttpStreamTransport(script)
        val events = ChatCompletionClient(transport).stream(profile, credential, request).toList()
        return events to transport
    }

    // ------------------------------------------------------------------
    // 请求的组装
    // ------------------------------------------------------------------

    @Test
    fun 应向配置的地址发出请求并带上凭据() = runTest {
        val (_, transport) = run(lines("data: [DONE]", ""))
        val sent = assertNotNull(transport.received)
        assertEquals("https://example.com/v1/chat/completions", sent.url)
        assertEquals("Bearer secret", sent.headers["Authorization"])
    }

    @Test
    fun 凭据为空白时不应放授权头() {
        // 带着空凭据发出去，服务端多半回一个含义模糊的拒绝，
        // 不如让它以「没有授权」的形式明确失败
        runTest {
            val (_, transport) = run(lines("data: [DONE]", ""), credential = "   ")
            assertTrue(assertNotNull(transport.received).headers.isEmpty())
        }
    }

    @Test
    fun 请求体应是组装后的内容() = runTest {
        val (_, transport) = run(lines("data: [DONE]", ""))
        val body = assertNotNull(transport.received).body
        assertTrue(body.contains("\"model\":\"m\""))
        assertTrue(body.contains("\"stream\":true"))
        assertTrue(
            body.contains("\"thinking\""),
            "配置支持思考参数时必须显式发出，省略会让它保持开启",
        )
    }

    // ------------------------------------------------------------------
    // 正常链路
    // ------------------------------------------------------------------

    @Test
    fun 行应被组装成帧并解释为事件() = runTest {
        val (events, _) = run(
            lines(
                """data: {"choices":[{"delta":{"content":"你"}}]}""", "",
                """data: {"choices":[{"delta":{"content":"好"}}]}""", "",
                "data: [DONE]", "",
            ),
        )
        assertEquals(
            listOf("你", "好"),
            events.filterIsInstance<ChatStreamEvent.TextDelta>().map { it.text },
        )
        assertEquals(1, events.filterIsInstance<ChatStreamEvent.Completed>().size)
    }

    @Test
    fun 心跳注释行不应产生任何事件() = runTest {
        val (events, _) = run(lines(": processing", "", "data: [DONE]", ""))
        assertTrue(events.filterIsInstance<ChatStreamEvent.TextDelta>().isEmpty())
        assertEquals(1, events.filterIsInstance<ChatStreamEvent.Completed>().size)
    }

    @Test
    fun 用量在停止原因之后到达时仍应进入结束事件() = runTest {
        val (events, _) = run(
            lines(
                """data: {"choices":[{"delta":{"content":"完"},"finish_reason":"stop"}]}""", "",
                """data: {"choices":[{"delta":{},"finish_reason":"stop"}],""" +
                    """"usage":{"prompt_tokens":7,"completion_tokens":9}}""", "",
                "data: [DONE]", "",
            ),
        )
        val completed = events.filterIsInstance<ChatStreamEvent.Completed>().single()
        assertEquals(7, completed.usage.promptTokens)
        assertEquals(16, completed.usage.totalTokens)
    }

    @Test
    fun 最后一帧没有空行收尾时也应被处理() = runTest {
        // 收尾帧常常正是携带用量和停止原因的那一帧，丢了最难排查
        val (events, _) = run(
            lines("""data: {"choices":[{"delta":{"content":"尾"}}],"usage":{"total_tokens":3}}"""),
        )
        assertEquals("尾", events.filterIsInstance<ChatStreamEvent.TextDelta>().single().text)
        val completed = events.filterIsInstance<ChatStreamEvent.Completed>().single()
        assertEquals(3, completed.usage.totalTokens)
    }

    @Test
    fun 没有结束标记时应补出结束事件() = runTest {
        val (events, _) = run(lines("""data: {"choices":[{"delta":{"content":"x"}}]}""", ""))
        assertEquals(1, events.filterIsInstance<ChatStreamEvent.Completed>().size)
    }

    @Test
    fun 有结束标记时不应重复发结束事件() = runTest {
        val (events, _) = run(lines("data: [DONE]", ""))
        assertEquals(1, events.filterIsInstance<ChatStreamEvent.Completed>().size)
    }

    // ------------------------------------------------------------------
    // 失败链路
    // ------------------------------------------------------------------

    @Test
    fun 请求失败应按状态码归类并取出服务端描述() = runTest {
        val (events, _) = run(
            listOf(
                HttpStreamEvent.HttpFailure(
                    status = 402,
                    body = """{"error":{"message":"balance too low","type":"billing"}}""",
                ),
            ),
        )
        val failure = events.filterIsInstance<ChatStreamEvent.Failed>().single()
        assertEquals(StreamErrorCode.QuotaExhausted, failure.error.code)
        assertEquals(402, failure.error.httpStatus)
        assertEquals("balance too low", failure.error.diagnostic)
        assertEquals("billing", failure.error.providerCode)
    }

    @Test
    fun 失败响应体不是结构化内容时也应正确归类() = runTest {
        // 网关出问题时经常返回一段 HTML，不能让解析失败压过真正的状态码信息
        val (events, _) = run(
            listOf(HttpStreamEvent.HttpFailure(502, "<html>Bad Gateway</html>")),
        )
        val failure = events.filterIsInstance<ChatStreamEvent.Failed>().single()
        assertEquals(StreamErrorCode.ServerError, failure.error.code)
        assertEquals(502, failure.error.httpStatus)
    }

    @Test
    fun 读取空闲超时应归类为空闲超时() = runTest {
        val (events, _) = run(
            lines("""data: {"choices":[{"delta":{"content":"半"}}]}""", "") +
                HttpStreamEvent.TransportFailure(TransportFailureKind.IdleTimeout),
        )
        // 已经产出的内容要保留，不能因为后面超时就丢掉
        assertEquals("半", events.filterIsInstance<ChatStreamEvent.TextDelta>().single().text)
        val failure = events.filterIsInstance<ChatStreamEvent.Failed>().single()
        assertEquals(StreamErrorCode.IdleTimeout, failure.error.code)
    }

    @Test
    fun 连接类失败应归类为网络失败() = runTest {
        val (events, _) = run(
            listOf(
                HttpStreamEvent.TransportFailure(
                    TransportFailureKind.RequestFailed,
                    diagnostic = "unresolved host",
                ),
            ),
        )
        val failure = events.filterIsInstance<ChatStreamEvent.Failed>().single()
        assertEquals(StreamErrorCode.NetworkFailure, failure.error.code)
        assertEquals("unresolved host", failure.error.diagnostic)
    }

    @Test
    fun 失败之后不应再发结束事件() = runTest {
        // 「成功结束」必须保持唯一含义，否则调用方无法判断内容能不能当完整结果用
        val (events, _) = run(listOf(HttpStreamEvent.HttpFailure(401, null)))
        assertTrue(
            events.filterIsInstance<ChatStreamEvent.Completed>().isEmpty(),
            "出错的调用不该同时报告成功结束",
        )
        assertEquals(
            StreamErrorCode.Unauthorized,
            events.filterIsInstance<ChatStreamEvent.Failed>().single().error.code,
        )
    }

    @Test
    fun 结束标记之后到达的失败不应被报出() = runTest {
        // 若服务端在结束标记之后不立刻关闭连接，空闲判定迟早会触发。
        // 此时已经产出过「正常结束」，再追加一个失败事件会让事件顺序自相矛盾，
        // 调用方无法判断这次调用到底成没成。
        val (events, _) = run(
            lines("data: [DONE]", "") +
                HttpStreamEvent.TransportFailure(TransportFailureKind.IdleTimeout),
        )
        assertEquals(1, events.filterIsInstance<ChatStreamEvent.Completed>().size)
        assertEquals(
            emptyList(),
            events.filterIsInstance<ChatStreamEvent.Failed>(),
            "流已经正常结束，之后到达的超时不该再报成失败",
        )
    }

    @Test
    fun 流内错误也应被归类() = runTest {
        // 有的服务商请求是 200，错误混在内容流里送回来
        val (events, _) = run(lines("""data: {"error":{"code":429,"message":"slow down"}}""", ""))
        val failure = events.filterIsInstance<ChatStreamEvent.Failed>().single()
        assertEquals(StreamErrorCode.RateLimited, failure.error.code)
    }
}
