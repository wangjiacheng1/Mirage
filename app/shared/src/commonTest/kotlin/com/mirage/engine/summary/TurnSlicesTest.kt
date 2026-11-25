package com.mirage.engine.summary

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 按轮次编号在对话记录里找下标。
 *
 * 下面的 turns 列表就是一条对话记录每一项的轮次编号：
 * null 是用户说的那句（不带编号），数字是 AI 的回复。
 * 所以一轮在记录里是「null, N」这样两条。
 */
class TurnSlicesTest {

    /** 三轮对话：用户、回复1、用户、回复2、用户、回复3。 */
    private val threeTurns = listOf(null, 1, null, 2, null, 3)

    @Test
    fun 找编号不超过某一轮的最后一条() {
        // 第 2 轮那条回复在下标 3
        assertEquals(3, TurnSlices.lastIndexUpTo(threeTurns, 2))
        assertEquals(1, TurnSlices.lastIndexUpTo(threeTurns, 1))
        assertEquals(5, TurnSlices.lastIndexUpTo(threeTurns, 3))
    }

    @Test
    fun 没有任何一条属于那一轮时返回负一() {
        assertEquals(-1, TurnSlices.lastIndexUpTo(listOf(null, null), 5))
        assertEquals(-1, TurnSlices.lastIndexUpTo(emptyList(), 1))
    }

    @Test
    fun 要压的那一段连用户说的那句一起带走() {
        // 压第 1 到 2 轮，应该是下标 0 到 3（两轮各两条），
        // 不能只取两条回复 —— 只有回复没有提问，摘要会不知道事情是怎么起来的
        assertEquals(0..3, TurnSlices.indicesOf(threeTurns, 1, 2))
    }

    @Test
    fun 从中间某一轮开始的区间起点是上一轮之后() {
        // 压第 2 到 3 轮：第 1 轮那两条（下标 0、1）不能带进来
        assertEquals(2..5, TurnSlices.indicesOf(threeTurns, 2, 3))
    }

    @Test
    fun 第一轮的区间从开头算起() {
        // 开场消息不带轮次编号，但它是第 1 轮的一部分，必须跟着一起压
        val withOpening = listOf(null, null, 1, null, 2)

        assertEquals(0..2, TurnSlices.indicesOf(withOpening, 1, 1))
    }

    @Test
    fun 单独一轮的区间() {
        assertEquals(2..3, TurnSlices.indicesOf(threeTurns, 2, 2))
    }

    @Test
    fun 编号断开时起点仍然算得对() {
        // 当前线是 1、3、4（第 2 轮被重新生成了，旧的那条在别的分支上）。
        // 要压第 3 到 4 轮：起点该是 turn=3 那一轮的用户消息，
        // 而不是因为「找不到第 2 轮」就退回开头
        val gapped = listOf(null, 1, null, 3, null, 4)

        assertEquals(2..5, TurnSlices.indicesOf(gapped, 3, 4))
    }

    @Test
    fun 这条线开头的编号不是一也从头算起() {
        // 开头那一轮被重新生成过，于是这条线上最小的编号是 3。
        // 压第 3 轮时前面没有更小的编号，起点就是 0
        val startsAtThree = listOf(null, 3, null, 4)

        assertEquals(0..1, TurnSlices.indicesOf(startsAtThree, 3, 3))
    }

    @Test
    fun 要的轮次还没发生时返回空() {
        assertNull(TurnSlices.indicesOf(threeTurns, 5, 8))
    }

    @Test
    fun 起点算出来跑到终点后面时返回空() {
        // 只有第 3 轮存在，却要第 1 到 2 轮 —— 这一段在记录里不存在
        val onlyThird = listOf(null, 3)

        assertNull(TurnSlices.indicesOf(onlyThird, 1, 2))
    }
}
