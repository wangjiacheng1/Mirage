package com.mirage.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mirage.engine.chat.ChatTurnState
import com.mirage.engine.credential.CredentialPreview
import com.mirage.engine.credential.CredentialStore
import com.mirage.engine.llm.ChatCompletionClient
import com.mirage.engine.llm.ChatCompletionRequest
import com.mirage.engine.llm.ChatMessage
import com.mirage.engine.llm.ChatRole
import com.mirage.engine.card.CardParseFailure
import com.mirage.engine.card.CardParseResult
import com.mirage.engine.card.CardStateInit
import com.mirage.engine.card.CardStateInitializer
import com.mirage.engine.card.CardTextSanitizer
import com.mirage.engine.history.MessageTree
import com.mirage.engine.card.CharacterCard
import com.mirage.engine.card.CharacterCardParser
import com.mirage.engine.card.rawJsonText
import com.mirage.engine.llm.ProviderCatalog
import com.mirage.engine.prompt.CardMacros
import com.mirage.engine.prompt.CardPromptAssembler
import com.mirage.engine.prompt.TokenBudget
import com.mirage.engine.prompt.EntryPurpose
import com.mirage.engine.state.StateDisplayInference
import com.mirage.engine.state.StateDisplayProfile
import com.mirage.engine.llm.ProviderProfile
import com.mirage.engine.llm.TokenUsage
import com.mirage.engine.settlement.SettlementCoordinator
import com.mirage.engine.settlement.SettlementPhase
import com.mirage.engine.settlement.SettlementOutcome
import com.mirage.engine.settlement.SettlementQueue
import com.mirage.engine.settlement.SettlementRecord
import com.mirage.engine.settlement.SettlementTask
import com.mirage.engine.state.SettlementReceipt
import com.mirage.engine.state.SettlementStatus
import com.mirage.engine.state.StateEventLog
import com.mirage.engine.state.StateSchema
import com.mirage.engine.state.StateStore
import com.mirage.engine.state.StateValue
import com.mirage.engine.store.BackupImportSummary
import com.mirage.engine.store.BackupReadFailure
import com.mirage.engine.store.BackupReadResult
import com.mirage.engine.store.BackupService
import com.mirage.engine.store.ChatStore
import com.mirage.engine.store.ReceiptJson
import com.mirage.engine.store.StoredReceipt
import com.mirage.engine.store.SessionRecord
import com.mirage.engine.store.SessionSummary
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 对话记录里的一条。
 *
 * [id] 用来在列表里稳定地标识这一条。没有它的话，新消息插入会让所有条目的
 * 位置整体平移，列表无法判断「原来看的是哪一条」，正在阅读的内容会突然跳走。
 */
data class TranscriptEntry(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    /**
     * 这条回复对应第几轮。只有回复侧有值。
     *
     * 结算记录是按轮次索引的，两边要靠它对上 —— 用列表下标会在将来支持
     * 分支与回溯之后立刻失效，那时同一个下标可能指向完全不同的内容。
     */
    val turn: Int? = null,
    /** 这条是同一句话的第几个版本（从 1 开始）。只有重新生成过才会大于 1。 */
    val versionIndex: Int = 1,
    /** 同一句话一共有几个版本。 */
    val versionCount: Int = 1,
)

/**
 * 导入进来的卡的概况，给界面显示用。
 *
 * 不直接把整张卡塞进界面状态：卡里有大段设定文本，每次界面重组都要跟着比较一遍，
 * 而界面要显示的其实只有这几个数字。
 */
data class CardSummary(
    val name: String,
    /** 讲故事用的设定条目有几条。 */
    val narrativeEntryCount: Int,
    /** 状态栏有几项。0 表示这张卡没有状态玩法。 */
    val stateFieldCount: Int,
    /** 读初始数值时跳过或拿不准的地方有几处。 */
    val noteCount: Int,
    /** 这张卡自己带了算数值的规则没有。 */
    val hasSettlementRules: Boolean,
)

/**
 * 备份操作的结果。
 *
 * 只给分类和数字，具体说什么由界面决定。
 */
sealed interface BackupNotice {
    /** 导出成功，带上写出去的内容有多大，让用户对文件大小有个预期。 */
    data class Exported(val sessionCount: Int, val byteCount: Int) : BackupNotice

    data class Imported(val summary: BackupImportSummary) : BackupNotice

    data class Failed(val code: BackupReadFailure?) : BackupNotice
}

/**
 * 界面上一张结算卡片要显示的内容。
 *
 * 它有两个来源：**正在跑的队列**（带排队、进行中这些中间状态），
 * 和**存档里读回来的**（重启之后只剩最终结果）。
 * 界面不该关心这份回执是哪来的，所以在这里合成一种形状。
 *
 * @property phase 为 null 表示这是从存档读回来的 —— 那一轮早就算完了，
 *   不存在「排队中」「正在算」这些中间状态。
 */
data class SettlementView(
    val turn: Int,
    val phase: SettlementPhase?,
    val receipt: SettlementReceipt,
    val usage: TokenUsage,
    val attempt: Int,
) {
    val status: SettlementStatus get() = receipt.status

    /** 能不能重算。正在排队或正在算的时候不给入口，再点只是多花一次钱。 */
    val retryable: Boolean
        get() = (phase == null || phase == SettlementPhase.Settled) &&
            status in setOf(
                SettlementStatus.Error,
                SettlementStatus.Stale,
                SettlementStatus.Interrupted,
            )
}

/** 配置不全时的阻塞项。只给分类，文案由界面决定。 */
enum class ConfigIssue {
    MissingCredential,
    MissingModel,
}

/** 界面需要的全部状态。 */
data class ChatUiState(
    //模型提供商ID
    val providerId: String,
    //模型ID
    val modelId: String,
    val credentialPreview: String = "",
    val hasCredential: Boolean = false,
    //对话记录
    val transcript: List<TranscriptEntry> = emptyList(),
    //一次请求的完整状态枚举
    val turn: ChatTurnState = ChatTurnState(),
    //配置不全时的阻塞项
    val issue: ConfigIssue? = null,
    /** 当前权威数值。来自状态仓，界面只读。 */
    val stateValues: Map<String, StateValue> = emptyMap(),
    /** 每一轮的结算记录，按提交顺序。 */
    val settlements: List<SettlementRecord> = emptyList(),
    /** 状态栏每一项该怎么显示（写数字还是画进度条）。 */
    val display: StateDisplayProfile = StateDisplayProfile(),
    /** 当前用的角色卡概况。没导入卡时为 null，这时跑的是内置占位数值。 */
    val cardSummary: CardSummary? = null,
    /** 导入卡失败的原因。只给分类，文案由界面决定。 */
    val cardImportFailure: CardParseFailure? = null,
    /** 全部存档，最近玩过的排在前面。 */
    val sessions: List<SessionSummary> = emptyList(),
    /** 当前打开的是哪一局。 */
    val currentSessionId: Long? = null,
    /**
     * 从存档里读回来的结算回执，按轮次索引。
     *
     * 重启之后队列是空的，没有它的话所有回执都会消失 ——
     * 而状态栏的数值明明还在，一边有数字一边没有交代，看着像出了故障。
     */
    val restoredReceipts: Map<Int, SettlementView> = emptyMap(),
    /** 这次请求为了装进上下文而省略掉的历史条数。 */
    val droppedHistoryCount: Int = 0,
    /** 这次请求估算出来的输入长度。估算值，不是服务商的口径。 */
    val estimatedPromptTokens: Int = 0,
    /** 备份导出 / 导入之后要显示的那条结果。为 null 表示没有待显示的结果。 */
    val backupNotice: BackupNotice? = null,
) {
    /**
     * 状态栏上「重新结算」该算哪一轮。
     *
     * 取最后一轮。为空的情况有两种：还没结算过任何一轮，或者当前有结算正在跑 ——
     * 后者不给入口，因为再点一次只是多花一次钱换同一个结果。
     *
     * 为什么状态栏也要有这个入口：回执卡片上的那个只在失败/过期/中断时出现，
     * 而「算成功了但结果不对」同样需要重算（模型漏算了某一项是常见情况），
     * 那种情况下卡片上没有按钮，用户就无路可走了。
     */
    val resettleTurn: Int?
        get() = when {
            settlements.any { it.status == SettlementStatus.Pending } -> null
            else -> settlements.lastOrNull()?.turn
        }

    /**
     * 取某一轮要显示的结算卡片。
     *
     * 正在跑的队列优先：它带着「排队中 / 正在算」这些中间状态，
     * 而存档里读回来的永远是最终结果。同一轮两边都有时，队列里的才是最新的。
     */
    fun settlementOf(turn: Int): SettlementView? {
        settlements.firstOrNull { it.turn == turn }?.let { record ->
            return SettlementView(
                turn = record.turn,
                phase = record.phase,
                receipt = record.outcome.receipt,
                usage = record.outcome.usage,
                attempt = record.attempt,
            )
        }
        return restoredReceipts[turn]
    }

    /**
     * 排在某一轮前面、还没跑完的任务数。
     *
     * 在界面侧算而不是让队列维护一个字段：它随时在变，存成字段就要考虑
     * 什么时候刷新；而这里每次重组现算一遍，天然不会过期。
     */
    fun queuedAhead(turn: Int): Int {
        val index = settlements.indexOfFirst { it.turn == turn }
        if (index <= 0) return 0
        return settlements.take(index).count { it.status == SettlementStatus.Pending }
    }

    val profile: ProviderProfile?
        get() = ProviderCatalog.findById(providerId)
}

/**
 * 对话界面的状态持有者。
 *
 * 这里刻意只做三件事：搬运状态、拼装请求、管理进行中的任务。
 * 「一个事件该让状态变成什么样」的判断全部在引擎侧的状态机里完成 ——
 * 那部分可以离线逐条验证，放到这里就只能靠真机点一遍。
 *
 * ⚠️ 当前的对话记录是一个平铺列表，这是已知的临时做法。
 * 最终的消息结构需要支持分支与回溯，那依赖本地存储，属于后续工作。
 * 现在提前搭一个半成品的树结构没有意义，但要清楚这一块会被替换。
 */
/** 当前时间。单独抽出来，是为了把「取系统时间」这件事集中在一处。 */
private fun currentTimeMillis(): Long = System.currentTimeMillis()

class ChatViewModel(
    private val chatClient: ChatCompletionClient,
    private val credentialStore: CredentialStore,
    private val selectionStore: SelectionStore,
    private val settlementCoordinator: SettlementCoordinator,
    private val chatStore: ChatStore,
) : ViewModel() {

    private val _state: MutableStateFlow<ChatUiState>
    val state: StateFlow<ChatUiState>

    /** 进行中的请求。保留它才能在使用者要求停止时真正断开。 */
    private var streamJob: Job? = null

    /**
     * 这一轮的回复还没存进库。
     *
     * 🔴 没有它会出事：一次生成结束后，界面状态里仍然留着那段完整回复
     * （用量、停止原因都要靠它显示，不能一存完就清空）。此时任何一次
     * 「停止」都会把这段已经存过的文字**再存一遍**，于是对话里多出一条
     * 一模一样的回复。切换存档时会调用停止，所以切一次就多一条。
     */
    private var pendingReply: Boolean = false

    /**
     * 正在把回复写进库的那个任务。
     *
     * 切换存档前必须等它做完。不等的话会出现这种顺序：
     * 停止 → 开始写上一局的回复（卡在数据库这一步）→ 切到新局、各项编号被重置
     * → 写库任务恢复，把上一局的回复接到了新局的消息树上，还把新局的
     * 「聊到哪一条」改掉了。这种错一旦发生，两局的数据就缠在一起了
     */
    private var commitJob: Job? = null

    /** 下一条记录的标识。只增不减，保证同一次会话里不会重复。 */
    private var nextEntryId: Long = 0

    /**
     * 生成一条记录。
     *
     * 刻意在状态更新之外调用：状态更新的那个代码块在并发修改时可能被重跑，
     * 把自增放进去会让标识出现跳号甚至重复。
     */
    private fun newEntry(fromUser: Boolean, text: String, turn: Int? = null) =
        TranscriptEntry(id = nextEntryId++, fromUser = fromUser, text = text, turn = turn)

    // ------------------------------------------------------------------
    // 数值与结算
    // ------------------------------------------------------------------

    /**
     * 权威数值的持有者。
     *
     * 初始值目前来自内置占位档，接入外部设定之后换成从那里装载，
     * 这一行以下的所有逻辑都不用动。
     */
    private val stateStore = StateStore(PlaceholderStateProfile.initialValues)

    /**
     * 状态栏的字段定义。决定哪些路径合法、各自有什么约束。
     *
     * 导入角色卡时整份替换，所以是可变的。没导卡时用内置占位档，
     * 这样没有卡也能把结算链路跑起来看。
     */
    private var schema = PlaceholderStateProfile.schema

    /** 当前这张卡。没导入时为 null，这时按内置占位档跑。 */
    private var card: CharacterCard? = null

    /**
     * 卡作者写的字段说明。
     *
     * 它讲的是「哪一项什么时候该变、变多少」，这部分卡作者比我们清楚，
     * 所以照抄给模型当参考。
     *
     * 🔴 但它只是参考，**输出格式始终用我们自己的那套**。
     * 卡是按别的框架写的，里面往往带着那套框架的提交方式和路径写法；
     * 一旦拿它当指令，模型会按那一套输出，我们这边一个字段都认不出来。
     * 这是实测踩过的坑：整轮结算全废，界面上只剩一句「结算失败」。
     */
    private var cardFieldNotes: String? = null

    /** 已经结算过的最大轮次。回复定稿时加一，作为这一轮的编号。 */
    private var lastTurn: Int = 0

    /** 当前这一局的编号。为 null 表示还没建过存档（第一次发消息时会建）。 */
    private var sessionId: Long? = null

    /** 这一局的开局数值。每次回放都从它开始加。 */
    private var sessionInitialValues: Map<String, StateValue> = PlaceholderStateProfile.initialValues

    /** 最新的一条消息ID，当前聊到哪一条了。新消息挂在它下面。 */
    private var headMessageId: Long? = null

    /**
     * 轮次 → 那条回复在库里的编号。
     *
     * 状态变化记录要挂在具体某条消息上（切分支时靠它判断这次变化算不算发生过），
     * 而结算结果回来时手上只有轮次，所以这里留一张对照表。
     */
    private val messageIdByTurn = mutableMapOf<Int, Long>()

    /** 已经存过库的回执：轮次 → 第几次尝试。用来避免重复写库。 */
    private val persistedReceipts = mutableMapOf<Int, Int>()

    /**
     * 当前这一局的消息树。
     *
     * 留在内存里，是因为「这条有几个版本」「切到上一个版本」这些操作
     * 每次重组都要问，每次都去查库会让界面卡。
     * 任何改动消息的操作之后都要重建它 —— 树过期了，分支切换就会指向不存在的消息。
     */
    private var messageTree: MessageTree = MessageTree(emptyList())

    init {
        // 优先沿用上次的选择；没有记录时才退回到第一个已验证的接入点
        val restoredId = selectionStore.readProviderId()
        val profile = restoredId?.let { ProviderCatalog.findById(it) }
            ?: ProviderCatalog.verified.first()
        _state = MutableStateFlow(
            ChatUiState(
                providerId = profile.id,
                modelId = selectionStore.readModelId(profile.id)
                    ?: profile.defaultModelId.orEmpty(),
                // 没导入卡时也要有显示方案，否则状态栏上的占位数值全都只能写数字
                display = StateDisplayInference.infer(PlaceholderStateProfile.initialValues, schema),
            ),
        )
        state = _state.asStateFlow()
        refreshCredentialPresence()
    }

    /**
     * 结算队列。
     *
     * 一次只跑一个：多个结算同时改一份数值必然出乱子，而丢弃后来的那一轮
     * 又会让那一轮的变化永远不发生。排队是这两者之间唯一不丢数据的选择。
     *
     * 落盘这一步交给状态仓，队列自己不碰数值 —— 它只负责调度和版本比对。
     */
    private val settlementQueue = SettlementQueue(
        //将ViewModel的携程工作域作为队列的生命周期
        scope = viewModelScope,
        //当前状态结算的版本号
        revisionOf = { stateStore.revision },
        //提交方法
        commit = { receipt ->
            stateStore.commit(receipt.changes)
            persistStateEvents(receipt)
        },
        runner = ::runSettlement,
    )

    init {
        // 把数值和结算记录镜像进界面状态。
        // 界面只认一个状态对象，不直接订阅引擎里的多个来源 ——
        // 那会让「这一帧看到的数值和回执是不是同一时刻的」变得无法保证
        viewModelScope.launch {
            stateStore.values.collect { values ->
                _state.update { it.copy(stateValues = values) }
            }
        }
        viewModelScope.launch {
            settlementQueue.records.collect { records ->
                _state.update { it.copy(settlements = records) }
                persistSettledReceipts(records)
            }
        }
        restoreLatestSession()
    }

    // ------------------------------------------------------------------
    // 配置
    // ------------------------------------------------------------------

    fun selectProvider(providerId: String) {
        val profile = ProviderCatalog.findById(providerId) ?: return
        selectionStore.writeProviderId(providerId)
        _state.update {
            it.copy(
                providerId = providerId,
                // 换来源时优先用该来源上次填过的模型名，其次是它的建议值。
                // 绝不沿用上一个来源的模型名 —— 那必然无效，而服务端会回
                // 「模型不存在」，把排查方向引向完全错误的地方
                modelId = selectionStore.readModelId(providerId)
                    ?: profile.defaultModelId.orEmpty(),
                // 摘要属于上一个来源，必须清掉，否则会让人以为新来源也配好了
                credentialPreview = "",
                issue = null,
            )
        }
        refreshCredentialPresence()
    }

    fun updateModelId(value: String) {
        selectionStore.writeModelId(_state.value.providerId, value)
        _state.update { it.copy(modelId = value, issue = null) }
    }

    fun saveCredential(secret: String) {
        val trimmed = secret.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            credentialStore.write(_state.value.providerId, trimmed)
            // 摘要用刚输入的值现算，而不是再读一次解密。
            // 能少接触一次明文就少一次
            _state.update {
                it.copy(
                    credentialPreview = CredentialPreview.of(trimmed),
                    hasCredential = true,
                    issue = null,
                )
            }
        }
    }

    fun clearCredential() {
        viewModelScope.launch {
            credentialStore.delete(_state.value.providerId)
            _state.update { it.copy(credentialPreview = "", hasCredential = false) }
        }
    }

    /**
     * 刷新「是否已配置」。
     *
     * 只查存在性，不解密。界面要显示的只是「配过没配过」，
     * 为此解密一次没有必要，而每次解密都是一次让明文落在内存里的机会。
     */
    private fun refreshCredentialPresence() {
        viewModelScope.launch {
            val present = credentialStore.contains(_state.value.providerId)
            _state.update { it.copy(hasCredential = present) }
        }
    }

    // ------------------------------------------------------------------
    // 发送一条对话
    // ------------------------------------------------------------------

    fun send(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        // 已经有请求在跑时忽略。允许并发会让同一轮对话出现两份回复，
        // 而且使用者是在为两次调用付费
        if (_state.value.turn.isActive) return

        val snapshot = _state.value
        if (snapshot.modelId.isBlank()) {
            _state.update { it.copy(issue = ConfigIssue.MissingModel) }
            return
        }
        val profile = snapshot.profile ?: return

        // 请求内容在追加这一条之前就定下来，避免「把本轮提问重复两次」这种顺序错误
        val outgoing = buildOutgoingMessages(snapshot.transcript, text)

        streamJob = viewModelScope.launch {
            val secret = credentialStore.read(snapshot.providerId)
            if (secret.isNullOrBlank()) {
                _state.update { it.copy(issue = ConfigIssue.MissingCredential) }
                return@launch
            }

            // 先落库再上界面：界面上每条的标识直接用库里的编号。
            // 两套编号（内存一套、库里一套）会在重启后对不上，
            // 列表的滚动位置和结算记录都会错位
            val userEntry = persistAndBuildEntry(fromUser = true, text = text, turn = null)
            pendingReply = true
            _state.update {
                it.copy(
                    transcript = it.transcript + userEntry,
                    turn = ChatTurnState().started(),
                    issue = null,
                )
            }

            streamReply(profile, secret, snapshot.modelId, outgoing)
        }
    }

    /**
     * 发一次请求并把流式结果接到界面上。
     *
     * 发消息和重新生成都走这里。两者唯一的差别在于「要不要先往树上挂一条用户消息」，
     * 请求之后的处理完全一样，没必要写两遍。
     */
    private suspend fun streamReply(
        profile: ProviderProfile,
        credential: String,
        modelId: String,
        outgoing: List<ChatMessage>,
    ) {
        chatClient.stream(
            profile = profile,
            credential = credential,
            request = ChatCompletionRequest(modelId = modelId, messages = outgoing),
        ).collect { event ->
            _state.update { it.copy(turn = it.turn.reduce(event)) }
        }
        commitReply()
    }

    // ------------------------------------------------------------------
    // 改动已有消息
    // ------------------------------------------------------------------

    /**
     * 重新生成某条回复。
     *
     * 做法是把「聊到哪一条」退回它的上一条，然后重新发一次请求。
     * 新回复会挂在同一个上一条下面 —— 于是这句话就有了两个并列版本，
     * 旧的那条**完全没动**，随时可以切回去。
     *
     * 这就是消息树存在的意义：不需要删除、不需要覆盖，只是多挂了一条。
     */
    fun regenerate(messageId: Long) {
        if (_state.value.turn.isActive) return
        val target = messageTree.find(messageId) ?: return
        if (target.fromUser) return

        streamJob = viewModelScope.launch {
            // 退回上一条。注意这里不删旧回复，它仍挂在树上
            moveHeadTo(target.parentId)
            generateFromHead()
        }
    }

    /**
     * 改掉自己说过的话，并让模型重新接着回。
     *
     * 做法是**新开一条用户消息**挂在同一个上一条下面 —— 于是这句话有了两个版本，
     * 用 ‹ › 能在「我原本说的」和「我改成的」之间切换，各自带着各自的回复。
     *
     * 为什么不是原地改文字：原地改之后，旧回复是按旧说法生成的，却挂在新说法下面，
     * 前后对不上；而把旧说法也留成一个版本，两条线各自自洽，切过去看到的是
     * 当时完整的样子。
     *
     * 想单纯改个错字不另起版本，用「只保存」那个按钮。
     */
    fun editAndRegenerate(messageId: Long, text: String) {
        if (_state.value.turn.isActive) return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val target = messageTree.find(messageId) ?: return

        streamJob = viewModelScope.launch {
            // 先退回这句话的上一条，新消息才会挂成它的兄弟而不是它的孩子
            headMessageId = target.parentId
            val entry = persistAndBuildEntry(fromUser = true, text = trimmed, turn = null)
            _state.update { it.copy(transcript = it.transcript + entry) }
            refreshFromHead()
            generateFromHead()
        }
    }

    /**
     * 从当前这条线的末尾接着生成一条回复。
     *
     * 不追加新的用户输入 —— 这条线最后一句本来就是用户说的话，
     * 再加一次就重复了。重新生成和「改完重新生成」都走这里。
     */
    private suspend fun generateFromHead() {
        val snapshot = _state.value
        val profile = snapshot.profile ?: return
        if (snapshot.modelId.isBlank()) {
            _state.update { it.copy(issue = ConfigIssue.MissingModel) }
            return
        }
        val secret = credentialStore.read(snapshot.providerId)
        if (secret.isNullOrBlank()) {
            _state.update { it.copy(issue = ConfigIssue.MissingCredential) }
            return
        }

        val outgoing = buildOutgoingMessages(_state.value.transcript, newInput = null)
        pendingReply = true
        _state.update { it.copy(turn = ChatTurnState().started(), issue = null) }
        streamReply(profile, secret, snapshot.modelId, outgoing)
    }

    /**
     * 切到同一句话的另一个版本。
     *
     * @param delta -1 看上一个版本，+1 看下一个。
     *
     * 切过去之后要顺着那条分支一路走到它自己的末尾 —— 那个版本后面可能
     * 已经接着聊了好几轮，只显示它一条会让人以为后面的内容丢了。
     */
    fun switchVersion(messageId: Long, delta: Int) {
        if (_state.value.turn.isActive) return
        val versions = messageTree.alternativesOf(messageId)
        val current = versions.indexOfFirst { it.id == messageId }
        val target = versions.getOrNull(current + delta) ?: return

        viewModelScope.launch {
            moveHeadTo(messageTree.latestDescendantOf(target.id)?.id ?: target.id)
        }
    }

    /** 改掉一条消息的文字。 */
    fun editMessage(messageId: Long, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            chatStore.updateMessageText(messageId, trimmed)
            // 改文字不影响树的形状，但界面上那条要跟着变，所以照样整体刷一遍。
            // 单独改一条看着更省事，却要额外维护一条更新路径，不值得
            refreshFromHead()
        }
    }

    /**
     * 删掉一条消息。
     *
     * 挂在它后面的内容会一起删 —— 那些内容是接着这条往下写的，
     * 留着就成了没有上文的碎片。删完之后退回它的上一条。
     */
    fun deleteMessage(messageId: Long) {
        if (_state.value.turn.isActive) return
        val target = messageTree.find(messageId) ?: return
        viewModelScope.launch {
            chatStore.deleteMessages(messageTree.descendantsOf(messageId).toList())
            moveHeadTo(target.parentId)
        }
    }

    /** 把「聊到哪一条」挪到指定的消息上，并刷新界面。 */
    private suspend fun moveHeadTo(messageId: Long?) {
        headMessageId = messageId
        val session = sessionId
        if (session != null && messageId != null) {
            chatStore.updateHead(session, messageId, currentTimeMillis())
        }
        refreshFromHead()
    }

    /**
     * 停止。
     *
     * ⚠️ 语义是**停止接收和显示**，不等于让服务方停止生成。
     * 已经产生的开销不会因为这里断开而减少，界面文案必须如实说明这一点。
     */
    fun stop() {
        streamJob?.cancel()
        streamJob = null
        // 在这里直接改状态而不是依赖协程内部：协程已被取消，
        // 里面再想更新状态就得特意绕过取消机制，不值得
        _state.update { it.copy(turn = it.turn.cancelled()) }
        // 定稿要写库，所以得起一个协程。不能复用刚才那个 —— 它已经被取消了，
        // 在被取消的协程里做挂起调用会立刻再抛一次取消
        commitJob = viewModelScope.launch { commitReply() }
    }

    fun dismissIssue() = _state.update { it.copy(issue = null) }

    /**
     * 把本轮已产出的回复定稿进对话记录。
     *
     * 中止和失败的情况同样要定稿 —— 那些内容已经生成、也已经计费，
     * 丢掉它对使用者只有损失。
     */
    private suspend fun commitReply() {
        // 模型仍有可能自己吐出状态块（它见过太多这种例子）。
        // 这里统一清一次：留着它既会在界面上显示成乱码一样的东西，
        // 也会进到下一轮的历史里，把这个习惯一直传下去
        // 没有待定稿的内容就直接走开。界面状态里那段文字是上一轮留下来给
        // 用量展示用的，不是新产出的内容
        if (!pendingReply) return
        pendingReply = false

        val reply = CardTextSanitizer.stripStatusBlocks(_state.value.turn.text)
        if (reply.isBlank()) return
        val turn = ++lastTurn
        val replyEntry = persistAndBuildEntry(fromUser = false, text = reply, turn = turn)
        _state.update { it.copy(transcript = it.transcript + replyEntry) }
        messageIdByTurn[turn] = replyEntry.id
        submitSettlement(turn, reply)
        // 树要跟着重建：刚挂上去的这条可能是某句话的第二个版本，
        // 不重建的话界面上算不出「2/2」，左右切换也找不到另一个版本
        refreshMessageTree()
    }

    // ------------------------------------------------------------------
    // 结算
    // ------------------------------------------------------------------

    /**
     * 把刚定稿的这一轮送去结算。
     *
     * 刻意等正文完全收完才发起，而不是边收边算：数值要依据的是整段内容，
     * 只看到一半就下结论必然算错，而且算完还得推翻重算，等于白花一次钱。
     *
     * 被中断的那一轮同样会送去结算 —— 已经产出的内容里可能确实发生了事情，
     * 它既然已经计费，就不该再白白丢掉里面的数值变化。
     */
    private fun submitSettlement(turn: Int, narrative: String) {
        if (schema.fields.isEmpty()) return
        settlementQueue.submit(buildTask(turn, narrative))
    }

    /**
     * 重新结算某一轮。
     *
     * 关键在于**重新取一次快照**，而不是把原来那个任务再提交一遍：
     * 原任务里的数值是当时的，拿它去算只会又得到一个基于旧前提的结果，
     * 再一次被判作废。
     */
    fun retrySettlement(turn: Int) {
        // 队列里还留着这一轮就用它的正文；重启之后队列是空的，
        // 那就从对话记录里取那条回复的原文 —— 要算的本来就是它
        val narrative = settlementQueue.recordOf(turn)?.task?.narrative
            ?: _state.value.transcript.firstOrNull { it.turn == turn }?.text
            ?: return
        settlementQueue.submit(buildTask(turn, narrative))
    }

    /**
     * 停掉正在进行的结算。
     *
     * 和停止正文一样，它停的是这边的等待，服务方那边可能照样把这次算完，
     * 开销不一定减少。被停掉的那一轮会留在界面上标为中断，而不是无声消失。
     */
    fun cancelSettlement() = settlementQueue.cancelActive()

    /**
     * 构建一次输入请求
     */
    private fun buildTask(turn: Int, narrative: String): SettlementTask {
        // 值和版本号必须来自同一次快照，分两次读会出现版本号已经变了、
        // 值还是旧的这种错位，之后的过期判定会得出相反的结论
        val snapshot = stateStore.snapshot()
        return SettlementTask(
            turn = turn,
            basedOnRevision = snapshot.revision,
            narrative = narrative,
            stateSnapshot = snapshot.values,
            schema = schema,
        )
    }

    /**
     * 真正去跑一次结算。
     *
     * 配置不全时返回一份失败回执，而不是抛异常：对这一轮来说结果是一样的
     * （没算成），但返回回执能让界面保留重算入口，抛异常只会留下一条日志。
     */
    private suspend fun runSettlement(task: SettlementTask): SettlementOutcome {
        val snapshot = _state.value
        val profile = snapshot.profile
        val secret = credentialStore.read(snapshot.providerId)
        if (profile == null || secret.isNullOrBlank() || snapshot.modelId.isBlank()) {
            return SettlementOutcome(
                turn = task.turn,
                basedOnRevision = task.basedOnRevision,
                receipt = SettlementReceipt(turn = task.turn, status = SettlementStatus.Error),
            )
        }
        return settlementCoordinator.settle(
            profile = profile,
            credential = secret,
            // 暂时和正文用同一个模型。两者迟早要分开配置：结算是一次格式明确的
            // 归纳工作，用更便宜的模型就够，而正文要的是文笔
            modelId = snapshot.modelId,
            task = task,
            fieldNotes = cardFieldNotes,
        )
    }

    /**
     * 拼这次要发出去的全部消息。
     *
     * @param newInput 用户这次说的话。为 null 表示重新生成 ——
     *   那时这条线最后一句本来就是用户的提问，再追加一次就重复了。
     */
    /**
     * 拼这次要发出去的全部消息，并裁到上下文装得下。
     *
     * @param newInput 用户这次说的话。为 null 表示重新生成 ——
     *   那时这条线最后一句本来就是用户的提问，再追加一次就重复了。
     *
     * 分成三段来拼，是因为三段能不能被裁掉完全不同：
     * 开头的设定和队尾的要求一条都不能少，只有中间的来回对话可以丢。
     */
    private fun buildOutgoingMessages(
        history: List<TranscriptEntry>,
        newInput: String?,
    ): List<ChatMessage> {
        val leading = mutableListOf<ChatMessage>()
        val tail = mutableListOf<ChatMessage>()

        // 导入了卡就按卡拼开头的系统消息：角色设定、命中的世界观条目、
        // 以及当前状态栏的数值（只让模型看，不让它在正文里写数值）
        val currentCard = card
        if (currentCard != null) {
            // 只拿最近几条聊天去匹配触发词。早就过去的话题不该一直把设定顶在上下文里
            val recent = (history.takeLast(RECENT_SCAN_MESSAGES).map { it.text } + listOfNotNull(newInput))
                .joinToString("\n")
            val prompt = CardPromptAssembler.buildNarrativePrompt(
                card = currentCard,
                recentText = recent,
                stateValues = stateStore.values.value,
            )
            leading += prompt.systemMessages
            prompt.trailingSystemMessage?.let { tail += it }
        }

        val middle = history.mapNotNull { entry ->
            // 空内容的历史条目不能发出去，发送前自检会直接拦下整个请求
            entry.text.takeIf { it.isNotBlank() }?.let {
                ChatMessage(
                    role = if (entry.fromUser) ChatRole.User else ChatRole.Assistant,
                    content = it,
                )
            }
        }

        // 这一轮用户说的话要排在队尾要求之前 —— 队尾要求的用意就是离模型最近
        newInput?.let { tail.add(0, ChatMessage(ChatRole.User, it)) }

        val fit = TokenBudget.fit(leading = leading, history = middle, tail = tail)
        _state.update {
            it.copy(
                droppedHistoryCount = fit.droppedCount,
                estimatedPromptTokens = fit.estimatedTokens,
            )
        }
        return fit.messages
    }

    // ------------------------------------------------------------------
    // 存档
    // ------------------------------------------------------------------

    /**
     * 把一条消息写进库，并返回界面要用的那一条。
     *
     * 界面上每条的标识直接用库里的编号，不另起一套。两套编号会在重启后对不上 ——
     * 列表的滚动位置、结算记录挂在哪条，全都会错位。
     */
    private suspend fun persistAndBuildEntry(
        fromUser: Boolean,
        text: String,
        turn: Int?,
    ): TranscriptEntry {
        val session = ensureSession()
        val now = currentTimeMillis()
        val id = chatStore.appendMessage(
            sessionId = session,
            parentId = headMessageId,
            fromUser = fromUser,
            text = text,
            turn = turn,
            createdAt = now,
        )
        // 新消息挂在上一条下面，然后它自己成为新的「聊到哪了」。
        // 这一步就是分支的全部机制：以后要从更早一条重新长，
        // 只要把 headMessageId 指回那一条，新消息自然挂到它下面去
        headMessageId = id
        chatStore.updateHead(session, id, now)
        return TranscriptEntry(id = id, fromUser = fromUser, text = text, turn = turn)
    }

    /**
     * 重建消息树，并把版本标记刷到界面上。
     *
     * 只重建树、不动状态栏：这里的调用时机是「刚追加了一条消息」，
     * 数值该怎么变由结算决定，不该在这里顺手算一遍。
     */
    private suspend fun refreshMessageTree() {
        val session = sessionId ?: return
        messageTree = MessageTree(chatStore.messagesOf(session))
        _state.update { current ->
            current.copy(
                transcript = current.transcript.map { entry ->
                    val versions = messageTree.alternativesOf(entry.id)
                    entry.copy(
                        versionIndex = versions.indexOfFirst { it.id == entry.id } + 1,
                        versionCount = versions.size,
                    )
                },
            )
        }
    }

    /** 取当前这一局；还没有就建一个。 */
    private suspend fun ensureSession(): Long {
        sessionId?.let { return it }
        val created = chatStore.createSession(
            title = card?.name ?: UNTITLED_SESSION,
            cardJson = card?.rawJsonText(),
            createdAt = currentTimeMillis(),
        )
        sessionId = created
        return created
    }

    /**
     * 把这一轮的状态变化写进库。
     *
     * 顺序号从库里要，不在内存里累加：内存里的计数在重启后会从头开始，
     * 新记录就排到了旧记录前面，回放结果全乱。
     */
    private fun persistStateEvents(receipt: SettlementReceipt) {
        if (receipt.changes.isEmpty()) return
        val session = sessionId ?: return
        val messageId = messageIdByTurn[receipt.turn] ?: return
        val revision = stateStore.revision
        viewModelScope.launch {
            val start = chatStore.nextEventSequence(session)
            chatStore.appendStateEvents(
                sessionId = session,
                events = StateEventLog.eventsOf(receipt, messageId, start, revision),
            )
        }
    }

    /**
     * 把已经出结果的回执写进库。
     *
     * 只存已经跑完的：排队中、正在算这些中间状态存下去没有意义，
     * 重启之后它们也不可能接着跑。
     *
     * 记下已经存过哪一份（轮次 + 第几次尝试），避免每次状态流动都重写一遍库 ——
     * 一轮结算期间这个流会动好几次，不挡住就是好几次无谓的磁盘写入。
     */
    private fun persistSettledReceipts(records: List<SettlementRecord>) {
        val session = sessionId ?: return
        records.forEach { record ->
            if (record.phase != SettlementPhase.Settled) return@forEach
            val messageId = messageIdByTurn[record.turn] ?: return@forEach
            if (persistedReceipts[record.turn] == record.attempt) return@forEach
            persistedReceipts[record.turn] = record.attempt

            val receipt = record.outcome.receipt
            viewModelScope.launch {
                chatStore.saveReceipt(
                    sessionId = session,
                    receipt = StoredReceipt(
                        messageId = messageId,
                        turn = record.turn,
                        status = receipt.status.name,
                        summary = receipt.summary,
                        changesJson = ReceiptJson.encodeChanges(receipt.changes),
                        failuresJson = ReceiptJson.encodeFailures(receipt.failures),
                        usageTotalTokens = record.outcome.usage.totalTokens,
                        usageCost = record.outcome.usage.cost,
                        attempt = record.attempt,
                        createdAt = currentTimeMillis(),
                    ),
                )
            }
        }
    }

    /** 把存档里的回执读回来，按轮次索引好给界面用。 */
    private suspend fun loadRestoredReceipts(sessionId: Long): Map<Int, SettlementView> =
        chatStore.receiptsOf(sessionId).associate { stored ->
            stored.turn to SettlementView(
                turn = stored.turn,
                // 从存档读回来的没有中间状态，这里留空正是为了让界面知道这一点
                phase = null,
                receipt = SettlementReceipt(
                    turn = stored.turn,
                    status = statusOf(stored.status),
                    summary = stored.summary,
                    changes = ReceiptJson.decodeChanges(stored.changesJson),
                    failures = ReceiptJson.decodeFailures(stored.failuresJson),
                ),
                usage = TokenUsage(
                    totalTokens = stored.usageTotalTokens,
                    cost = stored.usageCost,
                ),
                attempt = stored.attempt,
            )
        }

    /** 状态名对不上就当失败处理，不让一条坏记录毁掉整局存档。 */
    private fun statusOf(name: String): SettlementStatus =
        SettlementStatus.entries.firstOrNull { it.name == name } ?: SettlementStatus.Error

    /** 启动时恢复最近玩过的那一局。 */
    private fun restoreLatestSession() {
        viewModelScope.launch {
            chatStore.loadLatestSession()?.let { applySession(it) }
            refreshSessions()
        }
    }

    /** 切换到另一局存档。 */
    fun openSession(targetId: Long) {
        if (targetId == sessionId) return
        // 正在生成才需要停。没在生成时调用停止没有意义，
        // 还会顺带把界面标成「已停止显示」，让人以为出了什么事
        if (_state.value.turn.isActive) stop()
        viewModelScope.launch {
            // 等已经生成的部分写完库再切。那些内容是计过费的，不该因为切存档就丢掉
            commitJob?.join()
            chatStore.loadSession(targetId)?.let { applySession(it) }
            refreshSessions()
        }
    }

    /** 删掉一局存档。删的是当前这局时，自动切到剩下最近的那局。 */
    fun deleteSession(targetId: Long) {
        viewModelScope.launch {
            chatStore.deleteSession(targetId)
            if (targetId == sessionId) {
                clearCurrentSession()
                chatStore.loadLatestSession()?.let { applySession(it) }
            }
            refreshSessions()
        }
    }

    /** 重新读一遍存档列表。 */
    private suspend fun refreshSessions() {
        val sessions = chatStore.listSessions()
        _state.update { it.copy(sessions = sessions, currentSessionId = sessionId) }
    }

    /**
     * 把一局存档装载进来。
     *
     * 装载顺序是有讲究的：先把卡读回来（它决定状态栏有哪些项），
     * 再按消息树找出当前这条线，最后只回放这条线上的变化记录。
     * 顺序错了会出现「状态栏的项还是上一局的，而数值已经是这一局的」。
     */
    private suspend fun applySession(session: SessionRecord) {
        clearCurrentSession()
        sessionId = session.id

        // 卡原文存着，字段清单和开局值当场重新推导。
        // 推导是纯算、结果固定，存一份反而多一个会和卡不一致的地方
        val restoredCard = session.cardJson
            ?.let { CharacterCardParser.parse(it) as? CardParseResult.Success }
            ?.card
        val init = restoredCard?.let { CardStateInitializer.extract(it) }
        val initialValues = if (restoredCard == null) {
            PlaceholderStateProfile.initialValues
        } else {
            init?.values ?: emptyMap()
        }
        card = restoredCard
        schema = if (restoredCard == null) {
            PlaceholderStateProfile.schema
        } else {
            init?.schema ?: StateSchema(fields = emptyMap())
        }
        cardFieldNotes = restoredCard?.let { CardPromptAssembler.settlementRules(it) }

        headMessageId = session.headMessageId
        sessionInitialValues = initialValues
        refreshFromHead()

        _state.update { current ->
            current.copy(
                cardSummary = restoredCard?.let { summaryOf(it, schema.fields.size, init?.notes?.size ?: 0) },
                currentSessionId = session.id,
                issue = null,
            )
        }
    }

    /**
     * 按「当前聊到哪一条」把界面和状态栏整个刷一遍。
     *
     * 所有会改动消息结构的操作（发消息、重新生成、切版本、编辑、删除）
     * 最后都走这一个口子。走同一条路的好处是：界面、状态栏、轮次编号
     * 三者永远是同一时刻算出来的，不会出现「对话换了而状态栏没换」。
     */
    private suspend fun refreshFromHead() {
        val session = sessionId ?: return

        val allMessages = chatStore.messagesOf(session)
        messageTree = MessageTree(allMessages)
        // 从「聊到哪一条」往上回溯就是当前这条线。
        // 别的分支上的消息也都在库里，只是这次不显示
        val line = messageTree.lineageOf(headMessageId)
        val lineIds = line.map { it.id }.toSet()

        val events = chatStore.stateEventsOf(session)
        val values = StateEventLog.replayOnLine(sessionInitialValues, events, lineIds)
        val revision = StateEventLog.revisionOf(events, lineIds)
        stateStore.restore(values, revision)

        // 🔴 轮次编号要在**整局内唯一**，所以从这一局的全部消息里取最大值，
        // 不能只看当前这条线。
        //
        // 只看当前线会出事：重新生成时先退回上一条，那条线上就没有回复了，
        // 于是编号归零，新回复又拿到 1 号 —— 两个版本的编号撞车。
        // 结算回执是按编号挂的，撞车之后切回旧版本会看到新版本的回执。
        lastTurn = allMessages.mapNotNull { it.turn }.maxOrNull() ?: 0

        // 对照表收全局的，不只是当前线上的。切版本时要靠它找回那条消息
        messageIdByTurn.clear()
        allMessages.forEach { message -> message.turn?.let { messageIdByTurn[it] = message.id } }

        val restored = loadRestoredReceipts(session)

        _state.update { current ->
            current.copy(
                restoredReceipts = restored,
                transcript = line.map { message ->
                    // 同一个上一条下面挂着的那些，就是这句话的几个版本
                    val versions = messageTree.alternativesOf(message.id)
                    TranscriptEntry(
                        id = message.id,
                        fromUser = message.fromUser,
                        text = message.text,
                        turn = message.turn,
                        versionIndex = versions.indexOfFirst { it.id == message.id } + 1,
                        versionCount = versions.size,
                    )
                },
                display = StateDisplayInference.infer(values, schema),
            )
        }
    }

    /**
     * 清掉当前这一局在内存里留下的东西。
     *
     * 换局前必须清干净，尤其是结算记录和「轮次 → 消息」的对照表：
     * 两局的轮次编号都是从 1 开始的，不清的话下一局的第 1 轮会对上
     * 上一局第 1 轮那条消息，回执就挂错了位置。
     */
    private fun clearCurrentSession() {
        pendingReply = false
        sessionId = null
        headMessageId = null
        lastTurn = 0
        messageIdByTurn.clear()
        persistedReceipts.clear()
        messageTree = MessageTree(emptyList())
        settlementQueue.clear()
        _state.update {
            it.copy(
                transcript = emptyList(),
                settlements = emptyList(),
                restoredReceipts = emptyMap(),
                cardSummary = null,
                turn = ChatTurnState(),
            )
        }
    }

    // ------------------------------------------------------------------
    // 备份
    // ------------------------------------------------------------------

    /**
     * 把全部存档打包成一段文本，交给界面写进文件。
     *
     * 文本在这里生成、由界面负责落盘：挑文件位置是 Android 的事，
     * 打包是引擎的事，两者分开，打包逻辑就能脱离系统完整测试。
     */
    fun exportBackup(onReady: (String) -> Unit) {
        viewModelScope.launch {
            val document = BackupService.export(chatStore, currentTimeMillis())
            val text = BackupService.encode(document)
            _state.update {
                it.copy(
                    backupNotice = BackupNotice.Exported(
                        sessionCount = document.sessions.size,
                        byteCount = text.length,
                    ),
                )
            }
            onReady(text)
        }
    }

    /** 读一份备份文件，把里面的存档加进来。 */
    fun importBackup(text: String) {
        viewModelScope.launch {
            when (val result = BackupService.decode(text)) {
                is BackupReadResult.Failure ->
                    _state.update { it.copy(backupNotice = BackupNotice.Failed(result.code)) }

                is BackupReadResult.Success -> {
                    val summary = BackupService.import(chatStore, result.document)
                    refreshSessions()
                    _state.update { it.copy(backupNotice = BackupNotice.Imported(summary)) }
                }
            }
        }
    }

    /** 读文件本身就失败了（权限、文件被移走）。和「内容不是备份」分开报。 */
    fun reportBackupUnreadable() =
        _state.update { it.copy(backupNotice = BackupNotice.Failed(null)) }

    fun dismissBackupNotice() = _state.update { it.copy(backupNotice = null) }

    // ------------------------------------------------------------------
    // 角色卡
    // ------------------------------------------------------------------

    /**
     * 导入一张角色卡。
     *
     * 导入会**清空当前对话**并按卡重置状态栏：卡换了，之前的剧情和数值
     * 都不再对应得上，留着只会让后面的结算基于一堆错的前提去算。
     *
     * 开场消息直接写进对话记录，不调模型 —— 它是卡里现成的文字，
     * 调一次模型既花钱又会把作者写好的开场改掉。
     */
    fun importCard(text: String) {
        when (val result = CharacterCardParser.parse(text)) {
            is CardParseResult.Failure ->
                _state.update { it.copy(cardImportFailure = result.code) }

            // 导入要写库，所以放到协程里做
            is CardParseResult.Success -> viewModelScope.launch { applyCard(result.card) }
        }
    }

    fun dismissCardImportFailure() = _state.update { it.copy(cardImportFailure = null) }

    private suspend fun applyCard(imported: CharacterCard) {
        // 先清干净再装新的：轮次编号、结算记录、对照表都要归零，
        // 否则新局第 1 轮的回执会挂到旧局第 1 轮那条消息上
        clearCurrentSession()

        card = imported
        val init: CardStateInit? = CardStateInitializer.extract(imported)

        // 没有状态玩法的纯聊天卡：状态栏清空、字段清单也清空。
        // 字段清单为空时结算整个不启用，这是故意的 —— 没有状态栏可算
        schema = init?.schema ?: com.mirage.engine.state.StateSchema(fields = emptyMap())
        stateStore.reset(init?.values ?: emptyMap())
        cardFieldNotes = CardPromptAssembler.settlementRules(imported)
        lastTurn = 0

        // 导卡 = 开新的一局。旧的那一局连着它的消息和变化记录都留在库里，
        // 不删 —— 用户大概还想回去接着玩，而「导入会清空当前对话」
        // 说的是清空界面上这一局，不是把以前玩过的都毁掉
        sessionId = chatStore.createSession(
            title = imported.name,
            cardJson = imported.rawJsonText(),
            createdAt = currentTimeMillis(),
        )
        headMessageId = null
        messageIdByTurn.clear()

        val narrativeCount = CardPromptAssembler.classify(imported.worldBook?.entries ?: emptyList())
            .count { it.purpose == com.mirage.engine.prompt.EntryPurpose.Narrative && it.entry.enabled }

        // 开场消息末尾常常跟着一整段初始数值和一个状态面板占位标签。
        // 不清掉的话：用户会看见一坨 JSON 贴在开场白后面；更麻烦的是它留在聊天记录里，
        // 模型每轮都看到「上一条就长这样」，于是开始照着写，正文里冒出状态块
        val opening = CardMacros.expand(
            text = CardTextSanitizer.stripStatusBlocks(imported.firstMessage),
            userName = CardPromptAssembler.DEFAULT_USER_NAME,
            charName = imported.name,
        )
        // 开场消息也要落库。不落的话重开 App 看到的是一局没有开头的对话，
        // 而且模型拿到的历史里也缺了这一段
        // （落完之后会刷新存档列表，新的一局要立刻出现在列表里）
        val openingEntry = if (opening.isEmpty()) {
            null
        } else {
            persistAndBuildEntry(fromUser = false, text = opening, turn = null)
        }

        _state.update { current ->
            current.copy(
                transcript = listOfNotNull(openingEntry),
                settlements = emptyList(),
                display = StateDisplayInference.infer(init?.values ?: emptyMap(), schema),
                cardSummary = CardSummary(
                    name = imported.name,
                    narrativeEntryCount = narrativeCount,
                    stateFieldCount = schema.fields.size,
                    noteCount = init?.notes?.size ?: 0,
                    hasSettlementRules = CardPromptAssembler.settlementRules(imported) != null,
                ),
                cardImportFailure = null,
                issue = null,
                currentSessionId = sessionId,
            )
        }
        refreshSessions()
    }

    /**
     * 构造器。
     *
     * 手写而不是用生成式写法，是为了避免依赖较新的辅助接口 ——
     * 这一层换成依赖注入框架后会整体删掉，不值得为它引入不确定性。
     */
    /** 把卡的概况整理成界面要显示的那几个数字。 */
    private fun summaryOf(source: CharacterCard, stateFieldCount: Int, noteCount: Int) = CardSummary(
        name = source.name,
        narrativeEntryCount = CardPromptAssembler.classify(source.worldBook?.entries ?: emptyList())
            .count { it.purpose == EntryPurpose.Narrative && it.entry.enabled },
        stateFieldCount = stateFieldCount,
        noteCount = noteCount,
        hasSettlementRules = CardPromptAssembler.settlementRules(source) != null,
    )

    private companion object {
        /** 匹配触发词时往回看几条聊天。 */
        const val RECENT_SCAN_MESSAGES = 4

        /** 没导卡时这一局的名字。 */
        const val UNTITLED_SESSION = "未命名"
    }

    class Factory(private val container: MirageContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(
            chatClient = container.chatClient,
            credentialStore = container.credentialStore,
            selectionStore = container.selectionStore,
            settlementCoordinator = container.settlementCoordinator,
            chatStore = container.chatStore,
        ) as T
    }
}
