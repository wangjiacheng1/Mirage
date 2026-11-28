package com.mirage.engine.prompt

import com.mirage.engine.llm.ChatMessage
import com.mirage.engine.llm.ChatRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 长度预算与历史裁剪的单测。
 *
 * 重点是「该丢的丢、不该丢的一条都不能少」——
 * 设定被丢掉的后果是人设当场塌掉，比报错还难察觉。
 */
class TokenBudgetTest {

    private fun user(text: String) = ChatMessage(ChatRole.User, text)
    private fun assistant(text: String) = ChatMessage(ChatRole.Assistant, text)
    private fun system(text: String) = ChatMessage(ChatRole.System, text)

    @Test
    fun 汉字按一点五个估算() {
        // 5 个汉字 = 7.5，向上取整 8。
        // 按一字一个算会低估三成，而中文是主场景 —— 低估的后果是请求被服务端打回
        assertEquals(8, TokenBudget.estimate("今天天气好"))
    }

    @Test
    fun 英文若干字符才算一个() {
        // 9 个窄字符 → 3 个
        assertEquals(3, TokenBudget.estimate("abcdefghi"))
    }

    @Test
    fun 不足一组的窄字符也算一个() {
        // 宁可多算：估小了是整轮请求报错，估大了只是少带两轮历史
        assertEquals(1, TokenBudget.estimate("a"))
    }

    @Test
    fun 空串是零() {
        assertEquals(0, TokenBudget.estimate(""))
    }

    @Test
    fun 预算要扣掉回复空间和安全余量() {
        val budget = TokenBudget.budgetOf(32_000)

        assertEquals(32_000 - TokenBudget.RESERVED_FOR_REPLY - TokenBudget.SAFETY_MARGIN, budget)
    }

    @Test
    fun 装得下时一条都不丢() {
        val result = TokenBudget.fit(
            leading = listOf(system("角色设定")),
            history = listOf(user("第一句"), assistant("第一答")),
            tail = listOf(user("新问题")),
            budget = 1_000,
        )

        assertEquals(0, result.droppedCount)
        assertEquals(4, result.messages.size)
    }

    @Test
    fun 装不下时从最旧的开始丢() {
        val result = TokenBudget.fit(
            leading = listOf(system("设定")),
            history = listOf(user("最旧的一句"), assistant("中间的一句"), user("最近的一句")),
            tail = listOf(user("新问题")),
            // 只够放设定 + 新问题 + 一条历史
            budget = 30,
        )

        assertEquals(2, result.droppedCount)
        // 留下的必须是最近的那条 —— 越近的内容对当前剧情越要紧
        assertEquals("最近的一句", result.messages[1].content)
    }

    @Test
    fun 设定和本轮输入永远不丢() {
        val result = TokenBudget.fit(
            leading = listOf(system("很长的角色设定很长的角色设定很长的角色设定")),
            history = listOf(user("历史一"), assistant("历史二")),
            tail = listOf(user("这一轮的问题"), system("队尾要求")),
            // 给一个光设定都装不下的预算
            budget = 5,
        )

        // 丢什么都没用的时候，照样把不能丢的发出去，让服务端报真实原因
        assertEquals("很长的角色设定很长的角色设定很长的角色设定", result.messages.first().content)
        assertEquals("这一轮的问题", result.messages[result.messages.size - 2].content)
        assertEquals("队尾要求", result.messages.last().content)
        assertEquals(2, result.droppedCount)
    }

    @Test
    fun 历史为空时不报丢弃() {
        val result = TokenBudget.fit(
            leading = listOf(system("设定")),
            history = emptyList(),
            tail = listOf(user("问题")),
            budget = 1_000,
        )

        assertEquals(0, result.droppedCount)
    }

    @Test
    fun 估算结果不小于各段之和的下限() {
        val messages = listOf(system("设定"), user("问题"))

        // 每条有固定开销（角色名、分隔符），所以总量一定大于纯文字量
        assertTrue(TokenBudget.estimate(messages) > TokenBudget.estimate("设定问题"))
    }
}

/**
 * 上下文占用的分段统计。
 *
 * 这些数字直接画成界面上那条占用条。最要紧的一条是**分项加起来必须等于总数** ——
 * 对不上的话，条上会出现一截不属于任何一段的空白，而用户只会觉得「这个数不准」。
 */
class TokenBreakdownTest {

    private fun user(text: String) = ChatMessage(ChatRole.User, text)
    private fun assistant(text: String) = ChatMessage(ChatRole.Assistant, text)
    private fun system(text: String) = ChatMessage(ChatRole.System, text)

    @Test
    fun 分项加起来等于总数() {
        val fit = TokenBudget.fit(
            leading = listOf(system("角色设定".repeat(20))),
            history = listOf(user("第一句"), assistant("第一答"), user("第二句")),
            tail = listOf(user("这一轮说的话")),
            examples = listOf(user("示范问"), assistant("示范答")),
            budget = 10_000,
        )

        assertEquals(fit.estimatedTokens, fit.breakdown.total)
    }

    @Test
    fun 四段分别算在各自名下() {
        val fit = TokenBudget.fit(
            leading = listOf(system("设定".repeat(50))),
            history = listOf(user("历史".repeat(30))),
            tail = listOf(user("本轮")),
            budget = 10_000,
        )

        assertTrue(fit.breakdown.leading > 0)
        assertTrue(fit.breakdown.history > 0)
        assertTrue(fit.breakdown.tail > 0)
        // 没传示范，这一段就该是 0
        assertEquals(0, fit.breakdown.examples)
    }

    @Test
    fun 示范被整组丢掉时它那一段是零() {
        // 预算只够放设定和本轮，示范整组丢掉 —— 那它就不该在条上占地方
        val fit = TokenBudget.fit(
            leading = listOf(system("设定".repeat(300))),
            history = listOf(user("历史".repeat(100))),
            tail = listOf(user("本轮")),
            examples = listOf(user("示范问".repeat(100)), assistant("示范答".repeat(100))),
            budget = 1_200,
        )

        assertTrue(fit.droppedExamples)
        assertEquals(0, fit.breakdown.examples)
        assertEquals(fit.estimatedTokens, fit.breakdown.total)
    }

    @Test
    fun 历史被裁掉之后那一段跟着变小() {
        val history = (1..40).map { user("第 $it 句".repeat(20)) }

        val loose = TokenBudget.fit(leading = emptyList(), history = history, tail = emptyList(), budget = 100_000)
        val tight = TokenBudget.fit(leading = emptyList(), history = history, tail = emptyList(), budget = 2_000)

        assertTrue(tight.breakdown.history < loose.breakdown.history)
        // 裁过之后也要对得上，不能只有总数变了而分项没动
        assertEquals(tight.estimatedTokens, tight.breakdown.total)
    }

    @Test
    fun 预算也要带出来给界面算占比() {
        val fit = TokenBudget.fit(
            leading = listOf(system("设定")),
            history = emptyList(),
            tail = emptyList(),
            budget = 8_888,
        )

        assertEquals(8_888, fit.breakdown.budget)
    }

    @Test
    fun 占比按预算算() {
        val breakdown = TokenBreakdown(leading = 1_000, history = 1_000, budget = 10_000)

        assertEquals(20, breakdown.usedPercent)
    }

    @Test
    fun 超出预算时占比可以大于一百() {
        // 光是不能丢的部分就超了预算时我们照样发出去（那时丢什么都没用）。
        // 这里夹在 100 以内就看不出到底超了多少
        val breakdown = TokenBreakdown(leading = 15_000, budget = 10_000)

        assertEquals(150, breakdown.usedPercent)
    }

    @Test
    fun 没有预算时占比是零而不是崩掉() {
        assertEquals(0, TokenBreakdown(leading = 100, budget = 0).usedPercent)
    }
}
