package com.mirage.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mirage.engine.chat.ChatTurnPhase
import com.mirage.engine.chat.ChatTurnState
import com.mirage.engine.llm.FinishReason
import com.mirage.engine.llm.ProviderCatalog
import com.mirage.engine.llm.StreamError
import com.mirage.engine.llm.StreamErrorCode

/**
 * 对话主界面。
 *
 * 整个界面只有一屏，配置区直接折叠在顶部而不是单开一页 ——
 * 当前阶段最频繁的操作是「改一下配置、立刻试一次」，来回跳页会很别扭。
 *
 * 所有文字都来自文案资源，这里不出现任何面向使用者的字面量。
 */
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val state by viewModel.state.collectAsState()
    var configExpanded by remember { mutableStateOf(true) }
    // 数值面板默认收起：它是用来核对的，不是主内容，展开着会一直占掉对话的高度
    var statePanelExpanded by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    // 正在编辑 / 正在确认删除的那条。为 null 表示弹窗没开
    var editing by remember { mutableStateOf<TranscriptEntry?>(null) }
    var deleting by remember { mutableStateOf<TranscriptEntry?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            // 键盘弹出时整体上移，否则输入框会被盖住
            .imePadding()
            .padding(horizontal = 12.dp),
    ) {
        HeaderRow(
            expanded = configExpanded,
            onToggle = { configExpanded = !configExpanded },
        )

        if (configExpanded) {
            //配置区
            ConfigPanel(
                state = state,
                onSelectProvider = viewModel::selectProvider,
                onModelChange = viewModel::updateModelId,
                onSaveCredential = viewModel::saveCredential,
                onClearCredential = viewModel::clearCredential,
            )
            Spacer(Modifier.height(8.dp))
        }

        //备份区。
        BackupSection(
            notice = state.backupNotice,
            onExport = viewModel::exportBackup,
            onImport = viewModel::importBackup,
            onUnreadable = viewModel::reportBackupUnreadable,
            onDismissNotice = viewModel::dismissBackupNotice,
        )

        //存档列表区。
        SessionListSection(
            sessions = state.sessions,
            currentSessionId = state.currentSessionId,
            onOpen = viewModel::openSession,
            onDelete = viewModel::deleteSession,
        )

        //角色卡导入区。
        CardImportSection(
            summary = state.cardSummary,
            failure = state.cardImportFailure,
            onImport = viewModel::importCard,
            onDismissFailure = viewModel::dismissCardImportFailure,
        )

        //状态栏。
        StatePanel(
            values = state.stateValues,
            display = state.display,
            expanded = statePanelExpanded,
            onToggle = { statePanelExpanded = !statePanelExpanded },
            // 有可重算的轮次、而且当前没有结算在跑时才给入口。
            // 正在跑的时候再点一次，只会多花一次钱换同一个结果
            onResettle = state.resettleTurn?.let { turn -> { viewModel.retrySettlement(turn) } },
            // 占位说明只在没导入卡时出现。导入之后数值就是卡里的了，再提示会让人困惑
            placeholderNotice = state.cardSummary == null,
        )

        //对话记录
        Transcript(
            state = state,
            onRetrySettlement = viewModel::retrySettlement,
            onCancelSettlement = viewModel::cancelSettlement,
            onRegenerate = viewModel::regenerate,
            onEdit = { editing = it },
            onDelete = { deleting = it },
            onSwitchVersion = viewModel::switchVersion,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )

        //状态区
        StatusArea(state)

        //输入框
        InputRow(
            value = input,
            onValueChange = { input = it },
            isActive = state.turn.isActive,
            onSend = {
                viewModel.send(input)
                input = ""
            },
            onStop = viewModel::stop,
        )
    }

    editing?.let { target ->
        MessageEditDialog(
            initialText = target.text,
            allowRegenerate = target.fromUser,
            onConfirm = {
                viewModel.editMessage(target.id, it)
                editing = null
            },
            onConfirmAndRegenerate = {
                viewModel.editAndRegenerate(target.id, it)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }

    deleting?.let { target ->
        MessageDeleteDialog(
            onConfirm = {
                viewModel.deleteMessage(target.id)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun HeaderRow(expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
        )
        TextButton(onClick = onToggle) {
            Text(stringResource(if (expanded) R.string.config_hide else R.string.config_show))
        }
    }
}

// ---------------------------------------------------------------------------
// 配置区
// ---------------------------------------------------------------------------

@Composable
private fun ConfigPanel(
    state: ChatUiState,
    onSelectProvider: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onSaveCredential: (String) -> Unit,
    onClearCredential: () -> Unit,
) {
    var credentialInput by remember { mutableStateOf("") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.config_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))

            Text(stringResource(R.string.config_provider), style = MaterialTheme.typography.labelMedium)
            ProviderPicker(selectedId = state.providerId, onSelect = onSelectProvider)

            // 未实测过的来源要如实告知，否则一旦失败，使用者会先怀疑自己填错了
            if (ProviderCatalog.unverified.any { it.id == state.providerId }) {
                Text(
                    text = stringResource(R.string.config_unverified_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.modelId,
                onValueChange = onModelChange,
                label = { Text(stringResource(R.string.config_model)) },
                placeholder = { Text(stringResource(R.string.config_model_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = credentialInput,
                onValueChange = { credentialInput = it },
                label = { Text(stringResource(R.string.config_credential)) },
                placeholder = { Text(stringResource(R.string.config_credential_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = if (state.hasCredential) {
                    stringResource(
                        R.string.config_credential_saved,
                        state.credentialPreview.ifBlank { stringResource(R.string.config_credential_mask) },
                    )
                } else {
                    stringResource(R.string.config_credential_none)
                },
                style = MaterialTheme.typography.bodySmall,
            )

            Row {
                Button(
                    onClick = {
                        onSaveCredential(credentialInput)
                        // 存完立刻清掉输入框：密钥没有留在屏幕上的理由
                        credentialInput = ""
                    },
                    enabled = credentialInput.isNotBlank(),
                ) {
                    Text(stringResource(R.string.config_save))
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onClearCredential, enabled = state.hasCredential) {
                    Text(stringResource(R.string.config_clear))
                }
            }
        }
    }
}

/**
 * 来源选择。
 *
 * 用一排按钮而不是下拉菜单：选项数量不多，平铺出来比点开再选少一步操作，
 * 也省掉一个带展开状态的控件。
 */
@Composable
private fun ProviderPicker(selectedId: String, onSelect: (String) -> Unit) {
    Column {
        ProviderCatalog.all.chunked(3).forEach { row ->
            Row {
                row.forEach { profile ->
                    TextButton(onClick = { onSelect(profile.id) }) {
                        Text(
                            text = profile.name,
                            fontWeight = if (profile.id == selectedId) FontWeight.Bold else FontWeight.Normal,
                            color = if (profile.id == selectedId) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 对话记录
// ---------------------------------------------------------------------------

/**
 * 列表里的一行。
 *
 * 把「已定稿的记录」和「正在生成的内容」统一成同一种结构，列表就不必
 * 分两种情况处理。[key] 让列表在内容增删时仍能认出每一行，
 * 没有它的话新消息插入会使所有行整体平移，正在看的内容会突然跳走。
 */
private data class TranscriptRow(
    val key: Any,
    val fromUser: Boolean,
    val text: String,
    /** 回复侧才有。用来把这一行和它那一轮的结算卡片对上。 */
    val turn: Int? = null,
    /**
     * 这一行对应的那条消息。
     *
     * 正在生成的那一行还没进库，所以是 null —— 它也就没有操作入口，
     * 本来也不该有：内容还没生成完，谈不上重新生成或编辑
     */
    val entry: TranscriptEntry? = null,
)

/** 正在生成那一行的固定标识。同一时刻最多只有一行处于这个状态。 */
private const val STREAMING_ROW_KEY = "streaming"

@Composable
private fun Transcript(
    state: ChatUiState,
    onRetrySettlement: (Int) -> Unit,
    onCancelSettlement: () -> Unit,
    onRegenerate: (Long) -> Unit,
    onEdit: (TranscriptEntry) -> Unit,
    onDelete: (TranscriptEntry) -> Unit,
    onSwitchVersion: (Long, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val streamingText = state.turn.text
    val showStreaming = streamingText.isNotEmpty() && state.turn.isActive

    if (state.transcript.isEmpty() && !showStreaming) {
        Column(modifier = modifier, verticalArrangement = Arrangement.Center) {
            Text(
                text = stringResource(R.string.chat_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    // 按「从新到旧」排列，配合下面的反向布局，最新内容就落在底部
    val rows = buildList {
        if (showStreaming) {
            add(TranscriptRow(STREAMING_ROW_KEY, fromUser = false, text = streamingText))
        }
        for (index in state.transcript.indices.reversed()) {
            val entry = state.transcript[index]
            add(TranscriptRow(entry.id, entry.fromUser, entry.text, entry.turn, entry))
        }
    }

    val listState = rememberLazyListState()

    // 是否跟随最新内容。
    //
    // 🔴 只有「用户自己拖动列表」才会改它，别的任何情况都不改。
    //
    // 上一版栽在这里：当时的判断是「插进新行的那一刻，第 0 项还在不在视口里」。
    // 但反向布局插入新行时，原先那条的下标会从 0 变成 1，列表的视口是锚在
    // 那一条上的 —— 于是判断结果永远是「不在底部」，跟随再也不会发生。
    // 症状正是「发完话，AI 的回复不自动出现，得手动滑一下」。
    //
    // 改成只在滚动过程中记录：拖上去看历史就停止跟随，拖回底部就恢复跟随。
    // 插入新行不伴随滚动，所以它碰不到这个值。
    var followNewest by remember { mutableStateOf(true) }

    // 我们自己触发的滚动不算「用户意图」。
    // 不区分的话，自动滚动过程中读到的中间位置会被当成「用户翻到别处了」而停止跟随
    val autoScrolling = remember { mutableStateOf(false) }
    suspend fun scrollToNewest() {
        autoScrolling.value = true
        listState.scrollToItem(0)
        autoScrolling.value = false
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.firstVisibleItemIndex }
            .collect { (scrolling, firstIndex) ->
                if (scrolling && !autoScrolling.value) followNewest = firstIndex == 0
            }
    }

    // 内容变了就跟到最新。
    //
    // 同时看「最新一行是谁」和「一共几行」：新回复开始生成时最新一行会换人，
    // 而定稿时是把流式那一行换成正式一条、行数不变 —— 只看其中一个会漏掉一种情况。
    val newestRowKey = rows.firstOrNull()?.key
    LaunchedEffect(newestRowKey, rows.size) {
        if (followNewest) scrollToNewest()
    }

    // 自己刚发出去的那句无条件可见，并重新开始跟随。
    // 发完话看不到自己说了什么，会让人以为没发出去，然后重复发一次（白花一次钱）
    val newestUserEntryId = state.transcript.lastOrNull { it.fromUser }?.id
    LaunchedEffect(newestUserEntryId) {
        if (newestUserEntryId != null) {
            followNewest = true
            scrollToNewest()
        }
    }

    LazyColumn(
        modifier = modifier,
        state = listState,
        // 🔴 反向布局是这里的关键，不是风格选择。
        //
        // 它让列表从底部开始排布，于是：
        // 一、正在变长的那条消息，底边始终贴着视口底边、向上生长，
        //     新产出的文字永远可见，不需要任何代码去追着滚动；
        // 二、使用者往上翻看历史时，列表自然停住，没有代码来抢控制权。
        //
        // 先前的做法是每收到一个字就触发一次滚动到底部，有两个连带问题：
        // 滚动动画被不断取消重启造成抖动，以及「滚到某一项」的语义是把该项
        // 顶部对齐视口顶部 —— 当这一项比屏幕还高时，永远到不了它的末尾，
        // 手动下拉也会被立刻拽回去。反向布局把这两个问题一并消除。
        reverseLayout = true,
    ) {
        items(
            count = rows.size,
            key = { position -> rows[position].key },
        ) { position ->
            val row = rows[position]
            // 反向布局下，先画的在下面。结算卡片要出现在这条回复的下方，
            // 所以它得排在气泡前面 —— 这一点和正常阅读顺序相反，容易写错
            row.turn?.let { turn ->
                state.settlementOf(turn)?.let { record ->
                    SettlementReceiptCard(
                        record = record,
                        queuedAhead = state.queuedAhead(turn),
                        onRetry = onRetrySettlement,
                        onCancel = onCancelSettlement,
                    )
                }
            }
            // 反向布局下先画的在下面，所以操作行要排在气泡前面才会显示在气泡下方
            row.entry?.let { entry ->
                MessageActions(
                    entry = entry,
                    enabled = !state.turn.isActive,
                    onRegenerate = { onRegenerate(entry.id) },
                    onEdit = { onEdit(entry) },
                    onDelete = { onDelete(entry) },
                    onSwitchVersion = { delta -> onSwitchVersion(entry.id, delta) },
                )
            }
            MessageBubble(fromUser = row.fromUser, text = row.text)
        }
    }
}

/**
 * 一条消息气泡。
 *
 * 左右分置是刻意的基本要求：同侧显示时，一旦回复长起来，
 * 谁说的话会完全分不清。
 */
@Composable
private fun MessageBubble(fromUser: Boolean, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (fromUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier.fillMaxWidth(0.85f),
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = stringResource(
                        if (fromUser) R.string.chat_speaker_user else R.string.chat_speaker_assistant,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 正文按角色扮演的排版约定渲染：*动作* 斜体、台词提亮
                Text(text = narrativeText(text), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 状态区
// ---------------------------------------------------------------------------

@Composable
private fun StatusArea(state: ChatUiState) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        state.issue?.let { issue ->
            Text(
                text = stringResource(
                    when (issue) {
                        ConfigIssue.MissingCredential -> R.string.error_need_credential
                        ConfigIssue.MissingModel -> R.string.error_need_model
                    },
                ),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        phaseText(state.turn)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        // 省略了历史必须说出来。聊着聊着模型突然不记得前面的事，
        // 用户只会以为「这 AI 记性真差」，而真相是我们没把那段发过去
        if (state.droppedHistoryCount > 0) {
            Text(
                text = stringResource(R.string.context_trimmed, state.droppedHistoryCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.turn.error?.let { error ->
            Text(
                text = errorText(error),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            // 服务方的原话单独一行。它常常是英文或很技术化，不适合当主提示，
            // 但排查时往往只有它说得清到底怎么了
            error.diagnostic?.takeIf { it.isNotBlank() }?.let { detail ->
                Text(
                    text = stringResource(R.string.error_detail, detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        finishNote(state.turn.finishReason, state.turn.phase)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        if (state.turn.phase == ChatTurnPhase.Completed) {
            Text(
                text = usageText(state.turn.usage),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.turn.servingProvider?.let {
                Text(
                    text = stringResource(R.string.usage_served_by, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun phaseText(turn: ChatTurnState): String? = when {
    turn.isThinkingOnly -> stringResource(R.string.phase_thinking)
    turn.phase == ChatTurnPhase.Connecting -> stringResource(R.string.phase_connecting)
    turn.phase == ChatTurnPhase.Preparing -> stringResource(R.string.phase_preparing)
    turn.phase == ChatTurnPhase.Generating -> stringResource(R.string.phase_generating)
    turn.phase == ChatTurnPhase.Cancelled -> stringResource(R.string.phase_cancelled)
    else -> null
}

@Composable
private fun finishNote(reason: FinishReason?, phase: ChatTurnPhase): String? {
    if (phase != ChatTurnPhase.Completed) return null
    return when (reason) {
        FinishReason.Length -> stringResource(R.string.finish_length)
        FinishReason.ContentFilter -> stringResource(R.string.finish_content_filter)
        else -> null
    }
}

@Composable
private fun errorText(error: StreamError): String = stringResource(
    when (error.code) {
        StreamErrorCode.Unauthorized -> R.string.error_unauthorized
        StreamErrorCode.Forbidden -> R.string.error_forbidden
        StreamErrorCode.RateLimited -> R.string.error_rate_limited
        StreamErrorCode.QuotaExhausted -> R.string.error_quota
        StreamErrorCode.ModelUnavailable -> R.string.error_model_unavailable
        StreamErrorCode.BadRequest -> R.string.error_bad_request
        StreamErrorCode.ServerError -> R.string.error_server
        StreamErrorCode.IdleTimeout -> R.string.error_idle_timeout
        StreamErrorCode.NetworkFailure -> R.string.error_network
        StreamErrorCode.MalformedPayload -> R.string.error_malformed
        StreamErrorCode.Cancelled -> R.string.error_cancelled
        StreamErrorCode.Unknown -> R.string.error_unknown
    },
)

// ---------------------------------------------------------------------------
// 输入区
// ---------------------------------------------------------------------------

@Composable
private fun InputRow(
    value: String,
    onValueChange: (String) -> Unit,
    isActive: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        // 停止的真实含义必须说清楚：它只是不再显示，服务方可能照样把这次回复生成完，
        // 费用不一定减少。暗示「停止=省钱」会让使用者做出错误判断
        if (isActive) {
            Text(
                text = stringResource(R.string.chat_stop_explain),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text(stringResource(R.string.chat_input_hint)) },
                modifier = Modifier.weight(1f),
                enabled = !isActive,
            )
            Spacer(Modifier.width(8.dp))
            if (isActive) {
                Button(onClick = onStop) { Text(stringResource(R.string.chat_stop)) }
            } else {
                Button(onClick = onSend, enabled = value.isNotBlank()) {
                    Text(stringResource(R.string.chat_send))
                }
            }
        }
    }
}
