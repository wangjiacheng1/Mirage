package com.mirage.engine.state

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 状态仓的单测。重点全在**版本号什么时候动、什么时候不动** ——
 * 它是异步结算能不能落盘的唯一依据，判错一次就会出现「旧结果覆盖新状态」。
 */
class StateStoreTest {

    private fun change(path: String, before: Long, after: Long) = StateChange(
        path = path,
        operation = ChangeOperation.Set,
        before = StateValue.IntValue(before),
        after = StateValue.IntValue(after),
    )

    @Test
    fun 初始版本为零() {
        assertEquals(0L, StateStore().revision)
    }

    @Test
    fun 落盘后值更新且版本加一() {
        val store = StateStore(mapOf("甲/乙" to StateValue.IntValue(60)))

        val revision = store.commit(listOf(change("甲/乙", 60, 49)))

        assertEquals(1L, revision)
        assertEquals(StateValue.IntValue(49), store.values.value["甲/乙"])
    }

    @Test
    fun 空变更不推进版本() {
        val store = StateStore(mapOf("甲/乙" to StateValue.IntValue(60)))
        store.commit(listOf(change("甲/乙", 60, 49)))

        val revision = store.commit(emptyList())

        // 「本轮确实没有变化」不是一次变化。给它加版本会让在途任务白白作废
        assertEquals(1L, revision)
    }

    @Test
    fun 快照里的值和版本来自同一时刻() {
        val store = StateStore(mapOf("甲/乙" to StateValue.IntValue(60)))

        val before = store.snapshot()
        store.commit(listOf(change("甲/乙", 60, 49)))

        assertEquals(0L, before.revision)
        // 快照是值的拷贝，后续落盘不应该回头改动已经取走的那一份
        assertEquals(StateValue.IntValue(60), before.values["甲/乙"])
        assertEquals(1L, store.snapshot().revision)
    }

    @Test
    fun 缺席值不会把键删掉() {
        val store = StateStore(mapOf("甲/乙" to StateValue.IntValue(60)))

        store.commit(
            listOf(
                StateChange(
                    path = "甲/乙",
                    operation = ChangeOperation.Set,
                    before = StateValue.IntValue(60),
                    after = StateValue.Absent,
                ),
            ),
        )

        // 键整条消失会让界面上这一行凭空不见，看起来像数据丢了
        assertEquals(StateValue.IntValue(60), store.values.value["甲/乙"])
    }

    @Test
    fun 整体替换会推进版本() {
        val store = StateStore(mapOf("甲/乙" to StateValue.IntValue(60)))

        store.reset(mapOf("丙/丁" to StateValue.StringValue("x")))

        assertEquals(1L, store.revision)
        assertEquals(null, store.values.value["甲/乙"])
        assertEquals(StateValue.StringValue("x"), store.values.value["丙/丁"])
    }
}
