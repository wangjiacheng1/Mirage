package com.mirage.engine.llm.sse

/**
 * 一个已经组装完成的服务端推送帧。
 *
 * 这一层只做协议拆包，完全不理解负载的含义 ——
 * [data] 里是什么格式、要怎么解释，由上层决定。
 * 把「拆包」和「解释」分开，是为了让同一个拆包器服务于不同用途的流。
 */
data class SseFrame(
    /**
     * 帧的类型名。多数流不带这个字段，此时为 null。
     */
    val event: String? = null,

    /**
     * 负载。
     *
     * 一帧里允许出现多行负载，它们会按出现顺序用换行连接后放在这里。
     * 这是协议规定的行为，不是我们的发挥。
     */
    val data: String = "",

    /** 服务端给这一帧标的序号，用于断线续传。多数流不带。 */
    val id: String? = null,

    /** 服务端建议的重连等待毫秒数。多数流不带。 */
    val retryMillis: Long? = null,
)
