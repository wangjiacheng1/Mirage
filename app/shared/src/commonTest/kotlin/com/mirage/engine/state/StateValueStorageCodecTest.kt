package com.mirage.engine.state

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 状态值存库与读回的单测。
 *
 * 存档正确性全压在这一组转换上：写进去和读出来必须严格一致。
 * 差一点点都会表现为「重开 App 后某一项的值变了」，而那种 bug
 * 在界面上看不出原因，只能从这里查。
 */
class StateValueStorageCodecTest {

    private fun roundTrip(value: StateValue): StateValue =
        StateValueJson.decodeFromText(StateValueJson.encodeToText(value))

    @Test
    fun 各种类型存进去再读出来都一样() {
        listOf(
            StateValue.IntValue(49),
            StateValue.IntValue(-7),
            StateValue.DoubleValue(1.5),
            StateValue.StringValue("门厅"),
            StateValue.StringValue(""),
            StateValue.BoolValue(true),
            StateValue.BoolValue(false),
            StateValue.NullValue,
            StateValue.ArrayValue(listOf(StateValue.StringValue("甲"), StateValue.StringValue("乙"))),
            StateValue.ArrayValue(emptyList()),
        ).forEach { value ->
            assertEquals(value, roundTrip(value), "存读不一致：$value")
        }
    }

    @Test
    fun 整数不会被读成小数() {
        // 整数项显示成 49.0 在状态栏上非常刺眼，而且会让人以为类型变了
        assertEquals(StateValue.IntValue(49), roundTrip(StateValue.IntValue(49)))
    }

    @Test
    fun 写成文本的数字仍然是文本() {
        // 「文本形态的数字」和「数字」在类型检查那一步是两种情况，不能提前归一
        assertEquals(StateValue.StringValue("49"), roundTrip(StateValue.StringValue("49")))
    }

    @Test
    fun 一整张状态栏存读一致() {
        val values = mapOf(
            "某组/甲" to StateValue.IntValue(60),
            "某组/乙" to StateValue.StringValue("门厅"),
            "某组/丙" to StateValue.BoolValue(false),
            "列表" to StateValue.ArrayValue(listOf(StateValue.IntValue(1))),
        )

        assertEquals(values, StateValueJson.decodeMap(StateValueJson.encodeMap(values)))
    }

    @Test
    fun 存坏的内容不会让整局存档打不开() {
        // 退回空值而不是抛异常：一条坏记录不该让其余内容也显示不出来
        assertEquals(StateValue.NullValue, StateValueJson.decodeFromText("{这不是 JSON"))
        assertEquals(emptyMap(), StateValueJson.decodeMap("坏掉的内容"))
    }

    @Test
    fun 缺席的值存成空值() {
        // 缺席表示「这一轮没提到」，它本身不是一个能存下来的值。
        // 存成空值是有意的妥协，而写变化记录时已经把缺席过滤掉了
        assertEquals(StateValue.NullValue, roundTrip(StateValue.Absent))
    }
}
