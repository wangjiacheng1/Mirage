package com.mirage.engine.chat

import com.mirage.engine.llm.ChatStreamEvent
import com.mirage.engine.llm.FinishReason
import com.mirage.engine.llm.StreamError
import com.mirage.engine.llm.TokenUsage

/**
 * 一次请求所处的阶段。
 *
 * 等待过程被刻意拆成三个阶段而不是一个笼统的「加载中」。
 * 原因是实测中首次响应可能长达十几秒且期间毫无动静 —— 如果界面只显示一个转圈，
 * 使用者很容易判断成卡死进而重试，而**重试的代价是实打实的重复计费**：
 * 客户端断开并不会让服务端停止生成，已经产生的开销一分不少。
 * 所以「现在到底卡在哪一步」不是体验优化，是省钱功能。
 */
enum class ChatTurnPhase {
    /** 没有进行中的请求。 */
    Idle,

    /** 请求已发出，服务端还没开始返回。这一段卡住通常意味着网络或地址有问题。 */
    Connecting,

    /**
     * 服务端已受理请求、开始返回，但还没有产出任何内容。
     *
     * 这一段是正常的，可能在排队或准备。把它和上一段区分开，
     * 使用者才能知道「请求已经送到了，只是还没轮到」。
     */
    Preparing,

    /** 内容正在产出。 */
    Generating,

    /** 正常结束。 */
    Completed,

    /** 失败结束。 */
    Failed,

    /** 被使用者主动中止。 */
    Cancelled,
}

/**
 * 一次请求的完整状态。
 *
 * 做成不可变的数据加一个纯函数变换，而不是一个会被就地修改的对象：
 * 这样每一步变化都可以被单独构造和断言，整条状态流转可以完全离线验证，
 * 不需要网络也不需要界面。
 */
data class ChatTurnState(
    val phase: ChatTurnPhase = ChatTurnPhase.Idle,

    /** 已累积的正文。 */
    val text: String = "",

    /** 已累积的思考过程。 */
    val reasoning: String = "",

    /** 用量与费用。整条流结束时才会完整。 */
    val usage: TokenUsage = TokenUsage(),

    /** 服务端给出的停止原因。 */
    val finishReason: FinishReason? = null,

    /** 失败信息。只有分类和参数，不含任何面向使用者的文案。 */
    val error: StreamError? = null,

    /** 实际承接本次请求的服务方。同一模型两次表现差异很大时，这是唯一的排查线索。 */
    val servingProvider: String? = null,
) {
    /** 是否还在进行中。界面据此决定显示发送还是中止。 */
    val isActive: Boolean
        get() = phase == ChatTurnPhase.Connecting ||
            phase == ChatTurnPhase.Preparing ||
            phase == ChatTurnPhase.Generating

    /**
     * 是否只产出了思考过程、正文还没开始。
     *
     * 值得单独暴露，是因为这种状态下界面显示「正在生成」会让人困惑 ——
     * 明明说在生成却一个字都看不到。如实说明「正在思考」更准确，
     * 也顺便让人意识到思考是要花钱的。
     */
    val isThinkingOnly: Boolean
        get() = phase == ChatTurnPhase.Generating && text.isEmpty() && reasoning.isNotEmpty()

    /** 请求发出时的状态。 */
    fun started(): ChatTurnState = ChatTurnState(phase = ChatTurnPhase.Connecting)

    /**
     * 使用者主动中止。
     *
     * 保留已产出的内容：那部分已经生成、也已经计费，丢掉它对使用者只有损失。
     */
    fun cancelled(): ChatTurnState = copy(phase = ChatTurnPhase.Cancelled)

    /**
     * 吃进一个事件，得到新状态。
     *
     * 几条刻意的处理：
     * - **停止原因只记录、不改变阶段。** 它可能在真正结束之前就到达，
     *   而且可能到达多次。据它判定结束会丢掉随后才送达的用量和费用。
     * - **失败时保留已产出的内容。** 读到一半才出错，前面的字已经生成且已计费。
     * - **已经进入终态后忽略后续事件。** 终态之后再变化意味着同一次请求
     *   先报成功又报失败，使用者无从判断到底成没成。
     */
    fun reduce(event: ChatStreamEvent): ChatTurnState {
        if (isTerminal()) return this

        return when (event) {
            ChatStreamEvent.ResponseStarted ->
                if (phase == ChatTurnPhase.Connecting) copy(phase = ChatTurnPhase.Preparing) else this

            is ChatStreamEvent.TextDelta -> copy(
                phase = ChatTurnPhase.Generating,
                text = text + event.text,
            )

            is ChatStreamEvent.ReasoningDelta -> copy(
                phase = ChatTurnPhase.Generating,
                reasoning = reasoning + event.text,
            )

            is ChatStreamEvent.StopSignalled -> copy(finishReason = event.finishReason.reason)

            is ChatStreamEvent.Completed -> copy(
                phase = ChatTurnPhase.Completed,
                usage = event.usage,
                finishReason = event.finishReason?.reason ?: finishReason,
                servingProvider = event.servingProvider,
            )

            is ChatStreamEvent.Failed -> copy(
                phase = ChatTurnPhase.Failed,
                error = event.error,
            )

            // 工具调用分片不参与界面状态，由专门的处理环节消费
            is ChatStreamEvent.ToolCallDelta -> this
        }
    }

    private fun isTerminal(): Boolean = phase == ChatTurnPhase.Completed ||
        phase == ChatTurnPhase.Failed ||
        phase == ChatTurnPhase.Cancelled
}
