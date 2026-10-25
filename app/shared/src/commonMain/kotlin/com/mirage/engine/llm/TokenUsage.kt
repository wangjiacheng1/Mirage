package com.mirage.engine.llm

/**
 * 一次调用消耗的 token 与费用。
 *
 * 字段全部可空，因为这是实测得出的结论而不是保守设计：
 * 有的服务商在流式响应里压根不带用量，有的只在整条流的最后一帧才带，
 * 还有的要等调用结束后隔几十秒才能从另一个接口查到。
 * 把它们设成非空再填 0，会让「没有数据」和「真的是 0」无法区分，
 * 而这两者在展示费用时的含义完全不同。
 */
data class TokenUsage(
    /** 输入侧 token。 */
    val promptTokens: Int? = null,

    /** 输出侧 token，含思考过程。 */
    val completionTokens: Int? = null,

    /** 输入 + 输出。服务商给了就用它，没给则由两侧相加兜底。 */
    val totalTokens: Int? = null,

    /**
     * 其中属于「思考过程」的 token。
     *
     * 单独拆出来是因为它通常比正文贵得多，而且默认是开启的 ——
     * 不显式关闭就会产生这笔开销，用户需要能看见它到底花了多少。
     */
    val reasoningTokens: Int? = null,

    /**
     * 服务商结算的金额。
     *
     * 币种不在这里表达：各家口径不同，换算要在展示层做，
     * 引擎只负责把拿到的数字原样带出，不做任何推测或折算。
     */
    val cost: Double? = null,
) {
    /** 是否一个有效数字都没拿到。用来决定界面上该显示「待补全」还是真实数值。 */
    val isEmpty: Boolean
        get() = promptTokens == null && completionTokens == null &&
            totalTokens == null && reasoningTokens == null && cost == null

    /**
     * 用后到的数据补全当前对象。
     *
     * 需要这个操作是因为用量信息可能分散在不同位置先后到达，
     * 而且后到的那一帧往往只带部分字段。直接整体替换会把先前拿到的数据抹掉，
     * 所以这里逐字段「有新值才覆盖」。
     */
    fun mergeWith(later: TokenUsage): TokenUsage = TokenUsage(
        promptTokens = later.promptTokens ?: promptTokens,
        completionTokens = later.completionTokens ?: completionTokens,
        totalTokens = later.totalTokens ?: totalTokens,
        reasoningTokens = later.reasoningTokens ?: reasoningTokens,
        cost = later.cost ?: cost,
    )

    /**
     * 在服务商没给总数时，用两侧之和补上。
     *
     * 只在两侧都存在时才计算：只有一侧的话，算出来的「总数」是错的，
     * 宁可留空让界面显示未知。
     */
    fun withDerivedTotal(): TokenUsage =
        if (totalTokens == null && promptTokens != null && completionTokens != null) {
            copy(totalTokens = promptTokens + completionTokens)
        } else {
            this
        }
}
