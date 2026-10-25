package com.mirage.engine.llm

/**
 * 服务商控制「思考过程」的参数形状。
 *
 * 必须按服务商区分，不能统一成一个开关：各家的参数名和结构完全不同，
 * 发错了的后果不是报错，而是**被静默忽略** —— 思考照样开着、照样计费，
 * 而我们以为已经关掉了。这种失败没有任何症状，只能从账单上发现。
 */
enum class ReasoningControl {
    /** 不接受这类参数。此时任何思考相关的键都不应出现在请求里。 */
    Unsupported,

    /** 用一个带类型字段的对象表达，取值是「启用」或「停用」的字面量。 */
    ThinkingTypeObject,

    /** 用一个带布尔字段的对象表达。 */
    ReasoningEnabledObject,
}

/**
 * 是否需要显式索要用量数据，以及用什么形状索要。
 *
 * 默认是「不索要」：已验证过的两家在不加任何参数的情况下就会把用量随流送回。
 * 发一个对方不认识的键有被整个请求拒绝的风险，所以在确认需要之前不发。
 */
enum class UsageRequestStyle {
    /** 不发任何额外参数。 */
    NotRequested,

    /** 通过流式选项里的开关索要。 */
    StreamOptionsFlag,

    /** 通过一个独立的对象索要。 */
    UsageIncludeObject,
}

/**
 * 一个模型服务接入点的配置。
 *
 * 这个结构同时承担两个角色：内置模板的载体，和用户自填配置的载体。
 * 合成一个而不是分两套，是因为两者需要走完全相同的请求组装逻辑 ——
 * 分开会导致「模板能用、自填不能用」这类只在特定路径出现的问题。
 *
 * 刻意不包含凭据。凭据有独立的加密存放方式，和这份可以明文存、
 * 可以导出、可以打日志的配置必须分开，混在一起迟早会跟着配置一起泄出去。
 */
data class ProviderProfile(
    /**
     * 稳定标识，用于存储和引用。
     *
     * 一旦发布就不能再改：它会被写进用户的本地数据里，改了等于让存量配置失效。
     */
    val id: String,

    /**
     * 服务方名称。
     *
     * 这里放的是品牌专有名词（不翻译、不改写），不是界面文案。
     * 用户自建的接入点则存用户自己起的名字，属于用户数据。
     */
    val name: String,

    /** 接口根地址，不含具体路径，末尾不带斜杠。 */
    val baseUrl: String,

    /** 对话补全接口相对于根地址的路径。各家基本一致，留出来是为了应对例外。 */
    val chatCompletionsPath: String = "/chat/completions",

    /**
     * 建议使用的模型标识。
     *
     * 只在确实验证过的接入点上填写。没验证过的一律留空，由用户自己填 ——
     * 填一个过期或拼错的模型名，报错信息会指向「模型不存在」，
     * 让用户以为是自己配错了，排查方向完全跑偏。
     */
    val defaultModelId: String? = null,

    /** 思考过程的控制方式。 */
    val reasoningControl: ReasoningControl = ReasoningControl.Unsupported,

    /** 索要用量数据的方式。 */
    val usageRequestStyle: UsageRequestStyle = UsageRequestStyle.NotRequested,

    /**
     * 这个接入点是否会把请求转交给背后的多家服务方。
     *
     * 为真时必须记录每次实际承接方：同一个模型两次调用表现差异很大时，
     * 这是唯一能用来排查的线索，事后无法补查。
     */
    val routesToMultipleVendors: Boolean = false,

    /**
     * 配置来源，决定界面上允许改哪些字段。
     */
    val origin: ProfileOrigin = ProfileOrigin.BuiltIn,
) {
    /** 完整的对话补全地址。 */
    val chatCompletionsUrl: String
        get() = baseUrl.trimEnd('/') + chatCompletionsPath
}

/** 配置是内置模板还是用户自建。 */
enum class ProfileOrigin {
    /** 随应用提供的模板。地址等关键字段不允许用户直接改，只能复制后改。 */
    BuiltIn,

    /** 用户自己填的。所有字段都可改。 */
    UserDefined,
}
