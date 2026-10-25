package com.mirage.android

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.mirage.engine.card.CardParseFailure
import com.mirage.engine.card.CardParseResult
import com.mirage.engine.card.CardStateInitializer
import com.mirage.engine.card.CharacterCard
import com.mirage.engine.card.CharacterCardParser
import com.mirage.engine.prompt.CardPromptAssembler
import com.mirage.engine.prompt.EntryPurpose

/**
 * 角色卡导入区。
 *
 * 流程是「选文件 → 看一眼概况 → 确认」，中间那一步不能省：
 * 导入会清空当前对话、重置状态栏，这是个破坏性操作，
 * 选错文件直接生效的话，用户正在玩的那局就没了。
 *
 * 预览里特意写出「状态栏几项」和「有几处读不出来」——
 * 这两个数字能让用户在真正开始玩之前就发现「这张卡的状态没读全」，
 * 而不是玩到一半才察觉某一项从来不变。
 */
@Composable
fun CardImportSection(
    summary: CardSummary?,
    failure: CardParseFailure?,
    onImport: (String) -> Unit,
    onDismissFailure: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // 选中但还没确认的那张卡。确认之后才真正交给上层
    var pending by remember { mutableStateOf<PendingCard?>(null) }
    var readFailed by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
        }.getOrNull()
        if (text == null) {
            // 读文件本身失败（权限、文件已被移走）和「内容不是角色卡」是两回事，
            // 分开提示，否则用户会对着一张好卡反复怀疑卡有问题
            readFailed = true
            return@rememberLauncherForActivityResult
        }
        when (val result = CharacterCardParser.parse(text)) {
            // 读不出来时原样交给上层，由它记录失败原因。
            // 界面上「失败」只有一个来源，就不会出现两个弹窗同时说同一件事
            is CardParseResult.Failure -> onImport(text)
            is CardParseResult.Success -> pending = PendingCard(text, result.card)
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = summary?.let { stringResource(R.string.card_current, it.name) }
                    ?: stringResource(R.string.card_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 限定只挑 JSON 文件。各家文件管理器对类型过滤的支持不一致，
            // 所以额外带上通用类型兜底，否则在某些机器上会一个文件都看不见
            TextButton(onClick = { picker.launch(arrayOf("application/json", "text/*", "*/*")) }) {
                Text(stringResource(R.string.card_import))
            }
        }

        pending?.let { candidate ->
            CardPreviewDialog(
                card = candidate.card,
                onConfirm = {
                    onImport(candidate.text)
                    pending = null
                },
                onDismiss = { pending = null },
            )
        }

        failure?.let { code ->
            MessageDialog(text = stringResource(failureTextRes(code)), onDismiss = onDismissFailure)
        }

        if (readFailed) {
            MessageDialog(
                text = stringResource(R.string.card_error_unreadable),
                onDismiss = { readFailed = false },
            )
        }
    }
}

private class PendingCard(val text: String, val card: CharacterCard)

private fun failureTextRes(code: CardParseFailure): Int = when (code) {
    CardParseFailure.MalformedJson -> R.string.card_error_malformed
    CardParseFailure.NotAnObject -> R.string.card_error_not_object
    CardParseFailure.NotACharacterCard -> R.string.card_error_not_card
}

@Composable
private fun CardPreviewDialog(
    card: CharacterCard,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val entries = card.worldBook?.entries ?: emptyList()
    val narrativeCount = CardPromptAssembler.classify(entries)
        .count { it.purpose == EntryPurpose.Narrative && it.entry.enabled }
    val init = CardStateInitializer.extract(card)
    val stateCount = init?.values?.size ?: 0
    val noteCount = init?.notes?.size ?: 0
    val hasRules = CardPromptAssembler.settlementRules(card) != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.card_preview_title)) },
        text = {
            Column {
                Text(
                    stringResource(
                        R.string.card_preview_body,
                        card.name,
                        narrativeCount,
                        stateCount,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (stateCount == 0) {
                    Text(
                        stringResource(R.string.card_preview_no_state),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        stringResource(
                            if (hasRules) R.string.card_preview_rules else R.string.card_preview_no_rules,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (noteCount > 0) {
                    Text(
                        stringResource(R.string.card_preview_notes, noteCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    stringResource(R.string.card_preview_warn_replace),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.card_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.card_cancel)) }
        },
    )
}

@Composable
private fun MessageDialog(text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text) },
        confirmButton = {
            // 出错弹窗的按钮不能写「导入」，那会让人以为点了还会再导一次
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.card_dialog_ok)) }
        },
    )
}
