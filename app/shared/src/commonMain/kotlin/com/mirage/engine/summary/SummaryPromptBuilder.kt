package com.mirage.engine.summary

import com.mirage.engine.llm.ChatCompletionRequest
import com.mirage.engine.llm.ChatMessage
import com.mirage.engine.llm.ChatRole

/**
 * 构造「把这几轮剧情压成一段摘要」这次调用的输入。
 *
 * ## 这次调用在整条链路里的位置
 *
 * 一轮对话本来有两次调用：写正文、算状态栏。压缩是**第三次**，而且不是每轮都发 ——
 * 只有攒够内容、或者用户点了按钮才发一次。它产出的那段话会被存成一卷，
 * 之后每一轮都放进提示词的开头，顶替掉被它盖住的那些原文。
 *
 * ## 提示词同样写得很短
 *
 * 和结算那次一样的理由：实测里约束堆得越多，模型越容易顾此失彼。
 * 这里只说三件事 —— 用第三人称、按时间顺序、把后面还要用到的信息留住。
 *
 * ## 为什么要点名「把这些留住」
 *
 * 摘要天然会丢细节，问题是丢哪些。不说的话模型倾向于留下「情绪和氛围」、
 * 丢掉「谁叫什么、东西在谁手上、两个人现在是什么关系」——
 * 而后面接剧情真正要用的恰恰是后者。
 */
object SummaryPromptBuilder {

    /** 压一卷新摘要时的指令。 */
    const val VOLUME_INSTRUCTION: String =
        """把下面这段剧情压缩成一段摘要，用第三人称、按时间顺序写。

要留住的信息：出场人物的名字和身份、地点、关键物品在谁手上、人物关系的变化、做过的承诺和未了的事。
可以舍弃的：对话原话、环境描写、心理描写的细节。

只输出摘要本身，不要标题，不要点评，不要写成对话。"""

    /**
     * 把多卷合并成一卷「前情总览」时的指令。
     *
     * 这是唯一一次对已经压过的内容再压一次，所以要特别交代
     * 「不要进一步概括」—— 否则每合并一次，早期剧情就再糊一层。
     */
    const val MERGE_INSTRUCTION: String =
        """把下面几段剧情摘要合成一段连贯的前情总览，保持时间顺序。

把重复的内容合并，但**人名、地点、物品归属、人物关系、未了的事一个都不要省略**。
不要进一步概括成「他们经历了很多事」这样的空话。

只输出总览本身，不要标题，不要点评。"""

    /** 摘要段落在提示词里的开头那句话。 */
    private const val VOLUMES_HEADER: String =
        "下面是之前发生过的事情的摘要，按时间顺序。这些是已经发生的剧情，不是待办事项。"

    /**
     * 压一卷新摘要的请求。
     *
     * @param transcript 要压的那几轮原文，已经拼成一段文字。
     * @param maxOutputTokens 摘要的长度上限。
     *
     * 这里敢设上限，是因为**同时关掉了思考** —— 上限是思考和正文共享的，
     * 不关思考就可能出现「思考把额度吃光、摘要一个字都没有」。
     *
     * 🔴 必须走流式。摘要是后台产物，没人盯着它一个字一个字出来，
     * 看起来用非流式更合适 —— 但收响应那一侧只有流式通道这一条路，
     * 它是按 `data:` 行解析的。这里一关，服务端就返回普通 JSON，
     * 解析器一行都认不出来，于是「拿到 0 个字」被当成失败。
     * 两边必须说的是同一种协议。
     */
    fun buildVolumeRequest(
        modelId: String,
        transcript: String,
        instruction: String = VOLUME_INSTRUCTION,
        maxOutputTokens: Int? = DEFAULT_SUMMARY_MAX_TOKENS,
    ): ChatCompletionRequest = ChatCompletionRequest(
        modelId = modelId,
        messages = listOf(
            ChatMessage(ChatRole.System, instruction),
            ChatMessage(ChatRole.User, transcript),
        ),
        // 收响应走的是流式通道（按 data: 行解析），这里必须跟着说流式
        stream = true,
        maxOutputTokens = maxOutputTokens,
        reasoningEnabled = false,
    )

    /** 把多卷合并成前情总览的请求。 */
    fun buildMergeRequest(
        modelId: String,
        volumes: List<StoryVolume>,
        instruction: String = MERGE_INSTRUCTION,
        maxOutputTokens: Int? = DEFAULT_MERGE_MAX_TOKENS,
    ): ChatCompletionRequest = ChatCompletionRequest(
        modelId = modelId,
        messages = listOf(
            ChatMessage(ChatRole.System, instruction),
            ChatMessage(ChatRole.User, joinVolumes(volumes)),
        ),
        stream = true,
        maxOutputTokens = maxOutputTokens,
        reasoningEnabled = false,
    )

    /**
     * 把若干卷拼成放进提示词开头的那一段。没有卷时返回 null。
     *
     * 每卷前面标上轮次范围。标出来是为了让模型知道这些事的先后，
     * 也为了用户在界面上看到的和模型看到的是同一个东西。
     */
    fun volumesSection(volumes: List<StoryVolume>): String? {
        if (volumes.isEmpty()) return null
        return VOLUMES_HEADER + "\n\n" + joinVolumes(volumes)
    }

    /** 按轮次先后把各卷拼起来。 */
    private fun joinVolumes(volumes: List<StoryVolume>): String =
        volumes.sortedBy { it.fromTurn }.joinToString("\n\n") { volume ->
            "【第 ${volume.fromTurn}–${volume.toTurn} 轮】\n${volume.text.trim()}"
        }

    /**
     * 一卷摘要的长度上限。
     *
     * 定得不大是有意的：摘要要是写得和原文一样长，压缩就没有意义了。
     */
    const val DEFAULT_SUMMARY_MAX_TOKENS: Int = 800

    /** 前情总览的长度上限。它要盖住好几卷，所以给得多一些。 */
    const val DEFAULT_MERGE_MAX_TOKENS: Int = 1_600
}
