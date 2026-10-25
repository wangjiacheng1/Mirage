package com.mirage.engine.llm

/**
 * 模型本次生成为什么停下来。
 *
 * 判断一次调用成功与否必须看这个值，**不能看正文是否为空**：
 * 正文为空在某些情况下是完全正常的（例如模型这一轮只产出了工具调用），
 * 而正文不为空也可能是被截断的残缺内容。只有停止原因能区分这两种情况。
 */
enum class FinishReason {
    /** 正常说完了。只有这个值代表内容是完整的。 */
    Stop,

    /**
     * 撞到了长度上限被截断。
     *
     * 需要特别当心的是：上限是「思考过程 + 正文」共享的。
     * 如果模型开了思考，思考本身就可能把额度吃光，导致正文一个字都没留下。
     */
    Length,

    /** 停下来是为了发起工具调用。此时正文为空属于正常。 */
    ToolCalls,

    /** 被服务商的内容策略拦下。 */
    ContentFilter,

    /**
     * 服务商返回了我们不认识的停止原因。
     *
     * 保留这一项而不是直接报错，是因为各家会自行扩展这个字段，
     * 遇到新值时我们应该把流当作已结束来处理，而不是让整次调用崩掉。
     * 原始字面量由 [ParsedFinishReason.raw] 带出，便于排查。
     */
    Unknown,
}

/**
 * 停止原因的解析结果，同时保留枚举值和服务商给的原始字面量。
 *
 * 之所以不直接返回枚举：遇到 [FinishReason.Unknown] 时，
 * 排查问题唯一有用的信息就是那个没被识别的原始字符串。
 */
data class ParsedFinishReason(
    val reason: FinishReason,
    val raw: String,
) {
    companion object {
        /**
         * 把协议里的字面量映射成枚举。大小写和首尾空白都做了容错，
         * 因为这个值来自外部系统，不值得为格式瑕疵让整条流失败。
         */
        fun parse(raw: String): ParsedFinishReason {
            val reason = when (raw.trim().lowercase()) {
                "stop", "end_turn", "eos" -> FinishReason.Stop
                "length", "max_tokens" -> FinishReason.Length
                "tool_calls", "function_call" -> FinishReason.ToolCalls
                "content_filter" -> FinishReason.ContentFilter
                else -> FinishReason.Unknown
            }
            return ParsedFinishReason(reason, raw)
        }
    }
}
