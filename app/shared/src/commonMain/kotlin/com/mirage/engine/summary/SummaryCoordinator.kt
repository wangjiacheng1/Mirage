package com.mirage.engine.summary

import com.mirage.engine.llm.ChatCompletionClient
import com.mirage.engine.llm.ChatCompletionRequest
import com.mirage.engine.llm.ChatStreamEvent
import com.mirage.engine.llm.ProviderProfile
import com.mirage.engine.llm.StreamError
import com.mirage.engine.llm.TokenUsage
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * 一次压缩的结果。
 *
 * @property text 摘要正文。为 null 表示这次没拿到可用的内容，
 *   调用方不应该存卷 —— 存一个空卷比不存更糟，它会永久顶替掉那几轮原文。
 * @property error 失败原因。只给分类，文案由界面决定。
 */
data class SummaryOutcome(
    val text: String?,
    val usage: TokenUsage = TokenUsage(),
    val error: StreamError? = null,
)

/**
 * 发一次压缩请求，把模型写的摘要拿回来。
 *
 * ## 这一步在整条链路里的位置
 *
 * ```
 * 判断该压哪几轮（CompactionPlanner）
 *   ──► 造请求（SummaryPromptBuilder）
 *   ──► 调模型（这里）
 *   ──► 存成一卷（ChatStore）
 *   ──► 以后每轮都放进提示词开头
 * ```
 *
 * 压新卷和合并旧卷都走这一个方法 —— 两者的区别全在请求里，
 * 调用模型、攒内容、判定成败这些事完全一样。
 *
 * ## 为什么复用流式通道
 *
 * 和结算那次一样的理由：摘要完全可以用非流式请求拿，但那要再写一条
 * 读整个响应体的通道。复用现成的流式通道，只是把分片攒起来最后一次性用，
 * 代价可以忽略，却白拿了超时判定和错误归类。
 *
 * ## 只收正文
 *
 * 请求里已经显式关掉思考，但仍要防着服务方不理这个参数。
 * 思考内容混进摘要会被原样存成一卷，而且**之后每一轮都会发出去** ——
 * 一次没挡住，后面每轮都在为它付钱。
 */
class SummaryCoordinator(
    private val chatClient: ChatCompletionClient,
) {

    suspend fun summarize(
        profile: ProviderProfile,
        credential: String,
        request: ChatCompletionRequest,
        idleTimeout: Duration = DEFAULT_IDLE_TIMEOUT,
    ): SummaryOutcome {
        val collected = StringBuilder()
        var usage = TokenUsage()
        var streamError: StreamError? = null

        chatClient.stream(
            profile = profile,
            credential = credential,
            request = request,
            idleTimeout = idleTimeout,
        ).collect { event ->
            when (event) {
                is ChatStreamEvent.TextDelta -> collected.append(event.text)

                is ChatStreamEvent.Completed -> usage = event.usage

                // 只记第一个错误。后面的往往是同一个问题的连带表现，
                // 用后来的覆盖掉最初那个会让排查偏开
                is ChatStreamEvent.Failed -> if (streamError == null) streamError = event.error

                else -> Unit
            }
        }

        val text = collected.toString().trim()

        streamError?.let { return SummaryOutcome(text = null, usage = usage, error = it) }

        // 空白内容当失败处理。存一个空卷会永久顶替掉那几轮原文，
        // 而且表现成「它把中间那一段完全忘了」，极难定位
        return SummaryOutcome(
            text = text.takeIf { it.isNotEmpty() },
            usage = usage,
        )
    }

    private companion object {
        /**
         * 多久没有新内容就算卡死。
         *
         * 给得比叙事宽松：摘要要先读完一大段原文才开始写，
         * 首个字出来之前的等待本来就比平时长。
         */
        val DEFAULT_IDLE_TIMEOUT: Duration = 2.minutes
    }
}
