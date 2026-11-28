package com.mirage.android.page.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mirage.engine.prompt.TokenBreakdown
import com.mirage.android.R

/**
 * 上下文占用条。
 *
 * 一条横杠，按比例画出这次发出去的内容里设定、示范、历史、本轮各占多少。
 *
 * 为什么要分段而不是只给一个总数：看到「快满了」但不知道是谁吃掉的，
 * 就不知道该去压历史、还是该把卡里那段几千字的设定删一删。
 *
 * 数字是我们自己估的，不是服务商的口径，所以下面那行写明了「估算」。
 */
@Composable
fun ContextUsageBar(
    breakdown: TokenBreakdown,
    modifier: Modifier = Modifier,
) {
    // 一个字都没有时不画。那是还没发过消息的状态，画一条空杠只是噪音
    if (breakdown.total <= 0) return

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.context_usage_title),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = stringResource(
                    R.string.context_usage_total,
                    breakdown.total,
                    breakdown.budget,
                    breakdown.usedPercent,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        UsageBar(breakdown)

        // 图例。每段标上名字和数字，光看颜色没人分得清哪段是哪段
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LegendItem(SEGMENT_LEADING, stringResource(R.string.context_part_leading), breakdown.leading)
            if (breakdown.examples > 0) {
                LegendItem(SEGMENT_EXAMPLES, stringResource(R.string.context_part_examples), breakdown.examples)
            }
            LegendItem(SEGMENT_HISTORY, stringResource(R.string.context_part_history), breakdown.history)
            LegendItem(SEGMENT_TAIL, stringResource(R.string.context_part_tail), breakdown.tail)
        }

        Text(
            text = stringResource(R.string.context_usage_estimated),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 占用条本身：四段按各自比例排开，右边留白表示还剩多少。 */
@Composable
private fun UsageBar(breakdown: TokenBreakdown) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        // 按预算分比例，不是按已用部分分。
        // 按已用分的话，不管用了多少这条杠都是满的，就看不出还剩多少了
        val scale = maxOf(breakdown.budget, breakdown.total).toFloat()

        Segment(SEGMENT_LEADING, breakdown.leading / scale)
        Segment(SEGMENT_EXAMPLES, breakdown.examples / scale)
        Segment(SEGMENT_HISTORY, breakdown.history / scale)
        Segment(SEGMENT_TAIL, breakdown.tail / scale)
    }
}

/** 占用条里的一段。 */
@Composable
private fun RowScope.Segment(color: Color, fraction: Float) {
    // weight 不接受 0，而且空的一段本来也不用画
    if (fraction <= 0f) return
    Box(
        modifier = Modifier
            .weight(fraction)
            .fillMaxHeight()
            .background(color),
    )
}

/** 图例里的一项：一个色块 + 名字 + 数字。 */
@Composable
private fun LegendItem(color: Color, label: String, tokens: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(
            modifier = Modifier
                .padding(top = 3.dp)
                .size(9.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )
        Text(
            text = "$label $tokens",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// 四段的颜色写死在这里，不走主题色：它们之间要能一眼分开，
// 而主题色是按「主色 / 次色 / 强调色」的用途定的，凑在一起不一定分得清
private val SEGMENT_LEADING = Color(0xFF5C6BC0)
private val SEGMENT_EXAMPLES = Color(0xFF26A69A)
private val SEGMENT_HISTORY = Color(0xFF66BB6A)
private val SEGMENT_TAIL = Color(0xFFFFA726)
