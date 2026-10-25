package com.mirage.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mirage.engine.state.FieldDisplay
import com.mirage.engine.state.StateDisplayProfile
import com.mirage.engine.state.StateValue
import com.mirage.engine.state.asNumberOrNull

/**
 * 状态栏。
 *
 * 默认收起。它的用途是**核对**，不是主内容 —— 一直展开会把对话区挤掉一截，
 * 而大多数时候只在「剧情说变了、我想确认一下真的变了没」时才看它。
 *
 * 这里显示的是代码确认之后的数值，不是模型说的数值。两者可能不一样，
 * 而用户关心的永远是前者。
 *
 * 每一项长什么样（写数字还是画进度条）由显示方案决定，见 [StateDisplayProfile]。
 */
@Composable
fun StatePanel(
    values: Map<String, StateValue>,
    display: StateDisplayProfile,
    expanded: Boolean,
    onToggle: () -> Unit,
    placeholderNotice: Boolean,
    modifier: Modifier = Modifier,
) {
    if (values.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.state_panel_title, values.size),
                style = MaterialTheme.typography.labelLarge,
            )
            TextButton(onClick = onToggle) {
                Text(
                    stringResource(
                        if (expanded) R.string.state_panel_hide else R.string.state_panel_show,
                    ),
                )
            }
        }

        if (!expanded) return@Column

        // 已经被某条进度条用作满值的那些项，不再单独占一行。
        // 进度条上已经写着「当前 / 满值」，下面再来一行「满值 60」纯属重复，
        // 28 项的状态栏里这种重复会多出好几行，真正要看的数值反而被挤下去了
        val shownAsMax = values.keys.mapNotNull { display.of(it)?.maxPath }.toSet()

        // 按路径排序，顺序才稳定。跟着表的遍历顺序走的话，某一项变了就可能整列重排，
        // 看起来像数据在乱跳
        values.entries.sortedBy { it.key }
            .filter { it.key !in shownAsMax }
            .forEach { (path, value) ->
                StateRow(path = path, value = value, values = values, display = display)
            }

        if (placeholderNotice) {
            Text(
                text = stringResource(R.string.state_panel_placeholder_notice),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * 状态栏里的一行。
 *
 * 进度条这一行会同时写出「当前 / 满值」的数字 —— 只有条没有数字的话，
 * 用户看不出来到底还剩多少，而这正是他看状态栏想知道的事。
 */
@Composable
private fun StateRow(
    path: String,
    value: StateValue,
    values: Map<String, StateValue>,
    display: StateDisplayProfile,
) {
    val presentation = display.of(path)
    val maxValue = presentation?.maxPath?.let { values[it]?.asNumberOrNull() }
    val current = value.asNumberOrNull()

    // 满值还没准备好（为 0 或缺失）时退回写数字。
    // 真按 0 去画，进度条会显示成满格或者直接报错，两种都比写个数字更糟
    val drawGauge = presentation?.display == FieldDisplay.Gauge &&
        current != null && maxValue != null && maxValue > 0.0

    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            text = if (drawGauge) {
                stringResource(
                    R.string.state_panel_gauge_entry,
                    path,
                    stateValueText(value),
                    trimNumber(maxValue!!),
                )
            } else {
                stringResource(R.string.state_panel_entry, path, stateValueText(value))
            },
            style = MaterialTheme.typography.bodySmall,
        )
        if (drawGauge) {
            LinearProgressIndicator(
                // 超出满值时按满格画。数值本身不动 —— 显示层不该替代码去改数据
                progress = { (current!! / maxValue!!).coerceIn(0.0, 1.0).toFloat() },
                modifier = Modifier.fillMaxWidth().height(4.dp),
            )
        }
    }
}

/** 满值通常是整数，去掉没意义的尾零再显示。 */
private fun trimNumber(value: Double): String {
    val text = value.toString()
    if (!text.contains('.')) return text
    return text.trimEnd('0').trimEnd('.')
}
