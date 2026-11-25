package com.mirage.engine.summary

import com.mirage.engine.llm.ChatCompletionClient
import com.mirage.engine.llm.ProviderProfile
import com.mirage.engine.llm.ReasoningControl
import com.mirage.engine.llm.StreamErrorCode
import com.mirage.engine.net.HttpStreamEvent
import com.mirage.engine.net.ScriptedHttpStreamTransport
import com.mirage.engine.net.TransportFailureKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 压缩全链路的测试。
 *
 * 验的是这条链：拼原文 → 构造请求 → 调用 → 把分片攒成摘要。
 *
 * 🔴 **为什么必须有这一组，而不是只测各自那一半**
 *
 * 这里栽过一次：请求那边写了「不要流式」，收响应那边却只有流式通道一条路。
 * 两边各自的单测都是绿的 —— 请求那边断言「确实没流式」，
 * 收响应那边喂的是 SSE 所以也能跑通 —— **接起来才是错的**。
 * 真机表现是「每次点压缩都失败」，而单测一条都没红。
 *
 * 结论：凡是「一边发、一边收」的协议，必须有一组从请求走到结果的用例。
 */
class SummaryCoordinatorTest {

    private val profile = ProviderProfile(
        id = "p",
        name = "P",
        baseUrl = "https://example.com/v1",
        reasoningControl = ReasoningControl.ThinkingTypeObject,
    )

    /** 让模型「返回」一段指定正文。 */
    private fun replying(vararg chunks: String): List<HttpStreamEvent> =
        ScriptedHttpStreamTransport.completeStream(
            *chunks.map { """{"choices":[{"delta":{"content":${quote(it)}}}]}""" }.toTypedArray(),
        )

    private fun quote(text: String): String =
        "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private suspend fun summarize(
        script: List<HttpStreamEvent>,
    ): Pair<SummaryOutcome, ScriptedHttpStreamTransport> {
        val transport = ScriptedHttpStreamTransport(script)
        val outcome = SummaryCoordinator(ChatCompletionClient(transport)).summarize(
            profile = profile,
            credential = "secret",
            request = SummaryPromptBuilder.buildVolumeRequest(
                modelId = "some-model",
                transcript = "玩家：我走进酒馆\n\n叙述：老板抬起头。",
            ),
        )
        return outcome to transport
    }

    // ------------------------------------------------------------------
    // 整条链路
    // ------------------------------------------------------------------

    @Test
    fun 分片摘要应被攒成完整一段() = runTest {
        val (outcome, _) = summarize(replying("他走进", "酒馆，", "老板认出了他。"))

        assertEquals("他走进酒馆，老板认出了他。", outcome.text)
        assertNull(outcome.error)
    }

    @Test
    fun 发出去的请求必须声明流式() = runTest {
        // 🔴 这一条就是上面那个 bug 的守门人。
        // 说成 false 的话服务端返回普通 JSON，SSE 解析器一行都认不出来
        val (_, transport) = summarize(replying("摘要"))

        assertTrue(assertNotNull(transport.received).body.contains("\"stream\":true"))
    }

    @Test
    fun 发出去的请求应关闭思考() = runTest {
        // 设了长度上限就必须关思考，上限是两者共享的
        val (_, transport) = summarize(replying("摘要"))
        val body = assertNotNull(transport.received).body

        assertTrue(body.contains("\"thinking\""))
        assertTrue(body.contains("disabled"))
    }

    @Test
    fun 原文应作为用户消息发出去() = runTest {
        val (_, transport) = summarize(replying("摘要"))

        assertTrue(assertNotNull(transport.received).body.contains("我走进酒馆"))
    }

    // ------------------------------------------------------------------
    // 拿不到内容
    // ------------------------------------------------------------------

    @Test
    fun 一个字都没返回时当失败() = runTest {
        // 存一个空卷会永久顶替掉那几轮原文，表现成「它把中间那段全忘了」
        val (outcome, _) = summarize(ScriptedHttpStreamTransport.completeStream())

        assertNull(outcome.text)
    }

    @Test
    fun 只返回空白时也当失败() = runTest {
        val (outcome, _) = summarize(replying("   ", "\n"))

        assertNull(outcome.text)
    }

    @Test
    fun 调用失败时不给出摘要() = runTest {
        val script = listOf<HttpStreamEvent>(
            HttpStreamEvent.TransportFailure(TransportFailureKind.IdleTimeout, "太久没动静"),
        )

        val (outcome, _) = summarize(script)

        assertNull(outcome.text)
        assertEquals(StreamErrorCode.IdleTimeout, outcome.error?.code)
    }

    @Test
    fun 失败时已收到的片段也不当成摘要() = runTest {
        // 读到一半断了，那半段不能存成一卷 —— 它会永久顶替掉完整的原文
        val script = ScriptedHttpStreamTransport.lines(
            """data: {"choices":[{"delta":{"content":"他走进"}}]}""",
            "",
        ) + listOf<HttpStreamEvent>(
            HttpStreamEvent.TransportFailure(TransportFailureKind.RequestFailed, "断了"),
        )

        val (outcome, _) = summarize(script)

        assertNull(outcome.text)
    }
}
