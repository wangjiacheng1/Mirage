package com.mirage.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource

/**
 * 一条消息下面的操作行。
 *
 * 只在「没有请求在跑」的时候显示。生成过程中改动消息结构，会让正在写入的那条
 * 挂到一个已经变了的位置上 —— 与其处理那堆边界，不如这段时间先不给入口。
 */
@Composable
fun MessageActions(
    entry: TranscriptEntry,
    enabled: Boolean,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSwitchVersion: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!enabled) return

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (entry.fromUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 同一句话有多个版本时才显示左右切换。
        // 只有一个版本还摆着「1/1」和两个灰按钮，只会让人以为功能坏了
        if (entry.versionCount > 1) {
            TextButton(
                onClick = { onSwitchVersion(-1) },
                enabled = entry.versionIndex > 1,
            ) {
                Text("‹", style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                text = stringResource(R.string.message_version, entry.versionIndex, entry.versionCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = { onSwitchVersion(1) },
                enabled = entry.versionIndex < entry.versionCount,
            ) {
                Text("›", style = MaterialTheme.typography.bodyMedium)
            }
        }

        // 重新生成只对模型的回复有意义：用户自己说的话没什么好重新生成的
        if (!entry.fromUser) {
            TextButton(onClick = onRegenerate) {
                Text(
                    stringResource(R.string.message_regenerate),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        TextButton(onClick = onEdit) {
            Text(stringResource(R.string.message_edit), style = MaterialTheme.typography.labelSmall)
        }
        TextButton(onClick = onDelete) {
            Text(
                text = stringResource(R.string.message_delete),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * 编辑消息的弹窗。
 *
 * 编辑是原地改，不留旧版本 —— 它的用意是「这句话本来就该这么说」。
 * 想保留两个说法那是重新生成要做的事。
 */
@Composable
fun MessageEditDialog(
    initialText: String,
    /**
     * 要不要给「保存并重新生成」。
     *
     * 只在改自己说过的话时给：输入变了，按旧输入生成的那条回复就对不上了。
     * 改模型的回复则不需要 —— 改完就是想要那个样子，没有「接着重生成」的意思
     */
    allowRegenerate: Boolean,
    onConfirm: (String) -> Unit,
    onConfirmAndRegenerate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(initialText) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.message_edit_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (allowRegenerate) {
                    Text(
                        text = stringResource(R.string.message_edit_regenerate_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(
                    onClick = { onConfirm(draft) },
                    enabled = draft.isNotBlank(),
                ) {
                    Text(stringResource(R.string.message_edit_save))
                }
                if (allowRegenerate) {
                    TextButton(
                        onClick = { onConfirmAndRegenerate(draft) },
                        enabled = draft.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.message_edit_save_regenerate))
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.card_cancel)) }
        },
    )
}

/** 删除消息的二次确认。 */
@Composable
fun MessageDeleteDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.message_delete_title)) },
        text = { Text(stringResource(R.string.message_delete_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.session_delete_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.card_cancel)) }
        },
    )
}
