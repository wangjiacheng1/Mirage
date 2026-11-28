package com.mirage.android.page.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import com.mirage.android.R

/**
 * 通用叙事要求区。
 *
 * 这段话写在所有卡设定的最前面，管的是「怎么写」而不是「写谁」：
 * 篇幅、由谁扮演、玩家那句话该怎么理解、每一轮要不要往前走。
 *
 * 它和角色卡是两件事：卡的设定讲这个角色是谁，这一段讲该怎么下笔。
 * 所以换一张卡不用重写它，改它也不动卡。
 */
@Composable
fun NarrativePresetSection(
    text: String,
    enabled: Boolean,
    isDefault: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onSave: (String) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.preset_title),
                style = MaterialTheme.typography.labelLarge,
            )
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }

        // 关掉之后要说清后果：只发卡自己的内容。
        // 不说的话用户只会觉得「关掉了好像也没什么变化」
        if (!enabled) {
            Text(
                text = stringResource(R.string.preset_disabled_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (isDefault) {
                    stringResource(R.string.preset_state_default)
                } else {
                    stringResource(R.string.preset_state_custom)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row {
                TextButton(onClick = { editing = true }) {
                    Text(stringResource(R.string.preset_edit))
                }
                // 没改过就不给「恢复默认」，那个按钮点了没有任何效果
                if (!isDefault) {
                    TextButton(onClick = onReset) {
                        Text(stringResource(R.string.preset_reset))
                    }
                }
            }
        }
    }

    if (editing) {
        PresetEditDialog(
            initialText = text,
            onSave = {
                onSave(it)
                editing = false
            },
            onDismiss = { editing = false },
        )
    }
}

/** 改通用叙事要求的弹窗。 */
@Composable
private fun PresetEditDialog(
    initialText: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(initialText) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.preset_edit_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    // 这段是给模型看的，不是给人读的短文，得留够行数
                    minLines = 8,
                )
                Text(
                    text = stringResource(R.string.preset_edit_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.card_cancel))
                }
                // 允许存成空串（等于关掉这段），所以不按「非空」禁用。
                // 想彻底关掉的话上面有开关，这里清空只是不写内容
                TextButton(onClick = { onSave(draft) }) {
                    Text(stringResource(R.string.preset_save))
                }
            }
        },
    )
}
