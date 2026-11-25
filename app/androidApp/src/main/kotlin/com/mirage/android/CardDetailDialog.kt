package com.mirage.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import com.mirage.engine.card.CardEditor
import com.mirage.engine.card.CardField
import com.mirage.engine.card.CharacterCard
import com.mirage.engine.card.WorldBookEntry

/**
 * 角色卡查看与编辑弹窗。
 *
 * 做它的直接原因：用户觉得「回复语气和卡里的设定有点对不上」，
 * 但看不到卡里到底写了什么，也没法改 —— 只能凭感觉猜。
 *
 * 每个字段都配一句「这是什么、该写什么」。实测同类产品的痛点就是
 * 「每条内容没有介绍应该写什么」，新手建卡失败多半栽在这里。
 */
@Composable
fun CardDetailDialog(
    card: CharacterCard,
    stateFieldCount: Int,
    onSave: (CardField, String) -> Unit,
    onDismiss: () -> Unit,
) {
    // 正在编辑哪个字段。为 null 表示没在编辑
    var editing by remember { mutableStateOf<CardField?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.card_detail_title) + " · " + card.name) },
        text = {
            Column(
                // 卡的内容很长，必须能滚。限高是为了给标题和按钮留位置，
                // 不限的话内容一长，底下的关闭按钮会被挤出屏幕
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                CardField.entries.forEach { field ->
                    CardFieldRow(
                        field = field,
                        value = CardEditor.read(card, field),
                        onEdit = { editing = field },
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text(
                    text = stringResource(R.string.card_state_fields, stateFieldCount),
                    style = MaterialTheme.typography.labelLarge,
                )

                val entries = card.worldBook?.entries.orEmpty()
                if (entries.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.card_worldbook, entries.size),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    entries.forEach { WorldBookRow(it) }
                }

                Text(
                    text = stringResource(R.string.card_worldbook_readonly),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.card_detail_close)) }
        },
    )

    editing?.let { field ->
        MessageEditDialog(
            initialText = CardEditor.read(card, field),
            // 改卡不涉及「重新生成」，那是对话那边的事
            allowRegenerate = false,
            onConfirm = {
                onSave(field, it)
                editing = null
            },
            onConfirmAndRegenerate = { editing = null },
            onDismiss = { editing = null },
        )
    }
}

/** 卡上的一个字段：名字、说明、当前内容、编辑入口。 */
@Composable
private fun CardFieldRow(field: CardField, value: String, onEdit: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = labelOf(field) + "　" + stringResource(R.string.card_field_chars, value.length),
                style = MaterialTheme.typography.labelLarge,
            )
            TextButton(onClick = onEdit) { Text(stringResource(R.string.card_field_edit)) }
        }

        Text(
            text = hintOf(field),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            // 只给个开头。整段几百字显示出来，这个弹窗就没法看了；
            // 要看全文点编辑，那里是完整可滚动的
            text = value.take(PREVIEW_LENGTH).ifBlank { stringResource(R.string.card_field_empty) },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** 世界观设定里的一条。只读展示。 */
@Composable
private fun WorldBookRow(entry: WorldBookEntry) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = stringResource(
                R.string.card_worldbook_entry,
                entry.comment.ifBlank { entry.keys.firstOrNull().orEmpty() },
                entry.content.length,
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            // 常驻条目每轮都占上下文，带不带触发词是两种完全不同的开销，要能一眼看出来
            text = if (entry.constant) {
                stringResource(R.string.card_worldbook_always)
            } else if (entry.keys.isEmpty()) {
                stringResource(R.string.card_worldbook_none)
            } else {
                stringResource(R.string.card_worldbook_keys, entry.keys.joinToString("、"))
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun labelOf(field: CardField): String = when (field) {
    CardField.Name -> "角色名"
    CardField.Description -> "角色设定"
    CardField.Personality -> "性格"
    CardField.Scenario -> "处境"
    CardField.FirstMessage -> "开场白"
    CardField.MessageExample -> "对话示例"
    CardField.SystemPrompt -> "全局要求"
    CardField.PostHistoryInstructions -> "队尾要求"
    CardField.CreatorNotes -> "作者备注"
}

@Composable
private fun hintOf(field: CardField): String = stringResource(
    when (field) {
        CardField.Name -> R.string.card_hint_name
        CardField.Description -> R.string.card_hint_description
        CardField.Personality -> R.string.card_hint_personality
        CardField.Scenario -> R.string.card_hint_scenario
        CardField.FirstMessage -> R.string.card_hint_first_mes
        CardField.MessageExample -> R.string.card_hint_mes_example
        CardField.SystemPrompt -> R.string.card_hint_system_prompt
        CardField.PostHistoryInstructions -> R.string.card_hint_post_history
        CardField.CreatorNotes -> R.string.card_hint_creator_notes
    },
)

/** 列表里每个字段显示多长的开头。 */
private const val PREVIEW_LENGTH = 60
