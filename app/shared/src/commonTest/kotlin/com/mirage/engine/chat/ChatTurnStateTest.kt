package com.mirage.engine.chat

import com.mirage.engine.llm.ChatStreamEvent
import com.mirage.engine.llm.FinishReason
import com.mirage.engine.llm.ParsedFinishReason
import com.mirage.engine.llm.StreamError
import com.mirage.engine.llm.StreamErrorCode
import com.mirage.engine.llm.TokenUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 请求状态流转的测试。
 *
 * 这些流转全部可以离线验证，所以没有理由靠真机点一遍来确认。
 * 其中几条（终态之后不再变化、失败时保留已产出内容）错了之后的表现
 * 是「同一次请求先报成功又报失败」或「内容凭空消失」，
 * 真机上偶发、难复现，正是最该用这种方式钉住的部分。
 */
class ChatTurnStateTest {

    private val initial = ChatTurnState()

    private fun ChatTurnState.feed(vararg events: ChatStreamEvent): ChatTurnState =
        events.fold(this) { state, event -> state.reduce(event) }

    private fun completed(
        usage: TokenUsage = TokenUsage(),
        reason: String? = "stop",
        provider: String? = null,
    ) = ChatStreamEvent.Completed(
        finishReason = reason?.let { ParsedFinishReason.parse(it) },
        usage = usage,
        servingProvider = provider,
    )

    // ------------------------------------------------------------------
    // 阶段流转
    // ------------------------------------------------------------------

    @Test
    fun 初始应为空闲且不活跃() {
        assertEquals(ChatTurnPhase.Idle, initial.phase)
        assertFalse(initial.isActive)
    }

    @Test
    fun 发出请求后应进入连接中() {
        val state = initial.started()
        assertEquals(ChatTurnPhase.Connecting, state.phase)
        assertTrue(state.isActive)
    }

    @Test
    fun 服务端开始返回后应进入准备中() {
        // 这一段和「连接中」必须分开：前者卡住多半是网络或地址问题，
        // 后者是正常排队。混成一个笼统的等待，会让人把正常等待误判成卡死
        val state = initial.started().feed(ChatStreamEvent.ResponseStarted)
        assertEquals(ChatTurnPhase.Preparing, state.phase)
    }

    @Test
    fun 收到首段正文后应进入生成中() {
        val state = initial.started()
            .feed(ChatStreamEvent.ResponseStarted, ChatStreamEvent.TextDelta("你"))
        assertEquals(ChatTurnPhase.Generating, state.phase)
        assertEquals("你", state.text)
    }

    @Test
    fun 正文分片应按序累积() {
        val state = initial.started().feed(
            ChatStreamEvent.TextDelta("你"),
            ChatStreamEvent.TextDelta("好"),
            ChatStreamEvent.TextDelta("呀"),
        )
        assertEquals("你好呀", state.text)
    }

    @Test
    fun 已在生成中时再收到开始信号不应回退阶段() {
        val state = initial.started().feed(
            ChatStreamEvent.TextDelta("x"),
            ChatStreamEvent.ResponseStarted,
        )
        assertEquals(ChatTurnPhase.Generating, state.phase, "阶段只能向前，不能倒退")
    }

    // ------------------------------------------------------------------
    // 思考过程
    // ------------------------------------------------------------------

    @Test
    fun 思考过程应与正文分开累积() {
        val state = initial.started().feed(
            ChatStreamEvent.ReasoningDelta("先想一下"),
            ChatStreamEvent.TextDelta("答案"),
        )
        assertEquals("先想一下", state.reasoning)
        assertEquals("答案", state.text)
    }

    @Test
    fun 只有思考还没正文时应可被单独识别() {
        // 这种状态下显示「正在生成」会让人困惑：明明说在生成却一个字都看不到
        val state = initial.started().feed(ChatStreamEvent.ReasoningDelta("嗯"))
        assertTrue(state.isThinkingOnly)

        val afterText = state.feed(ChatStreamEvent.TextDelta("好"))
        assertFalse(afterText.isThinkingOnly)
    }

    // ------------------------------------------------------------------
    // 结束
    // ------------------------------------------------------------------

    @Test
    fun 停止原因只记录不改变阶段() {
        // 它可能在真正结束前就到达，而且可能到达多次。
        // 据它判定结束会丢掉随后才送达的用量和费用
        val state = initial.started().feed(
            ChatStreamEvent.TextDelta("完"),
            ChatStreamEvent.StopSignalled(ParsedFinishReason.parse("stop")),
        )
        assertEquals(ChatTurnPhase.Generating, state.phase, "还没真正结束，不能提前进终态")
        assertEquals(FinishReason.Stop, state.finishReason)
        assertTrue(state.isActive)
    }

    @Test
    fun 结束事件应落下用量与承接方() {
        val state = initial.started().feed(
            ChatStreamEvent.TextDelta("好"),
            completed(
                usage = TokenUsage(promptTokens = 11, completionTokens = 22, cost = 0.0003),
                provider = "SomeVendor",
            ),
        )
        assertEquals(ChatTurnPhase.Completed, state.phase)
        assertEquals(11, state.usage.promptTokens)
        assertEquals(0.0003, state.usage.cost)
        assertEquals("SomeVendor", state.servingProvider)
        assertFalse(state.isActive)
    }

    @Test
    fun 结束事件没带停止原因时应保留先前记录的那个() {
        val state = initial.started().feed(
            ChatStreamEvent.StopSignalled(ParsedFinishReason.parse("length")),
            completed(reason = null),
        )
        assertEquals(FinishReason.Length, state.finishReason)
    }

    // ------------------------------------------------------------------
    // 失败
    // ------------------------------------------------------------------

    @Test
    fun 失败时应保留已产出的内容() {
        // 读到一半才出错，前面的字已经生成、也已经计费，丢掉它对使用者只有损失
        val state = initial.started().feed(
            ChatStreamEvent.TextDelta("已经说了一半"),
            ChatStreamEvent.Failed(StreamError(StreamErrorCode.IdleTimeout)),
        )
        assertEquals(ChatTurnPhase.Failed, state.phase)
        assertEquals("已经说了一半", state.text)
        assertEquals(StreamErrorCode.IdleTimeout, state.error?.code)
    }

    @Test
    fun 失败信息里不应带面向使用者的文案() {
        // 文案由界面按分类自己决定，引擎只给分类和参数
        val state = initial.started()
            .feed(ChatStreamEvent.Failed(StreamError(StreamErrorCode.Unauthorized, httpStatus = 401)))
        assertEquals(StreamErrorCode.Unauthorized, state.error?.code)
        assertEquals(401, state.error?.httpStatus)
        assertNull(state.error?.providerCode)
    }

    // ------------------------------------------------------------------
    // 终态保护
    // ------------------------------------------------------------------

    @Test
    fun 正常结束之后到达的事件应被忽略() {
        // 终态之后再变化意味着同一次请求先报成功又报失败，使用者无从判断到底成没成
        val finished = initial.started().feed(ChatStreamEvent.TextDelta("好"), completed())
        val afterLate = finished.feed(
            ChatStreamEvent.Failed(StreamError(StreamErrorCode.IdleTimeout)),
            ChatStreamEvent.TextDelta("迟到的内容"),
        )
        assertEquals(ChatTurnPhase.Completed, afterLate.phase)
        assertEquals("好", afterLate.text)
        assertNull(afterLate.error)
    }

    @Test
    fun 失败之后到达的事件应被忽略() {
        val failed = initial.started()
            .feed(ChatStreamEvent.Failed(StreamError(StreamErrorCode.NetworkFailure)))
        val afterLate = failed.feed(completed())
        assertEquals(ChatTurnPhase.Failed, afterLate.phase, "失败过的请求不该又报成成功")
    }

    @Test
    fun 中止后应保留已产出内容且不再变化() {
        val state = initial.started()
            .feed(ChatStreamEvent.TextDelta("一半"))
            .cancelled()
            .feed(ChatStreamEvent.TextDelta("不该再来"))
        assertEquals(ChatTurnPhase.Cancelled, state.phase)
        assertEquals("一半", state.text)
        assertFalse(state.isActive)
    }

    @Test
    fun 工具调用分片不应影响界面状态() {
        val state = initial.started().feed(
            ChatStreamEvent.ToolCallDelta(index = 0, callId = "c", name = "n", argumentsFragment = "{"),
        )
        assertEquals(ChatTurnPhase.Connecting, state.phase)
        assertEquals("", state.text)
    }
}
