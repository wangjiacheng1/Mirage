package com.mirage.engine.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 两次调用的用量相加。一轮里正文一次、状态栏结算一次，要能算出总账。 */
class TokenUsageSumTest {

    @Test
    fun 两边都有数字时逐项相加() {
        val narrative = TokenUsage(promptTokens = 800, completionTokens = 200, totalTokens = 1000, cost = 0.001)
        val settlement = TokenUsage(promptTokens = 300, completionTokens = 100, totalTokens = 400, cost = 0.0004)

        val sum = narrative + settlement

        assertEquals(1100, sum.promptTokens)
        assertEquals(300, sum.completionTokens)
        assertEquals(1400, sum.totalTokens)
        assertEquals(0.0014, sum.cost!!, 1e-9)
    }

    @Test
    fun 两边都没有的字段仍然是没有() {
        val sum = TokenUsage(totalTokens = 100) + TokenUsage(totalTokens = 50)

        assertEquals(150, sum.totalTokens)
        // 谁都没给思考和费用，就不该凭空出现一个 0 —— 那会被当成「确实没花钱」
        assertNull(sum.reasoningTokens)
        assertNull(sum.cost)
    }

    @Test
    fun 只有一边有数字时用那一边的() {
        val sum = TokenUsage(totalTokens = 100, cost = 0.002) + TokenUsage()

        assertEquals(100, sum.totalTokens)
        assertEquals(0.002, sum.cost!!, 1e-9)
    }

    @Test
    fun 一边只给了两侧明细也要算进总数() {
        // 有的服务商不给 total，只给 prompt 和 completion。
        // 不先补总数的话，这一边会被当成 0
        val withTotal = TokenUsage(totalTokens = 1000)
        val detailOnly = TokenUsage(promptTokens = 300, completionTokens = 100)

        assertEquals(1400, (withTotal + detailOnly).totalTokens)
    }

    @Test
    fun 和空用量相加不改变原值() {
        val narrative = TokenUsage(promptTokens = 800, completionTokens = 200, totalTokens = 1000)

        assertEquals(narrative, narrative + TokenUsage())
    }

    @Test
    fun 两个空用量相加还是空() {
        assertEquals(true, (TokenUsage() + TokenUsage()).isEmpty)
    }

    @Test
    fun 思考部分也要相加() {
        val sum = TokenUsage(totalTokens = 1000, reasoningTokens = 400) +
            TokenUsage(totalTokens = 200, reasoningTokens = 50)

        assertEquals(1200, sum.totalTokens)
        assertEquals(450, sum.reasoningTokens)
    }
}
