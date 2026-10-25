package com.mirage.engine.settlement

import com.mirage.engine.state.ChangeOperation
import com.mirage.engine.state.SettlementReceipt
import com.mirage.engine.state.SettlementStatus
import com.mirage.engine.state.StateChange
import com.mirage.engine.state.StateSchema
import com.mirage.engine.state.StateValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 结算队列的单测。
 *
 * 这里验的全是时序问题：同时来两轮、结果回来时前提已经变了、跑一半被叫停。
 * 这些在真机上极难复现，但一旦发生就是数值错乱，所以必须在这一层钉死。
 * 把「调模型」抽成一个可控的挂起函数之后，这些时序可以被精确摆出来。
 *
 * ⚠️ 推进虚拟时间一律用 runCurrent()。队列的工作协程是一个永不结束的循环，
 * 而「推进到空闲」的那个方法会把这类长驻协程排除在外 —— 用它推进的话，
 * 队列里的任务一个都不会被执行，测试会以「什么都没发生」的形式失败，
 * 看起来像实现有问题，实际是测试没把协程推起来。
 */
// runCurrent 目前仍标注为实验性接口，这里统一声明一次，避免每个用例各自标注
@OptIn(ExperimentalCoroutinesApi::class)
class SettlementQueueTest {

    private fun task(turn: Int, revision: Long = 0L) = SettlementTask(
        turn = turn,
        basedOnRevision = revision,
        narrative = "第 $turn 轮的内容",
        stateSnapshot = mapOf("甲/乙" to StateValue.IntValue(60)),
        schema = StateSchema(fields = emptyMap()),
    )

    private fun updated(turn: Int, revision: Long = 0L) = SettlementOutcome(
        turn = turn,
        basedOnRevision = revision,
        receipt = SettlementReceipt(
            turn = turn,
            status = SettlementStatus.Updated,
            changes = listOf(
                StateChange(
                    path = "甲/乙",
                    operation = ChangeOperation.Set,
                    before = StateValue.IntValue(60),
                    after = StateValue.IntValue(49),
                ),
            ),
        ),
    )

    @Test
    fun 同时提交两轮时第二轮必须等第一轮跑完() = runTest {
        val started = mutableListOf<Int>()
        val gate = CompletableDeferred<Unit>()
        val queue = SettlementQueue(
            scope = backgroundScope,
            revisionOf = { 0L },
            commit = {},
            runner = { t ->
                started += t.turn
                if (t.turn == 1) gate.await()
                updated(t.turn)
            },
        )

        queue.submit(task(1))
        queue.submit(task(2))
        runCurrent()

        // 第二轮不能提前开跑 —— 两份结果会争着改同一份状态
        assertEquals(listOf(1), started)

        gate.complete(Unit)
        runCurrent()

        assertEquals(listOf(1, 2), started)
        assertEquals(
            listOf(SettlementStatus.Updated, SettlementStatus.Updated),
            queue.records.value.map { it.status },
        )
    }

    @Test
    fun 版本未变时结果落盘() = runTest {
        val committed = mutableListOf<SettlementReceipt>()
        val queue = SettlementQueue(
            scope = backgroundScope,
            revisionOf = { 0L },
            commit = { committed += it },
            runner = { updated(it.turn) },
        )

        queue.submit(task(1))
        runCurrent()

        assertEquals(1, committed.size)
        with(queue.recordOf(1)!!) {
            assertEquals(SettlementPhase.Settled, phase)
            assertEquals(SettlementStatus.Updated, status)
        }
    }

    @Test
    fun 版本已变时结果作废且不落盘() = runTest {
        var revision = 0L
        val committed = mutableListOf<SettlementReceipt>()
        val queue = SettlementQueue(
            scope = backgroundScope,
            revisionOf = { revision },
            commit = { committed += it },
            runner = { t ->
                // 模型还在跑的时候，状态被别的动作改掉了
                revision = 7L
                updated(t.turn)
            },
        )

        queue.submit(task(turn = 1, revision = 0L))
        runCurrent()

        assertEquals(emptyList(), committed, "过期结果绝不能落盘，否则会盖掉更晚发生的变化")
        with(queue.recordOf(1)!!) {
            assertEquals(SettlementStatus.Stale, status)
            // 变化必须被整体清空：部分采纳会产生一个谁也说不清来历的中间状态
            assertEquals(emptyList(), outcome.receipt.changes)
            assertTrue(retryable)
        }
    }

    @Test
    fun 中断正在跑的一轮后队列继续处理下一轮() = runTest {
        val started = mutableListOf<Int>()
        val queue = SettlementQueue(
            scope = backgroundScope,
            revisionOf = { 0L },
            commit = {},
            runner = { t ->
                started += t.turn
                // 第一轮永远不返回，用来模拟卡住之后被使用者叫停
                if (t.turn == 1) CompletableDeferred<Unit>().await()
                updated(t.turn)
            },
        )

        queue.submit(task(1))
        queue.submit(task(2))
        runCurrent()
        assertEquals(SettlementPhase.Running, queue.recordOf(1)!!.phase)

        queue.cancelActive()
        runCurrent()

        assertEquals(SettlementStatus.Interrupted, queue.recordOf(1)!!.status)
        // 只停这一个，整条队列不能跟着停 —— 否则之后每一轮都悄悄不结算了
        assertEquals(listOf(1, 2), started)
        assertEquals(SettlementStatus.Updated, queue.recordOf(2)!!.status)
    }

    @Test
    fun 重试会原地替换同一轮的记录() = runTest {
        var attemptCount = 0
        val queue = SettlementQueue(
            scope = backgroundScope,
            revisionOf = { 0L },
            commit = {},
            runner = { t ->
                attemptCount++
                if (attemptCount == 1) {
                    SettlementOutcome(
                        turn = t.turn,
                        basedOnRevision = t.basedOnRevision,
                        receipt = SettlementReceipt(turn = t.turn, status = SettlementStatus.Error),
                    )
                } else {
                    updated(t.turn)
                }
            },
        )

        queue.submit(task(1))
        queue.submit(task(2))
        runCurrent()
        assertEquals(SettlementStatus.Error, queue.recordOf(1)!!.status)

        queue.submit(task(1))
        runCurrent()

        with(queue.recordOf(1)!!) {
            assertEquals(SettlementStatus.Updated, status)
            assertEquals(2, attempt)
        }
        // 位置不能变：卡片在界面上跳走会让人以为看到的是另一轮的结果
        assertEquals(listOf(1, 2), queue.records.value.map { it.turn })
    }

    @Test
    fun 实现本身抛异常不会让队列停摆() = runTest {
        val started = mutableListOf<Int>()
        val queue = SettlementQueue(
            scope = backgroundScope,
            revisionOf = { 0L },
            commit = {},
            runner = { t ->
                started += t.turn
                if (t.turn == 1) error("模拟实现缺陷")
                updated(t.turn)
            },
        )

        queue.submit(task(1))
        queue.submit(task(2))
        runCurrent()

        assertEquals(SettlementStatus.Error, queue.recordOf(1)!!.status)
        assertEquals(listOf(1, 2), started)
        assertEquals(SettlementStatus.Updated, queue.recordOf(2)!!.status)
    }

    @Test
    fun 关闭后不再执行队列里剩下的任务() = runTest {
        val started = mutableListOf<Int>()
        val queue = SettlementQueue(
            scope = backgroundScope,
            revisionOf = { 0L },
            commit = {},
            runner = { t -> started += t.turn; updated(t.turn) },
        )

        queue.close()
        queue.submit(task(1))
        runCurrent()

        assertEquals(emptyList(), started)
    }
}
