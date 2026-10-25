package com.mirage.engine.card

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 清状态块的单测。
 *
 * 用例形状照着真卡：开场消息末尾跟着一整段初始数值标签块，再跟一个自闭合的
 * 面板占位标签。这正是实测中让模型学会「在正文里写状态块」的那个源头。
 */
class CardTextSanitizerTest {

    @Test
    fun 开场末尾的初值块和面板占位都被清掉() {
        val text = """
            铁门在身后合上。

            <initvar>
            {"某组": {"甲": 60}}
            </initvar>

            <mvu-status/>
        """.trimIndent()

        assertEquals("铁门在身后合上。", CardTextSanitizer.stripStatusBlocks(text))
    }

    @Test
    fun 模型自己吐出来的成对状态块也清掉() {
        val text = "正文内容。\n\n<mvu-status>\n{\"某组\":{\"甲\":1}}\n</mvu-status>"

        assertEquals("正文内容。", CardTextSanitizer.stripStatusBlocks(text))
    }

    @Test
    fun 只有开标签的残缺块从那里清到结尾() {
        // 流式输出被中途打断时会出现这种。留着半个块，模型下一轮照样会学
        val text = "正文内容。\n\n<mvu-status>\n{\"某组\":{\"甲\""

        assertEquals("正文内容。", CardTextSanitizer.stripStatusBlocks(text))
    }

    @Test
    fun 正文里正常的尖括号内容不受影响() {
        val text = "他在纸上写下「a < b > c」，又划掉了。"

        // 清过头比漏清更糟：漏清只是多一行字，清过头会把故事本身吃掉
        assertEquals(text, CardTextSanitizer.stripStatusBlocks(text))
    }

    @Test
    fun 清完之后不留成片空行() {
        val text = "第一段。\n\n<mvu-status/>\n\n\n\n第二段。"

        assertEquals("第一段。\n\n第二段。", CardTextSanitizer.stripStatusBlocks(text))
    }

    @Test
    fun 能判断一段文字里有没有状态块() {
        assertTrue(CardTextSanitizer.containsStatusBlock("正文 <mvu-status/>"))
        assertTrue(!CardTextSanitizer.containsStatusBlock("普通正文"))
    }

    @Test
    fun 没有状态块时原样返回() {
        assertEquals("普通正文", CardTextSanitizer.stripStatusBlocks("普通正文"))
    }
}
