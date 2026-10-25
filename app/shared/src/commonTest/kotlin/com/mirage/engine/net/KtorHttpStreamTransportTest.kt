package com.mirage.engine.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * 传输实现的测试。
 *
 * 这里验的是真实 HTTP 行为：请求是不是按预期发出去的、非成功状态码怎么处理、
 * 空行有没有被保留、读完之后流会不会正常结束。
 * 用模拟引擎而不是真实网络，所以不消耗凭据也不花钱，并且可以构造
 * 真实环境里很难碰到的情形，比如服务端挂着连接一直不发内容。
 */
class KtorHttpStreamTransportTest {

    /**
     * 在真实时间下运行测试。
     *
     * 🔴 这些测试**不能**用默认的虚拟时间，原因是一个很容易踩的陷阱：
     *
     * 虚拟时间会让超时判定瞬间到期而不消耗真实时间，但模拟引擎投递响应体
     * 是在真实调度器上进行的，需要几毫秒。于是内容行都能读到（已缓冲，读取不挂起），
     * 唯独最后那次「等流结束」的读取会挂起 —— 此刻虚拟时钟瞬间跳过整个超时窗口，
     * 把一条正常读完的流判成空闲超时。
     *
     * 真实环境不存在这个问题：服务端发完就关连接，结束信号在毫秒内到达。
     * 所以这里切到真实时间，让测试的时间尺度和被测对象的时间尺度一致。
     */
    private fun realTimeTest(block: suspend CoroutineScope.() -> Unit) = runTest {
        withContext(Dispatchers.Default) { block() }
    }

    /** 记录发出的请求，便于事后断言。 */
    private val sentRequests = mutableListOf<HttpRequestData>()

    /**
     * 构造一个按指定内容应答的客户端。
     *
     * @param contentLength 响应体字节数。真实服务端总会以某种方式告知响应边界，
     *   不告知的话「读到哪里算完」就取决于模拟实现的细节，
     *   测出来的结论未必适用于真实环境。
     */
    private fun clientRespondingWith(
        status: HttpStatusCode,
        body: ByteReadChannel,
        contentType: String = "text/event-stream",
        contentLength: Long? = null,
    ): HttpClient = HttpClient(MockEngine) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
        }
        engine {
            addHandler { request ->
                sentRequests += request
                respond(
                    content = body,
                    status = status,
                    headers = Headers.build {
                        append(HttpHeaders.ContentType, contentType)
                        contentLength?.let { append(HttpHeaders.ContentLength, it.toString()) }
                    },
                )
            }
        }
    }

    /** 以一段文本作为响应体，并如实带上长度。 */
    private fun clientRespondingWithText(
        status: HttpStatusCode,
        text: String,
        contentType: String = "text/event-stream",
    ): HttpClient = clientRespondingWith(
        status = status,
        body = ByteReadChannel(text),
        contentType = contentType,
        contentLength = text.encodeToByteArray().size.toLong(),
    )

    private fun request(idleTimeoutMillis: Long = 10_000) = HttpStreamRequest(
        url = "https://example.com/v1/chat/completions",
        body = """{"hello":"world"}""",
        headers = mapOf("Authorization" to "Bearer token"),
        idleTimeout = idleTimeoutMillis.milliseconds,
    )

    // ------------------------------------------------------------------
    // 请求的发出
    // ------------------------------------------------------------------

    @Test
    fun 应以POST发出并带上请求头与请求体() = realTimeTest {
        val client = clientRespondingWithText(HttpStatusCode.OK, "")
        KtorHttpStreamTransport(client).openLineStream(request()).toList()

        val sent = assertNotNull(sentRequests.firstOrNull())
        assertEquals(HttpMethod.Post, sent.method)
        assertEquals("Bearer token", sent.headers["Authorization"])
        assertTrue(
            sent.headers[HttpHeaders.Accept]?.contains("event-stream") == true,
            "应声明期望流式响应",
        )
    }

    // ------------------------------------------------------------------
    // 正常读取
    // ------------------------------------------------------------------

    @Test
    fun 应按顺序产出每一行且保留空行() = realTimeTest {
        // 空行在按行组织的协议里是分隔符，过滤掉会导致整条流无法分帧
        val client = clientRespondingWithText(HttpStatusCode.OK, "data: one\n\ndata: two\n\n")
        val events = KtorHttpStreamTransport(client).openLineStream(request()).toList()

        assertEquals(
            listOf("data: one", "", "data: two", ""),
            events.filterIsInstance<HttpStreamEvent.Line>().map { it.text },
        )
    }

    @Test
    fun 中文内容应完整还原() = realTimeTest {
        val client = clientRespondingWithText(HttpStatusCode.OK, "data: 你好，世界\n\n")
        val events = KtorHttpStreamTransport(client).openLineStream(request()).toList()
        assertEquals(
            "data: 你好，世界",
            events.filterIsInstance<HttpStreamEvent.Line>().first().text,
        )
    }

    @Test
    fun 读完之后流应正常结束且不产出失败事件() = realTimeTest {
        val client = clientRespondingWithText(HttpStatusCode.OK, "data: x\n\n")
        val events = KtorHttpStreamTransport(client).openLineStream(request()).toList()

        // 刻意用相等断言而不是「不存在」断言：前者在失败时会把实际事件整个打印出来，
        // 包括失败种类和诊断信息；后者只会说一句「期望为真」，等于把线索全丢了。
        assertEquals(
            emptyList(),
            events.filterNot { it is HttpStreamEvent.Line || it is HttpStreamEvent.Connected },
            "正常读完的流不该产出任何失败事件",
        )
        assertEquals(
            listOf(200),
            events.filterIsInstance<HttpStreamEvent.Connected>().map { it.status },
            "应当汇报一次「响应已开始」，界面靠它区分「还在连」和「对方在准备」",
        )
    }

    // ------------------------------------------------------------------
    // 失败
    // ------------------------------------------------------------------

    @Test
    fun 非成功状态码应连同响应体一起报出() = realTimeTest {
        // 服务端给的失败原因通常只在响应体里，丢掉它就只剩一个状态码
        val payload = """{"error":{"message":"nope"}}"""
        val client = clientRespondingWithText(
            HttpStatusCode.Unauthorized,
            payload,
            contentType = "application/json",
        )
        val events = KtorHttpStreamTransport(client).openLineStream(request()).toList()

        val failure = events.filterIsInstance<HttpStreamEvent.HttpFailure>().single()
        assertEquals(401, failure.status)
        assertEquals(payload, failure.body)
        assertTrue(
            events.none { it is HttpStreamEvent.Line },
            "请求失败时不该把响应体当成内容行产出",
        )
    }

    @Test
    fun 长时间收不到内容应报空闲超时() = realTimeTest {
        // 服务端挂着连接不发内容，是真实环境里确实会出现、
        // 但很难人为复现的情形。这里用一个不写也不关的通道来模拟
        val neverEnding = ByteChannel()
        val client = clientRespondingWith(HttpStatusCode.OK, neverEnding)

        val events = KtorHttpStreamTransport(client)
            .openLineStream(request(idleTimeoutMillis = 50))
            .toList()

        val failure = events.filterIsInstance<HttpStreamEvent.TransportFailure>().single()
        assertEquals(TransportFailureKind.IdleTimeout, failure.kind)
    }
}
