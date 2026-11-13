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
import com.mirage.engine.store.BackupReadFailure

/**
 * 备份导出 / 导入区。
 *
 * 没有云同步，换手机、重装、清数据之后要把东西带回来只有这一条路。
 *
 * **导出的文件里没有访问密钥。** 备份会被发到电脑、传进网盘，
 * 密钥跟着走就是直接的金钱损失；它留在系统密钥库里，换机重填一次即可。
 */
@Composable
fun BackupSection(
    notice: BackupNotice?,
    onExport: ((String) -> Unit) -> Unit,
    onImport: (String) -> Unit,
    onUnreadable: () -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // 打包好、正等着写进文件的那段文本。
    // 要先让用户挑好位置才能写，所以中间得暂存一下
    var pendingExport by remember { mutableStateOf<String?>(null) }

    val saver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        val text = pendingExport
        pendingExport = null
        if (uri == null || text == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(text.encodeToByteArray()) }
        }.isSuccess
        if (!ok) onUnreadable()
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
        }.getOrNull()
        // 读不到文件 和 内容不是备份 是两回事，分开报，
        // 否则用户会对着一份好文件反复怀疑内容有问题
        if (text == null) onUnreadable() else onImport(text)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.backup_title),
                style = MaterialTheme.typography.labelLarge,
            )
            Row {
                TextButton(
                    onClick = {
                        // 先打包，拿到文本之后再弹出「存到哪」
                        onExport { text ->
                            pendingExport = text
                            saver.launch(defaultFileName())
                        }
                    },
                ) {
                    Text(stringResource(R.string.backup_export))
                }
                TextButton(
                    onClick = { picker.launch(arrayOf("application/json", "text/*", "*/*")) },
                ) {
                    Text(stringResource(R.string.backup_import))
                }
            }
        }
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

/** 备份结果提示的文字。 */
@Composable
private fun noticeText(notice: BackupNotice): String = when (notice) {
    is BackupNotice.Exported ->
        stringResource(R.string.backup_exported, notice.sessionCount, notice.byteCount / 1024)

    is BackupNotice.Imported -> {
        val base = stringResource(
            R.string.backup_imported,
            notice.summary.sessionCount,
            notice.summary.messageCount,
        )
        // 跳过的必须当场说。事后翻几百条消息根本找不出少了哪条
        val skipped = notice.summary.skippedMessageCount + notice.summary.skippedEventCount
        if (skipped > 0) base + stringResource(R.string.backup_imported_skipped, skipped) else base
    }

    is BackupNotice.Failed -> when (notice.code) {
        BackupReadFailure.Malformed -> stringResource(R.string.backup_error_malformed)
        BackupReadFailure.UnsupportedVersion -> stringResource(R.string.backup_error_version)
        null -> stringResource(R.string.backup_error_unreadable)
    }
}

/** 默认文件名。带上日期，备份多了才分得清哪份是哪份。 */
private fun defaultFileName(): String {
    val formatter = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
    return "mirage-backup-" + formatter.format(java.util.Date()) + ".json"
}
