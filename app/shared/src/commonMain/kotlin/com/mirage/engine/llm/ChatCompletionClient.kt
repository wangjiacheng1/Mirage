package com.mirage.engine.llm

import com.mirage.engine.llm.sse.SseFrameReader
import com.mirage.engine.net.HttpStreamEvent
import com.mirage.engine.net.HttpStreamRequest
import com.mirage.engine.net.HttpStreamTransport
import com.mirage.engine.net.TransportFailureKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 把一次对话请求跑成一条语义事件流。
 *
 * 这个类只做编排，四件事都委托给各自的专职组件：
 * 组装请求体、发送并按行读回、把行组装成帧、把帧解释成事件。
 * 它自己唯一的职责是把它们接起来，并决定失败时如何收场。
 *
 * 依赖的是传输层的抽象而不是具体实现，因此这里的全部逻辑
 * 都可以用一个几行的假传输来测，不需要任何网络环境。
 */
class ChatCompletionClient(
    private val transport: HttpStreamTransport,
) {

    /**
     * 发起一次流式对话。
     *
     * @param credential 访问凭据。**作为参数传入而不是放进配置里**，
     *   是为了让配置对象可以安全地打印、导出、写进日志，而凭据永远不跟着走。
     * @param idleTimeout 允许多久收不到任何新内容。不同用途对这个值的合理预期
     *   差别很大，所以由调用方按场景指定。
     *
     * 返回的事件流在以下情况结束：收到结束标记、连接读完、或出现失败。
     * 失败时只发失败事件，**不会再发结束事件** —— 让「成功结束」保持唯一含义，
     * 调用方才能靠它来判断是否可以把内容当作完整结果使用。
     */
    fun stream(
        profile: ProviderProfile,
        credential: String,
        request: ChatCompletionRequest,
        idleTimeout: Duration = DEFAULT_IDLE_TIMEOUT,
    ): Flow<ChatStreamEvent> = flow {
        val httpRequest = HttpStreamRequest(
            url = profile.chatCompletionsUrl,
            body = ChatRequestBodyBuilder.build(profile, request).toString(),
            headers = authHeaders(credential),
            idleTimeout = idleTimeout,
        )

        val frameReader = SseFrameReader()
        val decoder = ChatStreamDecoder()
        var failed = false

        transport.openLineStream(httpRequest).collect { event ->
            // 解码器一旦判定流已结束，后面到达的一切都不再有意义，直接丢弃。
            // 这不是防御性冗余：如果服务端在结束标记之后不立刻关闭连接，
            // 空闲判定迟早会触发，于是在已经产出「正常结束」之后又追加一个失败事件 ——
            // 事件顺序自相矛盾，调用方无法判断这次调用到底成没成。
            if (decoder.isTerminated) return@collect

            when (event) {
                is HttpStreamEvent.Connected -> emit(ChatStreamEvent.ResponseStarted)

                is HttpStreamEvent.Line ->
                    frameReader.accept(event.text)?.let { frame ->
                        decoder.decode(frame).forEach { decoded -> emit(decoded) }
                    }

                is HttpStreamEvent.HttpFailure -> {
                    failed = true
                    emit(
                        ChatStreamEvent.Failed(
                            ProviderErrorParser.fromFailureBody(event.status, event.body),
                        ),
                    )
                }

                is HttpStreamEvent.TransportFailure -> {
                    failed = true
                    emit(ChatStreamEvent.Failed(event.toStreamError()))
                }
            }
        }

        if (failed) return@flow

        // 连接读完了但最后一帧没有以空行收尾时，把它补交给解码器 ——
        // 收尾帧常常正是携带用量和停止原因的那一帧，丢了最难排查
        frameReader.flushPendingFrame()?.let { frame ->
            decoder.decode(frame).forEach { decoded -> emit(decoded) }
        }
        decoder.finish().forEach { decoded -> emit(decoded) }
    }

    /**
     * 构造带凭据的请求头。
     *
     * 凭据为空白时不放这个头，而不是放一个空值：带着空凭据发出去，
     * 服务端多半回一个含义模糊的拒绝，不如让它以「没有授权」的形式明确失败。
     */
    private fun authHeaders(credential: String): Map<String, String> =
        if (credential.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $credential")

    private companion object {
        /**
         * 默认的空闲上限。
         *
         * 取值考虑的是首次响应可能很慢 —— 实测存在十几秒毫无动静、
         * 连心跳都没有的情况，设得太短会把正常请求判成卡死。
         */
        val DEFAULT_IDLE_TIMEOUT: Duration = 60.seconds
    }
}

/**
 * 把传输层的失败翻译成调用方使用的错误分类。
 *
 * 之所以要翻译而不是直接透出：传输层的分类是按「网络上发生了什么」划分的，
 * 而调用方需要的是按「用户能采取什么行动」划分的。
 */
private fun HttpStreamEvent.TransportFailure.toStreamError(): StreamError = StreamError(
    code = when (kind) {
        TransportFailureKind.IdleTimeout -> StreamErrorCode.IdleTimeout
        TransportFailureKind.RequestFailed -> StreamErrorCode.NetworkFailure
    },
    diagnostic = diagnostic,
)
