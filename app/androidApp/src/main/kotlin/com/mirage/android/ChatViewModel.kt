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
import com.mirage.engine.card.CharacterCard
import com.mirage.engine.card.CharacterCardParser
import com.mirage.engine.llm.ProviderCatalog
import com.mirage.engine.prompt.CardMacros
import com.mirage.engine.prompt.CardPromptAssembler
import com.mirage.engine.state.StateDisplayInference
import com.mirage.engine.state.StateDisplayProfile
import com.mirage.engine.llm.ProviderProfile
import com.mirage.engine.settlement.SettlementCoordinator
import com.mirage.engine.settlement.SettlementOutcome
import com.mirage.engine.settlement.SettlementQueue
import com.mirage.engine.settlement.SettlementRecord
import com.mirage.engine.settlement.SettlementTask
import com.mirage.engine.state.SettlementReceipt
import com.mirage.engine.state.SettlementStatus
import com.mirage.engine.state.StateStore
import com.mirage.engine.state.StateValue
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
    //一次请求的完整状态
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
) {
    /** 取某一轮的结算记录。回复气泡下面挂哪张卡片由它决定。 */
    fun settlementOf(turn: Int): SettlementRecord? = settlements.firstOrNull { it.turn == turn }

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
class ChatViewModel(
    private val chatClient: ChatCompletionClient,
    private val credentialStore: CredentialStore,
    private val selectionStore: SelectionStore,
    private val settlementCoordinator: SettlementCoordinator,
) : ViewModel() {

    private val _state: MutableStateFlow<ChatUiState>
    val state: StateFlow<ChatUiState>

    /** 进行中的请求。保留它才能在使用者要求停止时真正断开。 */
    private var streamJob: Job? = null

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
        commit = { receipt -> stateStore.commit(receipt.changes) },
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
            }
        }
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
    // 对话
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

            val userEntry = newEntry(fromUser = true, text = text)
            _state.update {
                it.copy(
                    transcript = it.transcript + userEntry,
                    turn = ChatTurnState().started(),
                    issue = null,
                )
            }

            chatClient.stream(
                profile = profile,
                credential = secret,
                request = ChatCompletionRequest(modelId = snapshot.modelId, messages = outgoing),
            ).collect { event ->
                _state.update { it.copy(turn = it.turn.reduce(event)) }
            }

            commitReply()
        }
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
        commitReply()
    }

    fun dismissIssue() = _state.update { it.copy(issue = null) }

    /**
     * 把本轮已产出的回复定稿进对话记录。
     *
     * 中止和失败的情况同样要定稿 —— 那些内容已经生成、也已经计费，
     * 丢掉它对使用者只有损失。
     */
    private fun commitReply() {
        // 模型仍有可能自己吐出状态块（它见过太多这种例子）。
        // 这里统一清一次：留着它既会在界面上显示成乱码一样的东西，
        // 也会进到下一轮的历史里，把这个习惯一直传下去
        val reply = CardTextSanitizer.stripStatusBlocks(_state.value.turn.text)
        if (reply.isBlank()) return
        val turn = ++lastTurn
        val replyEntry = newEntry(fromUser = false, text = reply, turn = turn)
        _state.update { it.copy(transcript = it.transcript + replyEntry) }
        submitSettlement(turn, reply)
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
        val record = settlementQueue.recordOf(turn) ?: return
        settlementQueue.submit(buildTask(turn, record.task.narrative))
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

    private fun buildOutgoingMessages(
        history: List<TranscriptEntry>,
        newInput: String,
    ): List<ChatMessage> = buildList {
        // 导入了卡就按卡拼开头的系统消息：角色设定、命中的世界观条目、
        // 以及当前状态栏的数值（只让模型看，不让它在正文里写数值）
        val currentCard = card
        var trailing: ChatMessage? = null
        if (currentCard != null) {
            // 只拿最近几条聊天去匹配触发词。早就过去的话题不该一直把设定顶在上下文里
            val recent = (history.takeLast(RECENT_SCAN_MESSAGES).map { it.text } + newInput)
                .joinToString("\n")
            val prompt = CardPromptAssembler.buildNarrativePrompt(
                card = currentCard,
                recentText = recent,
                stateValues = stateStore.values.value,
            )
            addAll(prompt.systemMessages)
            trailing = prompt.trailingSystemMessage
        }

        history.forEach { entry ->
            // 空内容的历史条目不能发出去，发送前自检会直接拦下整个请求
            if (entry.text.isNotBlank()) {
                add(
                    ChatMessage(
                        role = if (entry.fromUser) ChatRole.User else ChatRole.Assistant,
                        content = entry.text,
                    ),
                )
            }
        }
        add(ChatMessage(ChatRole.User, newInput))

        // 卡作者指定要放在历史最后的那条要求，必须排在用户这句之后 ——
        // 它的用意就是离模型最近，提前放就失去意义了
        trailing?.let { add(it) }
    }

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

            is CardParseResult.Success -> applyCard(result.card)
        }
    }

    fun dismissCardImportFailure() = _state.update { it.copy(cardImportFailure = null) }

    private fun applyCard(imported: CharacterCard) {
        card = imported
        val init: CardStateInit? = CardStateInitializer.extract(imported)

        // 没有状态玩法的纯聊天卡：状态栏清空、字段清单也清空。
        // 字段清单为空时结算整个不启用，这是故意的 —— 没有状态栏可算
        schema = init?.schema ?: com.mirage.engine.state.StateSchema(fields = emptyMap())
        stateStore.reset(init?.values ?: emptyMap())
        cardFieldNotes = CardPromptAssembler.settlementRules(imported)
        lastTurn = 0

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
        _state.update { current ->
            current.copy(
                transcript = if (opening.isEmpty()) emptyList() else listOf(newEntry(false, opening)),
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
            )
        }
    }

    /**
     * 构造器。
     *
     * 手写而不是用生成式写法，是为了避免依赖较新的辅助接口 ——
     * 这一层换成依赖注入框架后会整体删掉，不值得为它引入不确定性。
     */
    private companion object {
        /** 匹配触发词时往回看几条聊天。 */
        const val RECENT_SCAN_MESSAGES = 4
    }

    class Factory(private val container: MirageContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(
            chatClient = container.chatClient,
            credentialStore = container.credentialStore,
            selectionStore = container.selectionStore,
            settlementCoordinator = container.settlementCoordinator,
        ) as T
    }
}
