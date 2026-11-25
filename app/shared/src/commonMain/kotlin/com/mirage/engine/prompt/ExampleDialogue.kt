package com.mirage.engine.prompt

/** 示范对话里的一句。 */
data class ExampleTurn(
    val fromUser: Boolean,
    val text: String,
)

/**
 * 把卡里的对话示例拆成一轮轮真正的对话。
 *
 * ## 卡里长什么样
 *
 * ```
 * <START>
 * {{user}}: 你笑什么？
 * {{char}}: 「笑你站得这么直。」她把酒碗往墙砖上一放…
 * <START>
 * {{user}}: 我不信你什么都不怕。
 * {{char}}: 「怕啊。」她答得很快…
 * ```
 *
 * `<START>` 分隔的是**几段互不相干的对话**，不是一段连续聊天。
 *
 * ## 为什么要拆
 *
 * 整段文字直接塞进设定里，模型看到的是「一段带着奇怪标记的说明」。
 * 拆成真正的一问一答之后，它看到的是「原来这个角色是这样说话的」——
 * 示范效果完全不同。而对话示例恰恰是控制语气最有效的东西，
 * 比任何「请用某某语气」的要求都管用。
 *
 * ## 认不出来就不要硬拆
 *
 * 卡的写法五花八门，有些根本没有说话人标记。这时返回空，
 * 让调用方退回「原样当设定文字发过去」—— **宁可没拆成，也不能把内容弄丢**。
 */
object ExampleDialogueParser {

    /** 分隔符。大小写都有人写。 */
    private const val BLOCK_SEPARATOR = "<START>"

    /**
     * 拆成若干段，每段是一轮轮对话。
     *
     * @param userName 玩家的称呼，用来判断某一句是谁说的。
     * @param charName 角色名，同上。
     *
     * 说话人除了 `{{user}}` / `{{char}}` 这两种写法，也可能直接写名字。
     * 两种都认 —— 只认占位的话，那些写真名的卡会整段拆不出来。
     */
    fun parse(raw: String, userName: String, charName: String): List<List<ExampleTurn>> {
        if (raw.isBlank()) return emptyList()

        return raw.split(BLOCK_SEPARATOR, ignoreCase = true)
            .map { block -> parseBlock(block, userName, charName) }
            .filter { it.isNotEmpty() }
    }

    private fun parseBlock(block: String, userName: String, charName: String): List<ExampleTurn> {
        val turns = mutableListOf<ExampleTurn>()
        var current: ExampleTurn? = null
        val buffer = StringBuilder()

        fun flush() {
            val pending = current ?: return
            val text = buffer.toString().trim()
            if (text.isNotEmpty()) turns += pending.copy(text = text)
            buffer.clear()
        }

        block.lineSequence().forEach { line ->
            val speaker = speakerOf(line, userName, charName)
            if (speaker == null) {
                // 不是新的一句，那就是上一句的后续内容。
                // 示范对话经常是多段的，这种续行必须接在上一句后面而不是丢掉
                if (current != null) buffer.append(line).append('\n')
                return@forEach
            }
            flush()
            current = ExampleTurn(fromUser = speaker.fromUser, text = "")
            buffer.append(speaker.rest).append('\n')
        }
        flush()
        return turns
    }

    private data class Speaker(val fromUser: Boolean, val rest: String)

    /**
     * 这一行是不是「某某：……」的开头。
     *
     * 🔴 **只认这四种说话人**：`{{user}}`、`{{char}}`、玩家的称呼、角色名。
     * 其余一律当成上一句的续行。
     *
     * 一开始我写的是「冒号前面够短就算说话人」，单测当场证明这条太松：
     * 中文叙述里带冒号太常见了（「他报了个时间：戌时三刻」），
     * 一句台词会被劈成两个人说的。而「冒号前面有几个字」和「它是不是人名」
     * 本来就没有关系 —— 猜不准的事就不要猜。
     *
     * 代价是卡里写「路人甲：」的那种配角台词不会单独成句，
     * 而是接在上一句后面。内容一个字都没丢，只是少分了一次段，可以接受。
     *
     * 冒号认中英文两种，只认行首那一个。
     */
    private fun speakerOf(line: String, userName: String, charName: String): Speaker? {
        val trimmed = line.trimStart()
        val colon = trimmed.indexOfFirst { it == ':' || it == '：' }
        if (colon <= 0) return null

        val label = trimmed.substring(0, colon).trim()
        val rest = trimmed.substring(colon + 1).trimStart()
        return when {
            label.equals("{{user}}", ignoreCase = true) ||
                (userName.isNotEmpty() && label == userName) -> Speaker(true, rest)

            label.equals("{{char}}", ignoreCase = true) ||
                (charName.isNotEmpty() && label == charName) -> Speaker(false, rest)

            else -> null
        }
    }
}
