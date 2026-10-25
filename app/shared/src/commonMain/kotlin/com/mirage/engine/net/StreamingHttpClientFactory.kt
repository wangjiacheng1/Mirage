package com.mirage.engine.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * 创建用于流式调用的 HTTP 客户端。
 *
 * 具体引擎由调用方传入，而不是在这里选定：引擎是平台相关的，
 * 写死会让这段代码无法在不同平台和测试环境中复用。
 */
object StreamingHttpClientFactory {

    /**
     * @param engine 平台提供的引擎。
     * @param connectTimeout 建立连接的等待上限。只覆盖连接阶段，不影响读取。
     * @param socketTimeout 底层读取的空闲上限。
     *   刻意设得比调用方自己的空闲判定更宽松，让我们自己的判定先触发 ——
     *   这样拿到的失败原因是明确的，而不是一个笼统的底层超时。
     */
    fun create(
        engine: HttpClientEngineFactory<*>,
        connectTimeout: Duration = 30.seconds,
        socketTimeout: Duration = 10.minutes,
    ): HttpClient = HttpClient(engine) {
        // 非成功状态码不抛异常，由我们自己读出响应体再决定怎么归类。
        // 让它抛的话，服务端写在响应体里的失败原因就拿不到了。
        expectSuccess = false

        install(HttpTimeout) {
            connectTimeoutMillis = connectTimeout.inWholeMilliseconds
            socketTimeoutMillis = socketTimeout.inWholeMilliseconds

            // 🔴 整体请求时长必须不设限。
            // 流式生成的总时长本来就可能很久，设了上限就会在长回复进行到一半时
            // 把连接掐断，而且表现为「说到一半突然没了」，极易被误判成模型问题。
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
        }
    }
}
