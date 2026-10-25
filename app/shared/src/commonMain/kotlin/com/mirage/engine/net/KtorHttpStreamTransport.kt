package com.mirage.engine.net

import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * 基于 HTTP 客户端库的流式传输实现。
 *
 * 几个关键取舍：
 *
 * - **按行读取交给底层库完成。** 从网络读到的是字节，一个多字节字符
 *   完全可能被切在两次读取之间。自己拼接就必须自己做增量字符解码，
 *   那对中文内容是实打实的出错风险，而底层库已经正确处理了这件事。
 *
 * - **失败用事件汇报，不抛异常。** 调用方拿到的是一条事件流，
 *   把失败混成异常会让它需要同时处理两套错误通道。
 *   唯一例外是协程取消，它必须原样向上传播。
 *
 * - **自己计时判断空闲，而不是依赖底层的读超时。** 这样超时原因是明确的，
 *   而且间隔长度可以按调用场景单独指定 —— 一次长篇生成和一次简短查询
 *   对「多久没动静算卡住」的合理预期相差很大。
 */
class KtorHttpStreamTransport(
    private val client: HttpClient,
    private val log: TransportLog = TransportLog.Disabled,
) : HttpStreamTransport {

    /**
     * 诊断输出是否开启。
     *
     * 预先判断一次，后面所有输出点都先查这个标志再去拼字符串。
     * 不这么做的话，即使输出被丢弃，拼接完整请求体和每一行内容的开销照样会付出，
     * 而这条路径在生成过程中每收到一行就会走一次。
     */
    private val isLogging: Boolean = log !== TransportLog.Disabled

    override fun openLineStream(request: HttpStreamRequest): Flow<HttpStreamEvent> = channelFlow {
        if (isLogging) TransportLogFormat.request(request).forEach(log::write)
        try {
            client.preparePost(request.url) {
                request.headers.forEach { (name, value) -> header(name, value) }
                contentType(ContentType.parse(request.contentType))
                accept(ContentType.parse(request.accept))
                setBody(request.body)
            }.execute { response ->
                if (isLogging) log.write(TransportLogFormat.responseStatus(response.status.value))

                if (!response.status.isSuccess()) {
                    // 即使失败也要尽力把响应体读出来：服务端给的失败原因通常只在这里，
                    // 丢掉它就只剩一个状态码，排查会变得非常困难
                    val failureBody = runCatching { response.bodyAsText() }.getOrNull()
                    if (isLogging && failureBody != null) {
                        log.write(TransportLogFormat.failure(failureBody))
                    }
                    send(HttpStreamEvent.HttpFailure(response.status.value, failureBody))
                    return@execute
                }
                send(HttpStreamEvent.Connected(response.status.value))
                pumpLines(response.bodyAsChannel(), request.idleTimeout)
            }
        } catch (cancellation: CancellationException) {
            // 主动取消不是错误。转成失败事件会让界面把用户自己的操作显示成报错
            throw cancellation
        } catch (failure: Throwable) {
            if (isLogging) log.write(TransportLogFormat.failure(failure.message ?: "未知错误"))
            send(
                HttpStreamEvent.TransportFailure(
                    kind = TransportFailureKind.RequestFailed,
                    diagnostic = failure.message,
                ),
            )
        }
    }

    /** 持续读行直到流结束或超过空闲间隔。 */
    private suspend fun ProducerScope<HttpStreamEvent>.pumpLines(
        channel: ByteReadChannel,
        idleTimeout: Duration,
    ) {
        while (true) {
            when (val outcome = readNextLine(channel, idleTimeout)) {
                is LineOutcome.Received -> {
                    if (isLogging) log.write(TransportLogFormat.responseLine(outcome.text))
                    send(HttpStreamEvent.Line(outcome.text))
                }

                LineOutcome.EndOfStream -> {
                    if (isLogging) log.write(TransportLogFormat.responseEnd())
                    return
                }

                LineOutcome.TimedOut -> {
                    if (isLogging) log.write(TransportLogFormat.failure("空闲超时"))
                    send(HttpStreamEvent.TransportFailure(TransportFailureKind.IdleTimeout))
                    return
                }
            }
        }
    }

    /**
     * 读下一行，并对「这次读取」限定等待时长。
     *
     * 用一个专门的结果类型而不是直接返回可空字符串，是因为
     * 「读到了流的末尾」和「等太久放弃了」都会表现为「没有内容」，
     * 但两者的含义完全相反：前者是正常结束，后者是故障。
     * 用同一个空值表达必然导致误判。
     */
    private suspend fun readNextLine(
        channel: ByteReadChannel,
        idleTimeout: Duration,
    ): LineOutcome {
        // 通道已经关闭且没有剩余内容时直接收尾，不要再发起一次读取。
        // 少了这一步，收尾会依赖「读取返回空值」这一个信号，而某些情况下
        // 这次读取会一直等下去，最终被空闲判定当成故障 —— 明明是正常读完的流，
        // 却报成超时，这种误判非常难查。
        if (channel.isClosedForRead) return LineOutcome.EndOfStream

        return withTimeoutOrNull(idleTimeout) {
            val text = channel.readUTF8Line()
            if (text == null) LineOutcome.EndOfStream else LineOutcome.Received(text)
        } ?: LineOutcome.TimedOut
    }
}

/** 单次读行的三种结果。 */
private sealed interface LineOutcome {
    data class Received(val text: String) : LineOutcome
    data object EndOfStream : LineOutcome
    data object TimedOut : LineOutcome
}
