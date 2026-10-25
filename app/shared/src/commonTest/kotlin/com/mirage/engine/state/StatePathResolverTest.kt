package com.mirage.engine.state

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 路径归一的单测。
 *
 * 这一块是实测炸出来的：模型按另一套写法提交路径（点号分隔、带容器前缀），
 * 结果整轮的数值变化被当成「这些项不存在」全部挡掉，界面上只剩「结算失败」。
 */
class StatePathResolverTest {

    private val schema = StateSchema(
        fields = mapOf(
            "某组/甲" to FieldSpec(type = FieldType.Int),
            "某组/乙" to FieldSpec(type = FieldType.String),
        ),
    )

    private fun resolve(vararg pairs: Pair<String, StateValue>) =
        StatePathResolver.resolve(pairs.toMap(), schema)

    @Test
    fun 本来就对的路径原样通过() {
        val r = resolve("某组/甲" to StateValue.IntValue(1))

        assertEquals(mapOf("某组/甲" to StateValue.IntValue(1)), r.values)
        assertEquals(emptyMap(), r.remapped)
    }

    @Test
    fun 点号写法被改成斜杠() {
        val r = resolve("某组.甲" to StateValue.IntValue(1))

        assertEquals(StateValue.IntValue(1), r.values["某组/甲"])
        assertEquals(mapOf("某组.甲" to "某组/甲"), r.remapped)
    }

    @Test
    fun 带容器前缀的写法前缀被去掉() {
        val r = resolve("stat_data.某组.甲" to StateValue.IntValue(2))

        assertEquals(StateValue.IntValue(2), r.values["某组/甲"])
    }

    @Test
    fun 首尾多余的分隔符和空白被去掉() {
        val r = resolve("  /某组/甲  " to StateValue.IntValue(3))

        assertEquals(StateValue.IntValue(3), r.values["某组/甲"])
    }

    @Test
    fun 对不上的路径原样留下交给后面的检查去挡() {
        val r = resolve("某组/丙" to StateValue.IntValue(1))

        // 这里只负责认领写法，不负责判定允不允许改 —— 那是检查环节的职责
        assertEquals(StateValue.IntValue(1), r.values["某组/丙"])
        assertEquals(listOf("某组/丙"), r.unresolved)
    }

    @Test
    fun 绝不按相似度去猜一个最像的() {
        val r = resolve("某组/甲甲" to StateValue.IntValue(9))

        // 猜错会把数值悄悄写到另一项上，这种错几乎不可能被发现
        assertEquals(null, r.values["某组/甲"])
        assertEquals(listOf("某组/甲甲"), r.unresolved)
    }

    @Test
    fun 同一项被两种写法各提一次时保留先到的() {
        val r = StatePathResolver.resolve(
            linkedMapOf(
                "某组/甲" to StateValue.IntValue(1),
                "某组.甲" to StateValue.IntValue(2),
            ),
            schema,
        )

        // 后到的覆盖先到的话，最终用哪个值取决于遍历顺序，那是不可复现的
        assertEquals(StateValue.IntValue(1), r.values["某组/甲"])
        assertEquals(listOf("某组.甲"), r.unresolved)
    }
}
