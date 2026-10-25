package com.mirage.engine.net

/**
 * 传输层向上汇报的事件。
 *
 * 这里只有传输层面的事实（收到一行、HTTP 失败了、连接出问题了），
 * 不做任何业务判断 —— 比如「这个状态码对用户意味着什么」属于上层的事。
 * 把判断留给上层，是为了让同一个传输实现服务于用途不同的调用。
 *
 * 流的正常结束不单独给事件：事件流自身结束就代表读完了。
 * 多一个「结束」事件会让每个使用者都要处理两种结束信号。
 */
sealed interface HttpStreamEvent {

    /**
     * 服务端已经接受请求并开始返回。
     *
     * 单独汇报这个节点，是为了把「还没连上」和「连上了但对方还没开始产出内容」
     * 区分开。这两种等待对使用者的含义完全不同：前者可能是网络有问题，
     * 后者说明请求已经被受理、只是还在排队或准备。
     * 没有这个区分，界面就只能显示一个笼统的转圈，而漫长的转圈
     * 会让人误以为卡死进而重试 —— 重试的代价是实打实的重复计费。
     */
    data class Connected(val status: Int) : HttpStreamEvent

    /**
     * 收到一行内容，不含行尾符。
     *
     * 空字符串是有意义的 —— 在按行组织的协议里空行通常是分隔符，不能过滤掉。
     */
    data class Line(val text: String) : HttpStreamEvent

    /**
     * 服务端返回了非成功状态码。
     *
     * @property body 响应体，取不到时为空。它可能包含服务端给出的失败原因，
     *   是排查这类问题唯一有价值的信息，所以即使请求失败也要尽力读出来。
     */
    data class HttpFailure(
        val status: Int,
        val body: String?,
    ) : HttpStreamEvent

    /**
     * 请求没能正常完成。
     *
     * 和 [HttpFailure] 的区别是：那个代表「对方回了，但回的是错误」，
     * 这个代表「没能走完一次完整的请求响应」。两者的后续处理完全不同，
     * 混成一类会让上层无法判断该不该重试。
     */
    data class TransportFailure(
        val kind: TransportFailureKind,
        val diagnostic: String? = null,
    ) : HttpStreamEvent
}

/** 请求未能完成的原因。 */
enum class TransportFailureKind {
    /** 超过允许的无内容间隔。 */
    IdleTimeout,

    /**
     * 请求过程中出了问题，没能走完。
     *
     * 连接失败、域名解析失败、证书问题、中途断开都归在这里。
     * 不再细分是因为：细分需要依赖各平台不同的异常类型，而对上层来说
     * 这些情况可采取的动作是一样的。具体原因由 [HttpStreamEvent.TransportFailure.diagnostic] 带出。
     */
    RequestFailed,
}
