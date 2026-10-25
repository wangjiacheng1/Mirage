package com.mirage.engine.llm

/**
 * 流式调用过程中向调用方汇报的事件。
 *
 * 刻意把「正文」「思考过程」「工具调用」分成三种事件而不是合成一种带标记的结构：
 * 它们的去向完全不同 —— 正文要即时显示，思考过程通常折叠或丢弃，
 * 工具调用的参数是分片到达的、必须先攒齐才能用。合成一种会让每个调用点
 * 都得先判断类型再分流，等于把分发逻辑复制到所有使用者身上。
 */
sealed interface ChatStreamEvent {

    /**
     * 服务端已受理请求，开始返回，但还没有产出内容。
     *
     * 它的价值在于让等待过程可以被如实描述：收到它之前是「还在连」，
     * 收到之后到首段内容之前是「对方正在准备」。
     * 把这两段合成一个笼统的等待，会让人把正常的长等待误判成卡死。
     */
    data object ResponseStarted : ChatStreamEvent

    /** 一小段正文。按到达顺序直接追加即可。 */
    data class TextDelta(val text: String) : ChatStreamEvent

    /**
     * 一小段思考过程。
     *
     * 需要单独知道它的存在，不只是为了折叠显示：它消耗的额度和正文共享，
     * 调用方可能需要据此判断「正文为空是不是因为额度被思考吃光了」。
     */
    data class ReasoningDelta(val text: String) : ChatStreamEvent

    /**
     * 工具调用的一个分片。
     *
     * 参数几乎一定是分多片到达的，且分片数量在不同服务商之间差别很大，
     * 所以这里不做任何拼接和校验，只保证把分片原样带出。
     *
     * @property index 同一次回复里可能并行出现多个调用，靠这个下标归集。
     * @property callId 调用标识，通常只在该下标的第一个分片里出现。
     * @property name 要调用的名字，同样通常只在第一个分片里出现。
     * @property argumentsFragment 参数文本的一个片段，必须原样顺序拼接。
     */
    data class ToolCallDelta(
        val index: Int,
        val callId: String?,
        val name: String?,
        val argumentsFragment: String,
    ) : ChatStreamEvent

    /**
     * 服务端告知本次生成停止的原因。
     *
     * 这个事件**可能出现多次**：有的服务商会在停止原因之后再补发一帧用量数据，
     * 而那一帧往往把停止原因又带了一遍。因此收到它不代表流已经结束，
     * 不能据此停止读取 —— 真正的结束信号是 [Completed]。
     */
    data class StopSignalled(val finishReason: ParsedFinishReason) : ChatStreamEvent

    /**
     * 整条流正常结束。
     *
     * @property finishReason 整条流里最后一次收到的停止原因，没收到则为 null。
     * @property usage 整条流里累积合并出来的用量，可能为空。
     * @property servingProvider 实际承接本次请求的服务方标识。
     *
     * 需要 [servingProvider] 是因为有的接入点会把请求转发给背后多家中的某一家，
     * 每次可能不同。不记下来的话，同一个模型两次调用表现差异很大时无从排查。
     */
    data class Completed(
        val finishReason: ParsedFinishReason?,
        val usage: TokenUsage,
        val servingProvider: String?,
    ) : ChatStreamEvent

    /** 出错了。是否终止由 [StreamError.code] 的性质决定，由调用方判断。 */
    data class Failed(val error: StreamError) : ChatStreamEvent
}
