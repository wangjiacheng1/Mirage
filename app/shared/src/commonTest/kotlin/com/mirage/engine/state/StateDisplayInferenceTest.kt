package com.mirage.engine.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 猜显示形态的单测。
 *
 * 用例形状照着真卡：「某项 + 某项上限」成对、「当前 + 上限」成对、
 * 以及一个孤零零的数字（没有满值可配）。
 */
class StateDisplayInferenceTest {

    private fun profile(values: Map<String, StateValue>, schema: StateSchema = StateSchema(emptyMap())) =
        StateDisplayInference.infer(values, schema)

    @Test
    fun 同名加上限后缀的配成进度条() {
        val p = profile(
            mapOf(
                "某组/甲" to StateValue.IntValue(49),
                "某组/甲上限" to StateValue.IntValue(60),
            ),
        )

        assertEquals(FieldDisplay.Gauge, p.of("某组/甲")!!.display)
        assertEquals("某组/甲上限", p.of("某组/甲")!!.maxPath)
    }

    @Test
    fun 满值那一项自己只写数字() {
        val p = profile(
            mapOf(
                "某组/甲" to StateValue.IntValue(49),
                "某组/甲上限" to StateValue.IntValue(60),
            ),
        )

        // 不排除的话，满值自己会画出一条永远满格的进度条
        assertEquals(FieldDisplay.Number, p.of("某组/甲上限")!!.display)
        assertNull(p.of("某组/甲上限")!!.maxPath)
    }

    @Test
    fun 当前与上限成对的也配成进度条() {
        val p = profile(
            mapOf(
                "某组/当前" to StateValue.IntValue(5),
                "某组/上限" to StateValue.IntValue(5),
            ),
        )

        assertEquals("某组/上限", p.of("某组/当前")!!.maxPath)
    }

    @Test
    fun 配不上满值的数字就写数字() {
        val p = profile(mapOf("某组/乙" to StateValue.IntValue(41)))

        assertEquals(FieldDisplay.Number, p.of("某组/乙")!!.display)
        assertNull(p.of("某组/乙")!!.maxPath)
    }

    @Test
    fun 不同组的同名项不会被配到一起() {
        val p = profile(
            mapOf(
                "甲组/数值" to StateValue.IntValue(10),
                "乙组/数值上限" to StateValue.IntValue(99),
            ),
        )

        // 跨组配对会在状态栏上显示出一个完全错误的比例
        assertNull(p.of("甲组/数值")!!.maxPath)
    }

    @Test
    fun 文字是否列表各自对应形态() {
        val p = profile(
            mapOf(
                "某组/文字" to StateValue.StringValue("门厅"),
                "某组/是否" to StateValue.BoolValue(false),
                "某组/列表" to StateValue.ArrayValue(emptyList()),
            ),
        )

        assertEquals(FieldDisplay.Text, p.of("某组/文字")!!.display)
        assertEquals(FieldDisplay.Toggle, p.of("某组/是否")!!.display)
        assertEquals(FieldDisplay.Tags, p.of("某组/列表")!!.display)
    }

    @Test
    fun 配出来的满值绝不进字段清单() {
        val values = mapOf(
            "某组/甲" to StateValue.IntValue(49),
            "某组/甲上限" to StateValue.IntValue(60),
        )
        val schema = StateSchema(
            fields = values.keys.associateWith { FieldSpec(type = FieldType.Int) },
        )
        profile(values, schema)

        // 显示形态是猜的，猜错只是看着别扭；一旦被当成上限去夹数值，
        // 就等于把猜测变成了硬规则，卡可能直接玩不了
        assertNull(schema.specOf("某组/甲")!!.max)
        assertNull(schema.specOf("某组/甲")!!.maxFromPath)
    }
}
