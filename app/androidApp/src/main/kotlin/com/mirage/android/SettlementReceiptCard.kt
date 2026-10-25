package com.mirage.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mirage.engine.settlement.SettlementPhase
import com.mirage.engine.settlement.SettlementRecord
import com.mirage.engine.state.Disposition
import com.mirage.engine.state.GuardFailure
import com.mirage.engine.state.GuardFailureCode
import com.mirage.engine.state.GuardFailureDetail
import com.mirage.engine.state.SettlementStatus
import com.mirage.engine.state.StateChange

/**
 * ============================================================================
 * 结算回执卡片
 * ============================================================================
 *
 * 挂在每条回复下面，回答一个问题：**这一轮数值到底怎么了。**
 *
 * ## 设计上最要紧的一条
 *
 * 「本轮确实没有数值变化」和「本轮结算失败了」必须一眼能分清。
 * 把两者合并成一句含糊的「没有更新」，使用者就完全无法判断要不要重来 ——
 * 该重来的时候他不重来（数值永远少了一截），不该重来的时候他重来
 * （白花一次钱）。所以这两种状态在这里用了不同的措辞、不同的颜色，
 * 并且只有后者给重算入口。
 *
 * ## 为什么连被拦下的提议也要显示
 *
 * 剧情里说「受了 20 点伤」，面板上却只少了 11 点，这种对不上必须有交代。
 * 看不到交代的话，使用者只会得出「这个系统算不准」的结论；
 * 看到「超出允许范围，已调整」之后，他知道的是「规则生效了」。
 * 同一件事，两种完全相反的信任走向。
 *
 * @param queuedAhead 排在它前面、还没跑完的任务数。只在等待时有意义。
 */
@Composable
fun SettlementReceiptCard(
    record: SettlementRecord,
    queuedAhead: Int,
    onRetry: (Int) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val receipt = record.outcome.receipt

    Surface(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = statusText(record, queuedAhead),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = statusColor(record.status),
                )
                // 重试过的轮次要标出来，否则使用者分不清眼前这份结果
                // 是最初那次的，还是他刚才点重算之后的
                if (record.attempt > 1) {
                    Text(
                        text = "  " + stringResource(R.string.settlement_attempt, record.attempt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            receipt.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            receipt.changes.forEach { change ->
                ChangeLine(change)
            }

            receipt.failures.forEach { failure ->
                // 夹取类的条目在上面已经以「实际落下的值」出现过一次，
                // 这里补的是「我们动过手脚」这件事，两条合起来才是完整交代
                Text(
                    text = failureText(failure),
                    style = MaterialTheme.typography.bodySmall,
                    color = failureColor(failure.disposition),
                )
            }

            // 结算是使用者看不见的额外一次调用。看不见的开销更要如实列出来，
            // 否则账单上会出现他无法解释的数字
            val usage = record.outcome.usage
            if (!usage.isEmpty) {
                Text(
                    text = stringResource(R.string.settlement_usage, usageText(usage)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 正在跑的允许叫停。和停止正文一样，停的是这边的等待，
            // 服务方那边可能照样算完，所以文案不能暗示「停了就不花钱」
            if (record.phase == SettlementPhase.Running) {
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.settlement_cancel))
                }
            }

            if (record.retryable) {
                // 三种可重试的状态，起因完全不同，按钮也该说不同的话：
                // 失败是「再试一次」，过期是「按新剧情重算」，中断是「接着算」
                TextButton(onClick = { onRetry(record.turn) }) {
                    Text(
                        stringResource(
                            when (record.status) {
                                SettlementStatus.Stale -> R.string.settlement_retry_stale
                                SettlementStatus.Interrupted -> R.string.settlement_retry_interrupted
                                else -> R.string.settlement_retry_error
                            },
                        ),
                    )
                }
            }
        }
    }
}

/** 一条落盘的变化。被修正过的要额外标出来。 */
@Composable
private fun ChangeLine(change: StateChange) {
    val label = change.path
    Text(
        text = stringResource(
            R.string.settlement_change_line,
            label,
            stateValueText(change.before),
            stateValueText(change.after),
        ) + if (change.adjustedBy != null) stringResource(R.string.settlement_change_adjusted) else "",
        style = MaterialTheme.typography.bodySmall,
    )
    change.reason?.takeIf { it.isNotBlank() }?.let { reason ->
        Text(
            text = stringResource(R.string.settlement_change_reason, reason),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 整轮状态的说法。
 *
 * 等待和进行中要分开：排队说明「还没开始，前面有几个」，
 * 进行中说明「已经在跑了」。合成一句「处理中」会让人以为卡住了。
 */
@Composable
private fun statusText(record: SettlementRecord, queuedAhead: Int): String {
    if (record.phase == SettlementPhase.Waiting) {
        return if (queuedAhead > 0) {
            stringResource(R.string.settlement_status_waiting, queuedAhead)
        } else {
            stringResource(R.string.settlement_status_pending)
        }
    }
    if (record.phase == SettlementPhase.Running) {
        return stringResource(R.string.settlement_status_running)
    }

    val receipt = record.outcome.receipt
    return when (record.status) {
        SettlementStatus.Updated -> stringResource(
            R.string.settlement_status_updated,
            receipt.changes.size,
        )

        SettlementStatus.Partial -> stringResource(
            R.string.settlement_status_partial,
            receipt.changes.size,
            receipt.failures.size,
        )

        SettlementStatus.Unchanged -> stringResource(R.string.settlement_status_unchanged)
        SettlementStatus.Error -> stringResource(R.string.settlement_status_error)
        SettlementStatus.Stale -> stringResource(R.string.settlement_status_stale)
        SettlementStatus.Interrupted -> stringResource(R.string.settlement_status_interrupted)
        SettlementStatus.Pending -> stringResource(R.string.settlement_status_pending)
    }
}

/**
 * 状态配色。
 *
 * 只有真正需要使用者采取行动的状态才用警示色。「没有变化」是正常结果，
 * 给它上红色等于每隔几轮就报一次假警，几次之后所有提示都会被无视。
 */
@Composable
private fun statusColor(status: SettlementStatus): Color = when (status) {
    SettlementStatus.Error -> MaterialTheme.colorScheme.error
    SettlementStatus.Stale, SettlementStatus.Interrupted -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurface
}

/** 被丢弃的用警示色，被修正后仍落盘的只用次要色 —— 后者并没有丢东西。 */
@Composable
private fun failureColor(disposition: Disposition): Color = when (disposition) {
    Disposition.Rejected -> MaterialTheme.colorScheme.error
    Disposition.Clamped -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * 一条拦截的说法。
 *
 * 引擎给的是分类加参数，句子在这里拼。能做到「列表里一句话带过、
 * 详情里说清上下界」这种同一条拦截两种详略，靠的就是这个分工。
 *
 * 字段名优先用回执里自带的那个，而不是回头去查字段定义：
 * 回执可能被保存下来事后翻看，那时定义可能已经改过了。
 */
@Composable
private fun failureText(failure: GuardFailure): String {
    val name = failure.displayName ?: failure.path
    val detail = failure.detail

    return when (failure.code) {
        GuardFailureCode.UnknownPath ->
            stringResource(R.string.guard_unknown_path, name)

        GuardFailureCode.TypeMismatch -> {
            val expected = (detail as? GuardFailureDetail.TypeExpectation)?.expected
            if (expected == null) {
                stringResource(R.string.guard_type_mismatch_plain, name)
            } else {
                stringResource(R.string.guard_type_mismatch, name, fieldTypeText(expected))
            }
        }

        GuardFailureCode.Clamped -> {
            val clamp = detail as? GuardFailureDetail.RangeClamp
            if (clamp == null) {
                stringResource(R.string.guard_clamped_plain, name)
            } else {
                stringResource(
                    R.string.guard_clamped,
                    name,
                    stateValueText(failure.proposed),
                    stateValueText(clamp.applied),
                )
            }
        }

        GuardFailureCode.IllegalEnum -> {
            val allowed = (detail as? GuardFailureDetail.AllowedValues)?.values
            if (allowed.isNullOrEmpty()) {
                stringResource(R.string.guard_illegal_enum_plain, name)
            } else {
                stringResource(
                    R.string.guard_illegal_enum,
                    name,
                    allowed.joinToString(stringResource(R.string.value_list_separator)),
                )
            }
        }

        GuardFailureCode.DeltaTooLarge -> {
            val limit = detail as? GuardFailureDetail.DeltaLimit
            if (limit == null) {
                stringResource(R.string.guard_delta_too_large_plain, name)
            } else {
                stringResource(
                    R.string.guard_delta_too_large,
                    name,
                    formatAmount(limit.attempted),
                    formatAmount(limit.applied),
                )
            }
        }

        GuardFailureCode.ReadOnlyPath ->
            stringResource(R.string.guard_read_only, name)

        GuardFailureCode.DirectionViolation -> {
            val required = (detail as? GuardFailureDetail.DirectionRequirement)?.required
            if (required == null) {
                stringResource(R.string.guard_direction_plain, name)
            } else {
                stringResource(R.string.guard_direction, name, monotonicText(required))
            }
        }
    }
}

/** 变化幅度是算出来的数，同样去掉无意义的尾零。 */
private fun formatAmount(value: Double): String {
    val text = value.toString()
    if (!text.contains('.')) return text
    return text.trimEnd('0').trimEnd('.')
}
