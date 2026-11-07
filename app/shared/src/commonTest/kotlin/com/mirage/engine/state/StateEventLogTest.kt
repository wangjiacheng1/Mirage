package com.mirage.engine.state

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 状态变化记录的单测。
 *
 * 这一组用例守的是「状态栏存成变化记录」这个设计本身：
 * 切分支数值要跟着走、回到更早一轮数值要回得去、重放两遍结果要一样。
 * 这三条只要有一条不成立，这套存法就没有意义。
 */
class StateEventLogTest {

    private val initial = mapOf(
        "某组/甲" to StateValue.IntValue(60),
        "某组/乙" to StateValue.StringValue("门厅"),
    )

    private fun event(
        sequence: Long,
        messageId: Long,
        revision: Long,
        path: String,
        before: StateValue,
        after: StateValue,
    ) = StateEvent(
        sequence = sequence,
        messageId = messageId,
        revision = revision,
        path = path,
        operation = ChangeOperation.Set,
        before = before,
        after = after,
    )

    //  线一：消息 1 → 消息 2
    //  线二：消息 1 → 消息 3（和消息 2 并列的另一个版本）
    private val events = listOf(
        event(1, 1, 1, "某组/甲", StateValue.IntValue(60), StateValue.IntValue(49)),
        event(2, 2, 2, "某组/甲", StateValue.IntValue(49), StateValue.IntValue(30)),
        event(3, 3, 2, "某组/乙", StateValue.StringValue("门厅"), StateValue.StringValue("走廊")),
    )

    @Test
    fun 把变化加一遍得到当前数值() {
        val values = StateEventLog.replay(initial, events)

        assertEquals(StateValue.IntValue(30), values["某组/甲"])
        assertEquals(StateValue.StringValue("走廊"), values["某组/乙"])
    }

    @Test
    fun 顺序乱了也能算对() {
        val shuffled = listOf(events[2], events[0], events[1])

        assertEquals(StateEventLog.replay(initial, events), StateEventLog.replay(initial, shuffled))
    }

    @Test
    fun 切到另一个版本时对面那条线的变化不算数() {
        val lineTwo = StateEventLog.replayOnLine(initial, events, setOf(1L, 3L))

        // 走的是另一条线：消息 2 上那次扣血没有发生过
        assertEquals(StateValue.IntValue(49), lineTwo["某组/甲"])
        assertEquals(StateValue.StringValue("走廊"), lineTwo["某组/乙"])
    }

    @Test
    fun 回到更早一轮数值也回到当时() {
        val atFirst = StateEventLog.replayOnLine(initial, events, setOf(1L))

        assertEquals(StateValue.IntValue(49), atFirst["某组/甲"])
        // 后面那条线上改过的项要回到开局值，而不是停在被改后的样子
        assertEquals(StateValue.StringValue("门厅"), atFirst["某组/乙"])
    }

    @Test
    fun 同一批变化重放两遍结果一样() {
        val once = StateEventLog.replay(initial, events)
        val twice = StateEventLog.replay(initial, events + events)

        // 记录里存的是「改完之后是多少」而不是增减量，所以重放天然幂等。
        // 存增减量的话，多算一遍数值就翻倍了，而且错了无法从结果反推
        assertEquals(once, twice)
    }

    @Test
    fun 从快照往后接着算只处理更晚的变化() {
        val snapshot = mapOf("某组/甲" to StateValue.IntValue(49), "某组/乙" to StateValue.StringValue("门厅"))

        val values = StateEventLog.replayFromSnapshot(
            snapshot = snapshot,
            snapshotRevision = 1,
            events = events,
            lineMessageIds = setOf(1L, 2L),
        )

        assertEquals(StateValue.IntValue(30), values["某组/甲"])
    }

    @Test
    fun 这条线当前是第几版() {
        assertEquals(2L, StateEventLog.revisionOf(events, setOf(1L, 2L)))
        assertEquals(1L, StateEventLog.revisionOf(events, setOf(1L)))
        // 一次变化都没有时和刚开局一致
        assertEquals(0L, StateEventLog.revisionOf(events, emptySet()))
    }

    @Test
    fun 能查出某一项是怎么一步步变过来的() {
        val history = StateEventLog.historyOf(events, "某组/甲", setOf(1L, 2L))

        assertEquals(listOf(1L, 2L), history.map { it.sequence })
        assertEquals(StateValue.IntValue(60), history.first().before)
        assertEquals(StateValue.IntValue(30), history.last().after)
    }

    @Test
    fun 结算结果能转成要存的变化记录() {
        val receipt = SettlementReceipt(
            turn = 1,
            status = SettlementStatus.Updated,
            changes = listOf(
                StateChange(
                    path = "某组/甲",
                    operation = ChangeOperation.Set,
                    before = StateValue.IntValue(60),
                    after = StateValue.IntValue(49),
                    adjustedBy = GuardFailureCode.Clamped,
                ),
            ),
        )

        val produced = StateEventLog.eventsOf(receipt, messageId = 7, startSequence = 100, revision = 3)

        assertEquals(1, produced.size)
        with(produced.first()) {
            assertEquals(100L, sequence)
            assertEquals(7L, messageId)
            assertEquals(3L, revision)
            // 被代码修正过这件事要一起存下来，否则事后看不出这个值动过手脚
            assertEquals(GuardFailureCode.Clamped, adjustedBy)
        }
    }
}
