package com.mirage.engine.prompt

import com.mirage.engine.llm.ChatMessage

/**
 * 裁剪之后要发出去的内容。
 *
 * @property messages 最终发送的消息，顺序已排好。
 * @property droppedCount 为了装进上下文而省略掉的历史条数。界面要如实告诉用户。
 * @property estimatedTokens 估算出来的总量。
 */
data class TokenFit(
    val messages: List<ChatMessage>,
    val droppedCount: Int,
    val estimatedTokens: Int,
)

/**
 * 控制每次请求的长度，别超过模型的上下文上限。
 *
 * 聊得越久历史越长，总有一次会超过上限 —— 那时服务端直接报错，整轮白费。
 * 所以发送前先估一下，超了就从**最旧的历史**开始丢。
 *
 * ## 哪些绝对不能丢
 *
 * - **开头的设定**（角色设定、命中的世界观条目、当前状态栏）：丢了人设就塌了，
 *   模型会变成一个完全不认识这个角色的助手；
 * - **这一轮用户说的话**：丢了就没得回了；
 * - **队尾那条要求**：它的用意就是离模型最近，丢了等于作者白写。
 *
 * 所以只有中间的来回对话可以丢，而且从最旧的开始 —— 越近的内容对当前剧情越要紧。
 *
 * ## 估算只能是估算
 *
 * 真正的分词规则在服务商那边，各家还不一样，客户端算不准。实测还发现
 * **开着思考时平台会自己往输入侧塞内容**（量级在几十个 token），这部分我们
 * 连看都看不到。所以这里的策略是**宁可高估**：留足余量，宁可少放两轮历史，
 * 也不要真的撞上限。
 */
object TokenBudget {

    /**
     * 不知道模型上限时用的保守值。
     *
     * 实测各家差别极大（同一时间见过 32 000 和 1 048 576，差 32 倍），
     * 没有一个数字能通吃。取小的那一档当默认：**估小了只是少带几轮历史，
     * 估大了是整轮请求报错**，两种代价不对等。
     * 以后能从服务商拉到模型信息时，这个默认值会被真实上限替换。
     */
    const val DEFAULT_CONTEXT_TOKENS: Int = 32_000

    /**
     * 给回复留的空间。
     *
     * 叙事调用不设输出上限，模型想写多长写多长，所以这里要留够 ——
     * 留少了会出现「输入塞得下，但模型写到一半没空间了」。
     */
    const val RESERVED_FOR_REPLY: Int = 4_096

    /**
     * 额外的安全余量。
     *
     * 盖住两件我们算不到的事：平台自己往输入侧注入的内容，
     * 以及我们的字符估算与真实分词之间的偏差。
     */
    const val SAFETY_MARGIN: Int = 1_024

    /** 这次请求最多能用多少。 */
    fun budgetOf(contextTokens: Int = DEFAULT_CONTEXT_TOKENS): Int =
        (contextTokens - RESERVED_FOR_REPLY - SAFETY_MARGIN).coerceAtLeast(MIN_BUDGET)

    /**
     * 估算一段文字有多少 token。
     *
     * 按字符类别分开算：一个汉字比一个英文字母贵得多。
     *
     * 🔴 汉字按 **1.5 个** 算，不是 1 个。
     * 最初写成 1 个，和实际口径一对照才发现**中文场景低估了三成**，
     * 而中文正是我们的主场景 —— 低估的后果是以为还装得下，结果请求被服务端打回。
     *
     * 为了不引入小数，内部用「半个 token」做单位，最后再除以二向上取整。
     * 整数运算的好处是结果稳定可复现，不会出现浮点累加的误差。
     */
    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        var wide = 0
        var narrow = 0
        text.forEach { char ->
            if (isWideChar(char)) wide += 1 else narrow += 1
        }
        // 窄字符三个字符算两个半 token（也就是每 3 个算 1 个），向上取整
        val halves = wide * WIDE_HALF_TOKENS +
            (narrow * 2 + NARROW_CHARS_PER_TOKEN - 1) / NARROW_CHARS_PER_TOKEN
        return (halves + 1) / 2
    }

    /** 估算一组消息。每条都加一点固定开销，那是角色名和分隔符占的。 */
    fun estimate(messages: List<ChatMessage>): Int =
        messages.sumOf { estimate(it.content) + PER_MESSAGE_OVERHEAD }

    /**
     * 把内容装进预算，装不下就从最旧的历史开始丢。
     *
     * @param leading 开头的设定，绝不丢。
     * @param history 中间的来回对话，从旧到新。只有这部分可以丢。
     * @param tail 这一轮用户说的话和队尾要求，绝不丢。
     *
     * 即使光是不能丢的部分就已经超了，也照样发出去：那时丢什么都没用，
     * 让服务端报错反而能让用户看到真实原因（通常是设定本身写得太长）。
     */
    fun fit(
        leading: List<ChatMessage>,
        history: List<ChatMessage>,
        tail: List<ChatMessage>,
        budget: Int = budgetOf(),
    ): TokenFit {
        val fixedCost = estimate(leading) + estimate(tail)
        val kept = ArrayDeque<ChatMessage>()
        var used = fixedCost

        // 从最近的往回加，加到放不下为止。越近的内容对当前剧情越要紧
        for (message in history.asReversed()) {
            val cost = estimate(message.content) + PER_MESSAGE_OVERHEAD
            if (used + cost > budget) break
            kept.addFirst(message)
            used += cost
        }

        return TokenFit(
            messages = leading + kept + tail,
            droppedCount = history.size - kept.size,
            estimatedTokens = used,
        )
    }

    /**
     * 宽字符判定：中日韩文字、全角标点。
     *
     * 只按区间判断，不做更细的分类 —— 更细也不会更准，真正的分词规则
     * 在服务商那边，我们本来就只是在估。
     */
    private fun isWideChar(char: Char): Boolean {
        val code = char.code
        return code in 0x2E80..0x9FFF || // 汉字及部首
            code in 0xAC00..0xD7AF || // 谚文
            code in 0xF900..0xFAFF || // 兼容汉字
            code in 0xFF00..0xFF60 || // 全角符号
            code in 0x3000..0x303F // 中日韩标点
    }

    /** 一个汉字算几个「半 token」。3 个半 token = 1.5 个 token。 */
    private const val WIDE_HALF_TOKENS = 3

    /** 窄字符多少个算一个 token。 */
    private const val NARROW_CHARS_PER_TOKEN = 3

    /** 每条消息的固定开销（角色名、分隔符）。 */
    private const val PER_MESSAGE_OVERHEAD = 4

    /** 预算的下限。再小就连设定都放不下，没有意义。 */
    private const val MIN_BUDGET = 1_024
}
