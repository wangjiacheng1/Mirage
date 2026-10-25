package com.mirage.android

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.mirage.engine.state.FieldType
import com.mirage.engine.state.Monotonic
import com.mirage.engine.state.StateValue

/**
 * 把引擎的值类型渲染成界面上的一行字。
 *
 * 这一层刻意放在界面侧：引擎只负责「值是多少」，「显示成什么样」有很多
 * 只有界面才知道的讲究 —— 布尔该说「是/否」还是「开/关」、空值该显示成破折号
 * 还是留白、列表用什么分隔。把这些塞进引擎，等于让换措辞、换语言都要去改引擎。
 */
@Composable
fun stateValueText(value: StateValue): String = when (value) {
    is StateValue.IntValue -> value.value.toString()

    // 小数去掉无意义的尾零：倍率 1.50 显示成 1.5 更干净，
    // 而整数形态的小数（2.0）显示成 2 才不会让人以为是另一种类型
    is StateValue.DoubleValue -> trimTrailingZeros(value.value)

    // 空文本要显示成一个看得见的占位。直接显示成空白的话，
    // 状态栏上会出现一行只有名字没有值的条目，回执里会出现「某项 →」，
    // 两种都像是出了故障，而实际上「这一项现在就是空的」是正常状态
    is StateValue.StringValue -> value.value.ifBlank { stringResource(R.string.value_empty_text) }
    is StateValue.BoolValue -> stringResource(
        if (value.value) R.string.value_true else R.string.value_false,
    )

    is StateValue.ArrayValue -> if (value.items.isEmpty()) {
        stringResource(R.string.value_empty_list)
    } else {
        // 逐项递归，保证列表里的布尔和小数也走同一套渲染规则。
        // 这里用显式循环而不是 joinToString：后者的转换块不是内联的，
        // 在里面取文案资源会被编译器直接拒绝
        val separator = stringResource(R.string.value_list_separator)
        val builder = StringBuilder()
        for ((index, item) in value.items.withIndex()) {
            if (index > 0) builder.append(separator)
            builder.append(stateValueText(item))
        }
        builder.toString()
    }

    StateValue.NullValue -> stringResource(R.string.value_null)

    // 正常不会走到这里：缺席表示「本轮没提到」，它不该成为任何一条变化的结果。
    // 留一个占位而不是抛异常 —— 界面不该因为一个显示问题整屏崩掉
    StateValue.Absent -> stringResource(R.string.value_null)
}

/** 字段类型的说法。给「类型不符」那类提示填空用。 */
@Composable
fun fieldTypeText(type: FieldType): String = stringResource(
    when (type) {
        FieldType.Int -> R.string.type_int
        FieldType.Double -> R.string.type_double
        FieldType.String -> R.string.type_string
        FieldType.Bool -> R.string.type_bool
        FieldType.Array -> R.string.type_array
        FieldType.Any -> R.string.type_any
    },
)

/** 单调方向的说法。 */
@Composable
fun monotonicText(direction: Monotonic): String = stringResource(
    when (direction) {
        Monotonic.Increasing -> R.string.guard_direction_increase
        Monotonic.Decreasing -> R.string.guard_direction_decrease
    },
)

/**
 * 数值去尾零。
 *
 * 不用格式化模板，因为位数是不固定的：倍率可能是 1.5，也可能是 0.125，
 * 写死小数位会把后者截断成 0.13，那是在显示层悄悄改数据。
 */
private fun trimTrailingZeros(value: Double): String {
    val text = value.toString()
    if (!text.contains('.')) return text
    return text.trimEnd('0').trimEnd('.')
}
