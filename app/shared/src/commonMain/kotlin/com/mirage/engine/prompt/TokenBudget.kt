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
    /** 示范对话有没有被整体丢掉。界面要如实说出来。 */
    val droppedExamples: Boolean = false,
    /**
     * 这次发出去的内容，各部分分别占了多少。
     *
     * 单独报出来是为了让人能看出「上下文被谁吃掉了」——
     * 只给一个总数的话，看到「快满了」也不知道该压历史、
     * 还是该把卡里那段几千字的设定删一删。
     */
    val breakdown: TokenBreakdown = TokenBreakdown(),
)

/**
 * 一次请求里各部分占的长度。
 *
 * @property leading 开头的设定：角色设定、命中的世界观条目、前情提要、状态栏。
 * @property examples 卡里的示范对话。被整组丢掉时是 0。
 * @property history 中间保留下来的来回对话。
 * @property tail 这一轮说的话和队尾那条要求。
 * @property budget 这次最多能用多少。用来算占比。
 */
data class TokenBreakdown(
    val leading: Int = 0,
    val examples: Int = 0,
    val history: Int = 0,
    val tail: Int = 0,
    val budget: Int = 0,
) {
    val total: Int get() = leading + examples + history + tail

    /**
     * 用掉了预算的百分之几。预算为 0 时返回 0。
     *
     * 可能超过 100：光是不能丢的部分就超了预算时，我们照样发出去
     * （那时丢什么都没用），所以这个数字不夹在 100 以内 ——
     * 夹住就看不出到底超了多少。
     */
    val usedPercent: Int get() = if (budget <= 0) 0 else total * 100 / budget
}

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
        /**
         * 卡里的示范对话。空间不够时**整组丢掉**，而且比历史先丢。
         *
         * 为什么整组丢而不是丢一半：示范对话是一问一答配对出现的，
         * 只留下半边会变成一段没头没尾的话，比没有更糟。
         *
         * 为什么比历史先丢：示范是锦上添花，真实剧情丢了就接不上了。
         */
        examples: List<ChatMessage> = emptyList(),
        budget: Int = budgetOf(),
    ): TokenFit {
        val leadingCost = estimate(leading)
        val tailCost = estimate(tail)
        val fixedCost = leadingCost + tailCost

        // 先试着带上示范。它加上之后连最近一轮历史都放不下的话，就整组不要
        val exampleCost = estimate(examples)
        val historyFloor = history.lastOrNull()
            ?.let { estimate(it.content) + PER_MESSAGE_OVERHEAD }
            ?: 0
        val keepExamples = examples.isNotEmpty() &&
            fixedCost + exampleCost + historyFloor <= budget

        val kept = ArrayDeque<ChatMessage>()
        var used = fixedCost + if (keepExamples) exampleCost else 0

        // 从最近的往回加，加到放不下为止。越近的内容对当前剧情越要紧
        for (message in history.asReversed()) {
            val cost = estimate(message.content) + PER_MESSAGE_OVERHEAD
            if (used + cost > budget) break
            kept.addFirst(message)
            used += cost
        }

        return TokenFit(
            // 示范排在真实历史之前：它示范的是「怎么说话」，
            // 而紧挨着当前这轮的应该是真实剧情
            messages = leading + (if (keepExamples) examples else emptyList()) + kept + tail,
            droppedCount = history.size - kept.size,
            estimatedTokens = used,
            droppedExamples = examples.isNotEmpty() && !keepExamples,
            breakdown = TokenBreakdown(
                leading = leadingCost,
                examples = if (keepExamples) exampleCost else 0,
                // 用减的而不是把保留下来的再估一遍：上面是边加边判断的，
                // 重估一次有可能和它对不上，那样分项加起来就不等于总数了
                history = used - fixedCost - (if (keepExamples) exampleCost else 0),
                tail = tailCost,
                budget = budget,
            ),
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
