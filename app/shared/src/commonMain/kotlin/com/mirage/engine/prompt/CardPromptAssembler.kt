package com.mirage.engine.prompt

import com.mirage.engine.card.CharacterCard
import com.mirage.engine.card.WorldBookEntry
import com.mirage.engine.llm.ChatMessage
import com.mirage.engine.llm.ChatRole
import com.mirage.engine.state.StateValue
import com.mirage.engine.state.StateValueJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * 设定条目的去向。
 *
 * 同一本设定里的条目，用途完全不一样，必须分开送：
 * 讲故事用的送给正文那次调用，算数值用的送给结算那次调用，
 * 写初始数值的哪次都不送（它已经变成状态栏本身了）。
 *
 * 分错的后果很具体：把算数值的规则塞进正文，模型会一边讲故事一边报数字；
 * 把初始数值塞进正文，等于每轮都告诉模型「开局时是 60」，它会照着往回写。
 */
enum class EntryPurpose {
    /** 讲故事用的世界观设定。 */
    Narrative,

    /** 算数值用的规则。只送给结算那次调用。 */
    SettlementRules,

    /** 状态栏的初始数值。两次调用都不送。 */
    StateInit,
}

/** 分流之后的设定条目。 */
data class ClassifiedEntry(
    val entry: WorldBookEntry,
    val purpose: EntryPurpose,
)

/**
 * 装配好的一次正文请求所需的内容。
 *
 * @property systemMessages 开头那几条系统消息，按顺序。
 * @property trailingSystemMessage 放在聊天记录**最后**的那条。有的卡会写这种，
 *   目的是让要求离模型最近、不被长篇历史冲淡。没有就是 null。
 * @property usedEntryComments 这次带上了哪些设定条目（用作者的备注标识）。
 *   留着它是为了能在界面上说清「这一轮带了什么设定进去」，
 *   否则命中与否完全是黑箱，调卡的人没法判断设定为什么没生效。
 */
data class NarrativePrompt(
    val systemMessages: List<ChatMessage>,
    val trailingSystemMessage: ChatMessage? = null,
    val usedEntryComments: List<String> = emptyList(),
)

/**
 * ============================================================================
 * CardPromptAssembler —— 把卡拼成一次请求
 * ============================================================================
 *
 * 做三件事：
 * 1. 把设定条目按用途分流（讲故事的 / 算数值的 / 写初始数值的）；
 * 2. 挑出这一轮该带上的设定（常驻的全带，带触发词的看最近聊天里有没有提到）；
 * 3. 拼成系统消息，并把当前状态栏的数值一起带上。
 *
 * ## 为什么正文也要带状态栏的数值
 *
 * 不带的话模型不知道现在什么情况，会写出和状态栏矛盾的内容
 * （血只剩一点了却写成生龙活虎）。带上之后，状态栏成了一份不会被长篇历史
 * 挤掉的事实清单 —— 它顺带还起到了「长期记忆」的作用。
 *
 * 但只给它**看**，不让它**写**：正文里不允许出现数值变化，数值一律走结算那次调用。
 */
object CardPromptAssembler {

    /**
     * 没有专门设定玩家称呼时用的默认值。
     *
     * 用第二人称而不是编一个名字：卡里那些「{{user}}推开门」会变成「你推开门」，
     * 这是中文角色扮演里最自然的读法。
     */
    const val DEFAULT_USER_NAME: String = "你"

    /** 作者给初始数值条目打的记号。 */
    private const val MARKER_STATE_INIT = "initvar"

    /** 作者给算数值规则条目打的记号。 */
    private val MARKERS_SETTLEMENT = listOf("mvu_update", "mvu-update", "状态更新")

    /**
     * 给设定条目分流。
     *
     * 只认作者打的记号，不去读内容猜用途。读内容猜的话，一条讲战斗规则的
     * 世界观设定很容易被误判成算数值的规则，然后整条设定就从正文里消失了，
     * 而这种「设定莫名其妙不起作用」极难排查。
     */
    fun classify(entries: List<WorldBookEntry>): List<ClassifiedEntry> = entries.map { entry ->
        val comment = entry.comment.lowercase()
        val purpose = when {
            comment.contains(MARKER_STATE_INIT) -> EntryPurpose.StateInit
            MARKERS_SETTLEMENT.any { comment.contains(it) } -> EntryPurpose.SettlementRules
            else -> EntryPurpose.Narrative
        }
        ClassifiedEntry(entry, purpose)
    }

    /**
     * 卡里自带的算数值规则。
     *
     * 有就用卡作者写的（他最清楚这张卡的数值该怎么算），没有就返回 null，
     * 由调用方退回到我们自己那套通用说法。
     */
    fun settlementRules(card: CharacterCard): String? = classify(card.worldBook?.entries ?: emptyList())
        .filter { it.purpose == EntryPurpose.SettlementRules && it.entry.enabled }
        .joinToString("\n\n") { it.entry.content.trim() }
        .takeIf { it.isNotBlank() }

    /**
     * 拼出正文那次调用要用的系统消息。
     *
     * @param recentText 最近几条聊天的文字，用来判断带触发词的设定有没有被提到。
     *   只看最近几条而不是全部历史：早就过去的话题不该一直把设定顶在上下文里。
     * @param stateValues 当前状态栏的数值。空表示这张卡没有状态玩法。
     */
    fun buildNarrativePrompt(
        card: CharacterCard,
        recentText: String = "",
        stateValues: Map<String, StateValue> = emptyMap(),
        userName: String = DEFAULT_USER_NAME,
    ): NarrativePrompt {
        val sections = mutableListOf<String>()
        val used = mutableListOf<String>()

        card.systemPrompt.trim().takeIf { it.isNotEmpty() }?.let { sections += it }

        // 角色本身的设定。拼成带小标题的几段，而不是糊成一大段 ——
        // 分段之后模型更容易分清「这是性格」和「这是当前处境」
        card.description.trim().takeIf { it.isNotEmpty() }?.let { sections += it }
        card.personality.trim().takeIf { it.isNotEmpty() }?.let { sections += it }
        card.scenario.trim().takeIf { it.isNotEmpty() }?.let { sections += it }
        card.messageExample.trim().takeIf { it.isNotEmpty() }?.let { sections += it }

        val narrativeEntries = classify(card.worldBook?.entries ?: emptyList())
            .filter { it.purpose == EntryPurpose.Narrative && it.entry.enabled }
            .map { it.entry }

        // 常驻的全带；带触发词的看最近聊天里提到没有。
        // 排序用作者给的次序，让他能控制几条设定之间的先后关系
        val selected = narrativeEntries
            .filter { it.constant || matches(it, recentText) }
            .sortedBy { it.insertionOrder }

        selected.forEach { entry ->
            entry.content.trim().takeIf { it.isNotEmpty() }?.let {
                sections += it
                used += entry.comment.ifBlank { entry.keys.firstOrNull().orEmpty() }
            }
        }

        if (stateValues.isNotEmpty()) {
            sections += stateSection(stateValues)
        }

        // 合成**一条**系统消息，而不是每段各发一条。
        //
        // 理由有两条：一是各家服务商对「多条系统消息」的处理并不一致，
        // 有的只认第一条，剩下的设定就等于白送了；二是卡本来就是按
        // 「一整段系统提示词」设计的，拆开之后段与段的先后关系会变弱。
        // 占位统一在这里换成真名字。放在最后一步，是为了让上面各段的拼装
        // 不用各自操心这件事，也不会漏掉某一段
        val merged = CardMacros.expand(
            text = sections.joinToString("\n\n"),
            userName = userName,
            charName = card.name,
        )
        val systemMessages = if (sections.isEmpty()) {
            emptyList()
        } else {
            listOf(ChatMessage(ChatRole.System, merged))
        }
        val trailing = card.postHistoryInstructions.trim()
            .takeIf { it.isNotEmpty() }
            ?.let { ChatMessage(ChatRole.System, CardMacros.expand(it, userName, card.name)) }

        return NarrativePrompt(
            systemMessages = systemMessages,
            trailingSystemMessage = trailing,
            usedEntryComments = used,
        )
    }

    /**
     * 带触发词的设定，这一轮要不要带上。
     *
     * 只做「最近的话里出现过这个词」这种直白判断，不做同义词、不做分词。
     * 这是卡作者写触发词时的预期行为，做得更聪明反而会命中他没想带的条目。
     */
    private fun matches(entry: WorldBookEntry, recentText: String): Boolean {
        if (recentText.isBlank()) return false
        return entry.keys.any { key ->
            key.isNotBlank() && recentText.contains(key, ignoreCase = true)
        }
    }

    /**
     * 把状态栏的数值拼成一段。
     *
     * 用结构化写法而不是「甲是 60，乙是 5」这种句子：句子里的数字容易被模型
     * 当成可以顺手改写的内容，而结构化的一份清单更像「这是事实，照着写」。
     */
    private fun stateSection(values: Map<String, StateValue>): String {
        val obj: JsonObject = buildJsonObject {
            values.entries.sortedBy { it.key }.forEach { (path, value) ->
                StateValueJson.encode(value)?.let { put(path, it) }
            }
        }
        return STATE_HEADER + "\n" + Json.encodeToString(JsonObject.serializer(), obj)
    }

    /**
     * 状态清单前面的那句说明。
     *
     * 这是给模型的指令，不是给用户看的界面文案，所以写在这里而不是文案资源里。
     * 两者的区别在于：这句话改了会影响模型行为（要跟着回归测试），
     * 而界面文案改了只影响人怎么读。
     */
    private const val STATE_HEADER =
        "下面是当前的状态数值，仅供你了解处境、保持前后一致。" +
            "不要在正文里写出任何数值、增减或结算结果，数值由系统单独处理。"
}
