package com.mirage.engine.text

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 正文标记解析的单测。
 *
 * 重点在「配不上对的标记不能吃掉正文」—— 另一处文本处理正是栽在这上面，
 * 当时「找不到结尾就处理到末尾」，把后面的内容全删了。
 */
class InlineMarkupTest {

    private fun styles(raw: String) = InlineMarkup.parse(raw).spans.map { it.style }

    private fun textOf(raw: String) = InlineMarkup.parse(raw).text

    @Test
    fun 斜体标记被去掉并记下范围() {
        val result = InlineMarkup.parse("他*皱了皱眉*，没说话。")

        assertEquals("他皱了皱眉，没说话。", result.text)
        assertEquals(listOf(MarkupSpan(1, 5, MarkupStyle.Italic)), result.spans)
    }

    @Test
    fun 粗体优先于斜体() {
        val result = InlineMarkup.parse("**很重要**")

        // 先判两个星号，否则 ** 会被拆成两个斜体标记，范围全错
        assertEquals("很重要", result.text)
        assertEquals(listOf(MarkupStyle.Bold), result.spans.map { it.style })
    }

    @Test
    fun 粗体和斜体可以同时出现() {
        assertEquals(
            listOf(MarkupStyle.Bold, MarkupStyle.Italic),
            styles("**重**然后*轻*").sorted(),
        )
    }

    @Test
    fun 只有开头没有结尾的星号当普通字符留着() {
        // 直接吞掉的话，用户写的星号会凭空消失
        assertEquals("3 * 5 等于多少", textOf("3 * 5 等于多少"))
        assertEquals(emptyList(), InlineMarkup.parse("3 * 5 等于多少").spans)
    }

    @Test
    fun 落单的双星号也原样留着() {
        assertEquals("看这里 ** 没闭合", textOf("看这里 ** 没闭合"))
    }

    @Test
    fun 中文引号里的台词被标出来且引号保留() {
        val result = InlineMarkup.parse("他说「跟我来」。")

        // 引号是中文排版的一部分，去掉反而别扭，所以只标范围不删字符
        assertEquals("他说「跟我来」。", result.text)
        assertEquals(listOf(MarkupSpan(2, 7, MarkupStyle.Quote)), result.spans)
    }

    @Test
    fun 英文直引号也认() {
        assertEquals(listOf(MarkupStyle.Quote), styles("""他说 "come on" 。"""))
    }

    @Test
    fun 没有闭合的引号不把后面整段吞掉() {
        val result = InlineMarkup.parse("他说「跟我来，然后走了。")

        assertEquals("他说「跟我来，然后走了。", result.text)
        assertEquals(emptyList(), result.spans)
    }

    @Test
    fun 反引号内容标成代码() {
        assertEquals(listOf(MarkupStyle.Code), styles("输入 `help` 看说明"))
    }

    @Test
    fun 普通文字原样返回() {
        assertEquals("就是一段普通的话。", textOf("就是一段普通的话。"))
        assertEquals(emptyList(), InlineMarkup.parse("就是一段普通的话。").spans)
    }

    @Test
    fun 空串不出错() {
        assertEquals("", textOf(""))
    }

    @Test
    fun 换行保留() {
        assertEquals("第一段\n\n第二段", textOf("第一段\n\n第二段"))
    }

    @Test
    fun 空标记不产生范围() {
        // ** 紧挨着 ** 中间没内容，标成粗体没有意义
        assertEquals(emptyList(), InlineMarkup.parse("****").spans)
    }
}
