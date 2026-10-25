package com.mirage.engine.net

import io.ktor.client.engine.okhttp.OkHttp

/**
 * 创建 Android 上的流式传输实现。
 *
 * 提供这个入口，是为了让界面层完全不必知道底层用的是哪个 HTTP 引擎 ——
 * 引擎的选择属于引擎模块的内部决定，泄漏到界面层会让「换引擎」变成跨模块改动。
 *
 * @param enableLogging 是否把完整的请求与响应写进系统日志。
 *   只在排查问题时打开：输出量很大，正式使用没有价值。
 *   请求头里的凭据由格式化环节强制遮蔽，不依赖调用处记得处理。
 */
fun createAndroidTransport(enableLogging: Boolean): HttpStreamTransport =
    KtorHttpStreamTransport(
        client = StreamingHttpClientFactory.create(OkHttp),
        log = if (enableLogging) androidTransportLog() else TransportLog.Disabled,
    )
