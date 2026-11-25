package com.mirage.engine.prompt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 对话示例拆分的单测。
 *
 * 用例形状照着真卡：`<START>` 分隔、`{{user}}:` / `{{char}}:` 标记、
 * 台词后面跟着好几段叙述。
 */
class ExampleDialogueParserTest {

    private fun parse(raw: String) = ExampleDialogueParser.parse(raw, userName = "你", charName = "某角色")

    @Test
    fun 按分隔符拆成互不相干的几段() {
        val blocks = parse(
            """
            <START>
            {{user}}: 第一段的问
            {{char}}: 第一段的答
            <START>
            {{user}}: 第二段的问
            {{char}}: 第二段的答
            """.trimIndent(),
        )

        // 两段是两次独立对话，不是一段连续聊天
        assertEquals(2, blocks.size)
        assertEquals(2, blocks[0].size)
    }

    @Test
    fun 分清楚哪句是玩家说的() {
        val block = parse("<START>\n{{user}}: 你笑什么？\n{{char}}: 笑你站得直。").single()

        assertEquals(true, block[0].fromUser)
        assertEquals("你笑什么？", block[0].text)
        assertEquals(false, block[1].fromUser)
    }

    @Test
    fun 直接写名字的也认() {
        val block = parse("<START>\n你: 问一句\n某角色: 答一句").single()

        assertEquals(true, block[0].fromUser)
        assertEquals(false, block[1].fromUser)
    }

    @Test
    fun 认不出的名字当成上一句的续行() {
        val block = parse("<START>\n{{user}}: 问\n路人甲: 插一句嘴").single()

        // 「冒号前面有几个字」和「它是不是人名」没有关系，猜不准的事就不猜。
        // 代价是配角台词不单独成句，但内容一个字都没丢
        assertEquals(1, block.size)
        assertTrue(block[0].text.contains("路人甲"))
    }

    @Test
    fun 台词后面的多段叙述接在同一句上() {
        val block = parse(
            """
            <START>
            {{char}}: 「怕啊。」她答得很快。

            她又补了一句，声音低下去。
            """.trimIndent(),
        ).single()

        assertEquals(1, block.size)
        assertTrue(block[0].text.contains("她又补了一句"), "续行必须接在上一句后面，不能丢")
    }

    @Test
    fun 中文冒号也认() {
        val block = parse("<START>\n{{user}}：中文冒号").single()

        assertEquals(true, block[0].fromUser)
        assertEquals("中文冒号", block[0].text)
    }

    @Test
    fun 台词中间的冒号不会被当成换人说话() {
        val block = parse("<START>\n{{char}}: 他报了个时间：戌时三刻。").single()

        // 只认行首的说话人，否则一句带冒号的台词会被拆成两句
        assertEquals(1, block.size)
        assertEquals("他报了个时间：戌时三刻。", block[0].text)
    }

    @Test
    fun 没有说话人标记时返回空交给调用方退回原样发送() {
        // 宁可没拆成，也不能把内容弄丢
        assertEquals(emptyList(), parse("这就是一段没有任何标记的说明文字。"))
    }

    @Test
    fun 没有分隔符但有标记的也能拆() {
        val blocks = parse("{{user}}: 问\n{{char}}: 答")

        assertEquals(1, blocks.size)
        assertEquals(2, blocks.single().size)
    }

    @Test
    fun 空内容返回空() {
        assertEquals(emptyList(), parse(""))
        assertEquals(emptyList(), parse("   "))
    }

    @Test
    fun 叙述里的冒号不会被误判成说话人() {
        val block = parse("<START>\n{{char}}: 开头\n他报了个时间：戌时三刻。")

        // 中文叙述里带冒号太常见，这是最早一版栽过的地方
        assertEquals(1, block.single().size)
        assertTrue(block.single()[0].text.contains("戌时三刻"))
    }
}
