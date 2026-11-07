package com.mirage.engine.text

/** 一段文字里某一截该用什么样式显示。 */
data class MarkupSpan(
    val start: Int,
    /** 不含这一位，和 Kotlin 的区间习惯一致。 */
    val end: Int,
    val style: MarkupStyle,
)

/**
 * 样式种类。
 *
 * 只给种类，不给具体颜色字号 —— 那是界面层的事，换主题时不该来动这里。
 */
enum class MarkupStyle {
    /** `**这样** `。 */
    Bold,

    /** `*这样*`。角色扮演里用来写动作、神态。 */
    Italic,

    /** 引号包起来的台词。 */
    Quote,

    /** 反引号包起来的内容。 */
    Code,
}

/** 去掉标记之后的文字，加上每一截的样式。 */
data class MarkedText(
    val text: String,
    val spans: List<MarkupSpan> = emptyList(),
)

/**
 * 把正文里的标记解析成样式。
 *
 * 角色扮演的正文有两套排版约定：Markdown 的 `**粗体**` `*斜体*`，
 * 以及引号包起来的台词。斜体在这里尤其重要 —— 大多数卡都用它写动作和神态，
 * 不渲染的话满屏都是星号。
 *
 * ## 为什么自己写而不是引一个 Markdown 库
 *
 * 我们只需要行内的几种标记，不需要标题、列表、表格、链接。
 * 引一个完整实现要多背一个依赖，还会把我们不想要的东西也渲染出来
 * （比如把 `#` 开头的一行变成大标题，而那可能只是正文里的一个井号）。
 *
 * ## 配不上对的标记一律当普通字符
 *
 * 只有开头没有结尾的 `*`，就是一个星号，原样留着。
 * 这一条是吃过亏的：另一处文本处理里「找不到结尾就一路处理到末尾」，
 * 结果把后面的正文全吞了。**先把几种合法形态列全，再动手。**
 */
object InlineMarkup {

    /** 成对的引号。前一个是开头，后一个是结尾。 */
    private val QUOTE_PAIRS = listOf(
        '「' to '」',
        '『' to '』',
        '“' to '”',
        '"' to '"',
    )

    fun parse(raw: String): MarkedText {
        if (raw.isEmpty()) return MarkedText(raw)

        val output = StringBuilder()
        val spans = mutableListOf<MarkupSpan>()
        // 已经遇到开头、还在等结尾的标记。记下它在**输出结果**里的位置，
        // 因为标记本身不会进输出，原文位置和输出位置对不上
        var boldStart: Int? = null
        var italicStart: Int? = null
        var codeStart: Int? = null

        var index = 0
        while (index < raw.length) {
            val char = raw[index]

            // 两个星号优先于一个：先判长的，否则 `**` 会被当成两个斜体标记
            if (char == '*' && index + 1 < raw.length && raw[index + 1] == '*') {
                val start = boldStart
                if (start == null) {
                    boldStart = output.length
                } else {
                    if (output.length > start) spans += MarkupSpan(start, output.length, MarkupStyle.Bold)
                    boldStart = null
                }
                index += 2
                continue
            }

            if (char == '*') {
                val start = italicStart
                if (start == null) {
                    italicStart = output.length
                } else {
                    if (output.length > start) spans += MarkupSpan(start, output.length, MarkupStyle.Italic)
                    italicStart = null
                }
                index += 1
                continue
            }

            if (char == '`') {
                val start = codeStart
                if (start == null) {
                    codeStart = output.length
                } else {
                    if (output.length > start) spans += MarkupSpan(start, output.length, MarkupStyle.Code)
                    codeStart = null
                }
                index += 1
                continue
            }

            output.append(char)
            index += 1
        }

        // 只有开头没有结尾的标记：把那个符号补回原位，当普通字符看待。
        // 直接丢掉的话，用户写的星号会凭空消失
        restoreUnclosed(output, listOfNotNull(boldStart?.let { it to "**" }, italicStart?.let { it to "*" }, codeStart?.let { it to "`" }))

        spans += findQuoteSpans(output.toString())
        return MarkedText(output.toString(), spans.sortedBy { it.start })
    }

    /**
     * 把没配上对的标记符号插回去。
     *
     * 从后往前插，否则前面插进去的字符会把后面记下的位置整体顶偏。
     */
    private fun restoreUnclosed(output: StringBuilder, unclosed: List<Pair<Int, String>>) {
        unclosed.sortedByDescending { it.first }.forEach { (position, marker) ->
            output.insert(position.coerceAtMost(output.length), marker)
        }
    }

    /**
     * 找出引号包起来的台词。
     *
     * 引号本身要留在文字里 —— 它是中文排版的一部分，去掉之后读起来反而别扭。
     * 所以这里只标范围，不删字符。
     */
    private fun findQuoteSpans(text: String): List<MarkupSpan> {
        val spans = mutableListOf<MarkupSpan>()
        var index = 0
        while (index < text.length) {
            val pair = QUOTE_PAIRS.firstOrNull { it.first == text[index] }
            if (pair == null) {
                index += 1
                continue
            }
            // 直引号的开头和结尾是同一个字符，所以要从下一位开始找
            val closing = text.indexOf(pair.second, startIndex = index + 1)
            if (closing < 0) {
                // 没有结尾的引号很常见（正在流式输出、或者作者就没闭合），
                // 当普通字符处理，不要把后面整段都标成台词
                index += 1
                continue
            }
            spans += MarkupSpan(index, closing + 1, MarkupStyle.Quote)
            index = closing + 1
        }
        return spans
    }
}
