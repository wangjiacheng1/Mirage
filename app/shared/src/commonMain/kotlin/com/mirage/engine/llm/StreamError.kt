package com.mirage.engine.llm

/**
 * 流式调用可能出现的失败类别。
 *
 * 这里只有分类、没有文案。界面要显示什么话、显示得多详细、用什么语言，
 * 全部由展示层根据这个分类自己决定。引擎产出文案会导致同一句话被钉死在
 * 所有界面上，也没法换语言。
 *
 * 分类的粒度按「用户能采取的行动」来划分，而不是按协议错误码一一对应：
 * 比如额度不足和未授权在协议上都可能是 4xx，但前者要去充值、后者要去改配置，
 * 是两件完全不同的事，所以分开；而各种 5xx 对用户来说都只能等或重试，合成一类。
 */
enum class StreamErrorCode {
    /** 凭据无效或缺失。用户需要去检查配置。 */
    Unauthorized,

    /** 没有访问该资源的权限，凭据本身是有效的。 */
    Forbidden,

    /** 触发频率限制。等一会儿再试通常就好了。 */
    RateLimited,

    /** 余额或额度耗尽。用户需要去充值，重试没有意义。 */
    QuotaExhausted,

    /** 指定的模型不存在或当前凭据不可用。 */
    ModelUnavailable,

    /** 请求本身不合法。属于我们的 bug 或用户填了非法参数，重试没有意义。 */
    BadRequest,

    /** 服务端自身故障。 */
    ServerError,

    /**
     * 迟迟没有新内容到达。
     *
     * 判定依据是「距上一次收到内容的间隔」，而不是整次调用的总时长：
     * 一次生成本身就可能持续很久，按总时长判超时会把正常的长回复误杀。
     */
    IdleTimeout,

    /** 连接层面的失败，比如握手不上、中途断开。 */
    NetworkFailure,

    /** 收到了无法解析的负载。 */
    MalformedPayload,

    /** 调用方主动取消。 */
    Cancelled,

    /** 不属于以上任何一类。 */
    Unknown,
}

/**
 * 一次失败的完整描述。
 *
 * @property code 给展示层做分支用的分类。
 * @property httpStatus 有 HTTP 状态码时带上，没有则为 null（例如连接根本没建立起来）。
 * @property providerCode 服务商自己的错误标识，各家格式不同，原样带出。
 * @property diagnostic 服务商返回的原始描述。
 *
 * [diagnostic] 只用于排查问题 —— 写日志、给调试界面看、让用户复制去提问。
 * **不要把它当成给用户看的提示文案**：它可能是英文、可能很长、
 * 也可能包含对普通用户毫无意义的内部细节。
 */
data class StreamError(
    val code: StreamErrorCode,
    val httpStatus: Int? = null,
    val providerCode: String? = null,
    val diagnostic: String? = null,
) {
    companion object {
        /**
         * 按 HTTP 状态码归类。
         *
         * 402 和 429 要和其它 4xx 分开：它们对应的用户动作完全不同。
         * 404 在这个场景下几乎总是「模型名写错了」，而不是「地址写错了」，
         * 因为地址错了一般连不上或者返回别的状态码。
         */
        fun fromHttpStatus(
            status: Int,
            providerCode: String? = null,
            diagnostic: String? = null,
        ): StreamError {
            val code = when {
                status == 401 -> StreamErrorCode.Unauthorized
                status == 403 -> StreamErrorCode.Forbidden
                status == 402 -> StreamErrorCode.QuotaExhausted
                status == 404 -> StreamErrorCode.ModelUnavailable
                status == 429 -> StreamErrorCode.RateLimited
                status in 400..499 -> StreamErrorCode.BadRequest
                status in 500..599 -> StreamErrorCode.ServerError
                else -> StreamErrorCode.Unknown
            }
            return StreamError(code, status, providerCode, diagnostic)
        }
    }
}
