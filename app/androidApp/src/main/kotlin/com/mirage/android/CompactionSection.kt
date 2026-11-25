package com.mirage.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource

/**
 * 压缩历史区。
 *
 * 聊得越久，每轮要发出去的历史就越长，直到撞上模型的上限。
 * 这里把旧的那些轮次交给模型总结成摘要，之后只发摘要、不发原文。
 *
 * **原文一个字都不会删**，只是不再发给模型。界面上照样能往上翻、能编辑、能切版本。
 */
@Composable
fun CompactionSection(
    compactedTurnCount: Int,
    volumeCount: Int,
    compacting: Boolean,
    notice: CompactionNotice?,
    onCompact: () -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.compaction_title),
                style = MaterialTheme.typography.labelLarge,
            )
            TextButton(onClick = onCompact, enabled = !compacting) {
                Text(
                    stringResource(
                        if (compacting) R.string.compaction_running else R.string.compaction_action,
                    ),
                )
            }
        }

        // 压到哪了要摆出来。不说的话用户无从判断「它还记不记得前面的事」
        Text(
            text = if (volumeCount > 0) {
                stringResource(R.string.compaction_state, volumeCount, compactedTurnCount)
            } else {
                stringResource(R.string.compaction_state_none)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 🔴 有损这件事必须一直摆在这儿，不能只在第一次提一下。
        // 摘要会丢细节，用户有权在按下按钮之前就知道这一点
        Text(
            text = stringResource(R.string.compaction_lossy_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    notice?.let { current ->
        AlertDialog(
            onDismissRequest = onDismissNotice,
            text = { Text(noticeText(current)) },
            confirmButton = {
                TextButton(onClick = onDismissNotice) {
                    Text(stringResource(R.string.card_dialog_ok))
                }
            },
        )
    }
}

/** 压缩结果提示的文字。 */
@Composable
private fun noticeText(notice: CompactionNotice): String = when (notice) {
    is CompactionNotice.Compacted ->
        stringResource(R.string.compaction_done, notice.turnCount)

    is CompactionNotice.Merged ->
        stringResource(R.string.compaction_merged, notice.volumeCount)

    // 点了没反应最让人以为是坏了，所以「现在没什么可压」也要如实说
    CompactionNotice.NothingToDo -> stringResource(R.string.compaction_nothing)

    // 失败原因要说出来。只说「没成功」的话，是网络断了、密钥过期、
    // 还是模型什么都没写，用户一个都分不出来，只能反复点
    is CompactionNotice.Failed -> {
        val base = stringResource(R.string.compaction_failed)
        val reason = notice.error?.let { errorText(it) }
        if (reason != null) base + "\n" + reason else base
    }
}
