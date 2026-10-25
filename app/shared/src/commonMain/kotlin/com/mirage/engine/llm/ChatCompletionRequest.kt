package com.mirage.engine.llm

/**
 * 一次对话补全调用的参数。
 *
 * 可选参数一律用可空类型而不是给默认数值，因为「不发这个键」和
 * 「发一个等于默认值的键」在实际行为上可能不同：有的服务商会因为某个键的存在
 * 而切换到另一套处理逻辑。想要服务商的默认行为，唯一可靠的办法是不发那个键。
 */
data class ChatCompletionRequest(
    /** 模型标识。由用户或模板提供，我们不做任何推测或补全。 */
    val modelId: String,

    /** 消息列表，按时间顺序。 */
    val messages: List<ChatMessage>,

    /** 是否流式。 */
    val stream: Boolean = true,

    /** 采样温度。为空则不发，使用服务商默认值。 */
    val temperature: Double? = null,

    /** 核采样阈值。为空则不发。 */
    val topP: Double? = null,

    /**
     * 输出长度上限。为空则不发。
     *
     * 默认为空是刻意的：这个上限是**思考过程和正文共享**的，
     * 一旦设小，思考就可能把额度吃光，导致正文一个字都不剩 ——
     * 而这种失败看起来像「模型什么都没说」，极难定位。
     * 只有在确实需要约束输出规模、且已经关闭思考的场景才应该设它。
     */
    val maxOutputTokens: Int? = null,

    /**
     * 是否允许模型输出思考过程。
     *
     * 默认关闭，而且这个默认值很重要：**多数服务商在不发任何相关参数时
     * 是默认开启思考的**，而思考 token 的开销可能是正文的若干倍。
     * 「不管这个参数」是实际成本最高的选项，所以必须显式关。
     *
     * 注意这个开关只在服务商支持对应参数时才会生效，
     * 不支持的情况下我们不会发任何相关键，思考仍会保持服务商的默认状态。
     */
    val reasoningEnabled: Boolean = false,
)

/** 请求参数在发送前被查出的问题类别。 */
enum class RequestDefectCode {
    /** 消息列表为空。 */
    EmptyMessages,

    /** 模型标识为空白。 */
    BlankModelId,

    /** 某条消息的正文为空白。 */
    BlankMessageContent,

    /** 温度超出可接受范围。 */
    TemperatureOutOfRange,

    /** 核采样阈值超出可接受范围。 */
    TopPOutOfRange,

    /** 输出上限不是正数。 */
    NonPositiveMaxOutputTokens,
}

/**
 * 一条校验结果。
 *
 * @property messageIndex 问题出在某条消息上时给出它的下标，否则为空。
 *   没有它的话，一个长列表里哪条有问题只能靠人肉比对。
 */
data class RequestDefect(
    val code: RequestDefectCode,
    val messageIndex: Int? = null,
)

/**
 * 发送前自检。
 *
 * 为什么必须有这一步：请求体是本地拼出来的，拼错了**只有服务端会告诉你**，
 * 而它的反馈往往是一个笼统的「请求无效」，既要等一次网络往返，
 * 又不指出错在哪个字段。把能在本地判定的问题就地查出来，
 * 可以把一类排查成本很高的问题变成立刻可见。
 *
 * 返回全部问题而不是遇到第一个就停：一次把所有毛病看完，比改一个跑一次快得多。
 *
 * 校验范围刻意保守 —— 只查「确定是错的」，不查「看起来可疑的」。
 * 把合法但少见的用法拦下来，比漏过一个错误更难排查。
 */
fun ChatCompletionRequest.findDefects(): List<RequestDefect> {
    val defects = mutableListOf<RequestDefect>()

    if (modelId.isBlank()) defects += RequestDefect(RequestDefectCode.BlankModelId)
    if (messages.isEmpty()) defects += RequestDefect(RequestDefectCode.EmptyMessages)

    messages.forEachIndexed { index, message ->
        if (message.content.isBlank()) {
            defects += RequestDefect(RequestDefectCode.BlankMessageContent, index)
        }
    }

    // 范围取各家普遍接受的区间，只拦明显越界的值
    temperature?.let {
        if (it < 0.0 || it > 2.0) defects += RequestDefect(RequestDefectCode.TemperatureOutOfRange)
    }
    topP?.let {
        if (it <= 0.0 || it > 1.0) defects += RequestDefect(RequestDefectCode.TopPOutOfRange)
    }
    maxOutputTokens?.let {
        if (it <= 0) defects += RequestDefect(RequestDefectCode.NonPositiveMaxOutputTokens)
    }

    return defects
}

/**
 * 自检不通过就直接抛。
 *
 * 抛出的信息是给开发者看的，不是给用户看的文案 ——
 * 走到这里说明是代码缺陷或上游传参错误，正常使用不应触发。
 * 面向用户的提示由调用方根据 [findDefects] 的结果自行决定。
 */
fun ChatCompletionRequest.requireWellFormed() {
    val defects = findDefects()
    require(defects.isEmpty()) { "malformed chat request: $defects" }
}
