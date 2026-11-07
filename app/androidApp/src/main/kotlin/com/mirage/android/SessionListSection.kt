package com.mirage.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mirage.engine.store.SessionSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 存档列表区。
 *
 * 点一条就切过去。切换会把状态栏整个换成那一局的数值 —— 两局之间互不影响，
 * 这是靠「状态值存成变化记录、按这条线回放」做到的，不是靠各存一份当前值。
 *
 * 默认收起：存档多了会占掉半屏，而大多数时候用户只是继续玩当前这局。
 */
@Composable
fun SessionListSection(
    sessions: List<SessionSummary>,
    currentSessionId: Long?,
    onOpen: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (sessions.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }
    // 待确认删除的那一局。删除不可撤销，不能点一下就没
    var pendingDelete by remember { mutableStateOf<SessionSummary?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.session_title, sessions.size),
                style = MaterialTheme.typography.labelLarge,
            )
            TextButton(onClick = { expanded = !expanded }) {
                Text(
                    stringResource(
                        if (expanded) R.string.session_hide else R.string.session_show,
                    ),
                )
            }
        }

        if (expanded) {
            sessions.forEach { session ->
                SessionRow(
                    session = session,
                    current = session.id == currentSessionId,
                    onOpen = { onOpen(session.id) },
                    onDelete = { pendingDelete = session },
                )
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.session_delete_title)) },
            text = { Text(stringResource(R.string.session_delete_body, target.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(target.id)
                        pendingDelete = null
                    },
                ) {
                    Text(stringResource(R.string.session_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.card_cancel))
                }
            },
        )
    }
}

/** 存档列表里的一行。 */
@Composable
private fun SessionRow(
    session: SessionSummary,
    current: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 当前这局也可以点，点了不会有任何动作（上层会直接返回）。
            // 做成不可点反而要多一套样式，而用户点自己正在玩的那局本来也不期待发生什么
            .clickable(onClick = onOpen)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (current) {
                    stringResource(R.string.session_current, session.title)
                } else {
                    session.title
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (current) FontWeight.Medium else FontWeight.Normal,
            )
            Text(
                text = stringResource(
                    R.string.session_meta,
                    session.messageCount,
                    formatTime(session.updatedAt),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onDelete) {
            Text(
                text = stringResource(R.string.session_delete),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** 时间戳转成「月-日 时:分」。存档列表只需要看个大概，不用精确到秒。 */
private fun formatTime(timestamp: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
