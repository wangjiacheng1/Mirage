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
    fun 中文按一字一个估算() {
        assertEquals(5, TokenBudget.estimate("今天天气好"))
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
