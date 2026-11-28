package com.mirage.android.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mirage.android.page.character.CardDetailDialog
import com.mirage.android.page.character.CardImportSection
import com.mirage.android.page.chat.BackupSection
import com.mirage.android.page.chat.ChatScreen
import com.mirage.android.page.chat.ChatViewModel
import com.mirage.android.page.chat.CompactionSection
import com.mirage.android.page.chat.ContextUsageBar
import com.mirage.android.page.chat.NarrativePresetSection
import com.mirage.android.page.chat.SessionListSection
import com.mirage.android.R
import com.mirage.android.page.chat.ConfigPanel

/** 底部三个页签。 */
enum class MainTab {
    /** 聊天：正文、状态栏、输入框。产品绝大部分时间停在这里。 */
    Story,

    /** 角色：角色卡导入、查看、改写。 */
    Character,

    /** 我的：接入点与密钥、备份、压缩、叙事要求。 */
    Settings,
}

/**
 * App 的主框架：底部三个页签 + 当前页签的内容。
 *
 * ## 为什么要分页签
 *
 * 之前所有东西都堆在聊天页顶部：接入点配置、备份、压缩、存档列表、角色卡导入、
 * 状态栏，一共七块。它们一直占着屏幕，正文被挤到只剩下面一小条。
 *
 * 这些东西的使用频率差得很远 —— 密钥配一次就不动了，备份偶尔用一次，
 * 而正文是每一轮都在看的。按频率分开之后，聊天页只留每轮都要用的东西。
 *
 * ## 为什么页签状态放在这里
 *
 * 切页签不该影响正在进行的生成，所以三个页签共用同一个 ViewModel，
 * 只是显示不同的部分。换页签只是换显示，不碰任何正在跑的任务。
 */
@Composable
fun MainTabs(viewModel: ChatViewModel) {
    var tab by remember { mutableStateOf(MainTab.Story) }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when (tab) {
                MainTab.Story -> ChatScreen(viewModel)
                MainTab.Character -> CharacterTab(viewModel)
                MainTab.Settings -> SettingsTab(viewModel)
            }
        }

        NavigationBar {
            MainTab.entries.forEach { entry ->
                NavigationBarItem(
                    selected = tab == entry,
                    onClick = { tab = entry },
                    // 先不配图标：这一轮只做结构，图标跟着后面的视觉一起定
                    icon = { Text(labelOf(entry)) },
                )
            }
        }
    }
}

@Composable
private fun labelOf(tab: MainTab): String = when (tab) {
    MainTab.Story -> stringResource(R.string.tab_story)
    MainTab.Character -> stringResource(R.string.tab_character)
    MainTab.Settings -> stringResource(R.string.tab_settings)
}

/**
 * 角色页：这一局用的是哪张卡，以及导入、查看、改写。
 *
 * 和「我的」分开，是因为换卡和改卡是跟着剧情走的事，
 * 而密钥、备份那些配一次就不碰了。
 */
@Composable
private fun CharacterTab(viewModel: ChatViewModel) {
    val state by viewModel.state.collectAsState()
    var showCardDetail by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        SectionTitle(stringResource(R.string.tab_character))

        CardImportSection(
            summary = state.cardSummary,
            failure = state.cardImportFailure,
            onImport = viewModel::importCard,
            onDismissFailure = viewModel::dismissCardImportFailure,
            onShowDetail = { showCardDetail = true },
        )
    }

    if (showCardDetail) {
        state.card?.let { card ->
            CardDetailDialog(
                card = card,
                stateFieldCount = state.cardSummary?.stateFieldCount ?: 0,
                onSave = { field, text -> viewModel.editCard(mapOf(field to text)) },
                onDismiss = { showCardDetail = false },
            )
        }
    }
}

/**
 * 我的：配一次就基本不动的东西，和偶尔用一次的维护操作。
 *
 * 顺序按「多久碰一次」排：模型通道（配一次）→ 叙事要求（偶尔调）→
 * 上下文与压缩（长会话时看）→ 存档与备份（偶尔）。
 */
@Composable
private fun SettingsTab(viewModel: ChatViewModel) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        SectionTitle(stringResource(R.string.settings_section_channel))
        ConfigPanel(
            state = state,
            onSelectProvider = viewModel::selectProvider,
            onModelChange = viewModel::updateModelId,
            onSaveCredential = viewModel::saveCredential,
            onClearCredential = viewModel::clearCredential,
        )

        Divider()

        NarrativePresetSection(
            text = state.narrativePreset,
            enabled = state.narrativePresetEnabled,
            isDefault = state.narrativePresetIsDefault,
            onEnabledChange = viewModel::setNarrativePresetEnabled,
            onSave = viewModel::updateNarrativePreset,
            onReset = viewModel::resetNarrativePreset,
        )

        Divider()

        ContextUsageBar(breakdown = state.tokenBreakdown)

        CompactionSection(
            compactedTurnCount = state.compactedTurnCount,
            volumeCount = state.volumes.size,
            compacting = state.compacting,
            notice = state.compactionNotice,
            onCompact = viewModel::compactNow,
            onDismissNotice = viewModel::dismissCompactionNotice,
        )

        Divider()

        SessionListSection(
            sessions = state.sessions,
            currentSessionId = state.currentSessionId,
            onOpen = viewModel::openSession,
            onDelete = viewModel::deleteSession,
        )

        Divider()

        BackupSection(
            notice = state.backupNotice,
            onExport = viewModel::exportBackup,
            onImport = viewModel::importBackup,
            onUnreadable = viewModel::reportBackupUnreadable,
            onDismissNotice = viewModel::dismissBackupNotice,
        )
    }
}

/** 页签里的小标题。 */
@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/** 区块之间的分隔线。 */
@Composable
private fun Divider() {
    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
}
