package com.mirage.engine.llm

/**
 * 内置的接入点模板。
 *
 * 模板的意义是省掉用户查地址、拼路径这一步，而不是替用户做决定 ——
 * 任何模板都可以复制一份再改，也可以完全自己填。
 *
 * ## 关于字段的可信度
 *
 * 下面每一项都标注了是否经过真实请求验证。**没验证过的项，
 * 其地址和参数形状都只是待确认的起点，不是事实。**
 * 接入任何一项之前都应当先用一次最小请求打通，确认三件事：
 * 地址对不对、流式能不能出字、关闭思考的参数认不认。
 *
 * 之所以把没验证的也放进来：用户至少不用从零开始查，
 * 而把它们假装成已验证的则会让错误地址伪装成「模型不存在」之类的误导性报错。
 *
 * ## 关于思考过程的默认值
 *
 * 标注为「不支持」的项，含义是**我们还不知道它的关闭开关长什么样**，
 * 于是选择不发任何相关参数。这是安全但不省钱的选择：
 * 发一个对方不认识的键有被整个请求拒绝的风险，而不发只是让思考保持默认状态。
 * 等验证出正确形状后再逐项打开。
 */
object ProviderCatalog {

    /** 已用真实请求验证过地址、流式与思考开关的接入点。 */
    val verified: List<ProviderProfile> = listOf(
        ProviderProfile(
            id = "deepseek",
            name = "DeepSeek",
            baseUrl = "https://api.deepseek.com",
            defaultModelId = "deepseek-chat",
            reasoningControl = ReasoningControl.ThinkingTypeObject,
        ),
        ProviderProfile(
            id = "openrouter",
            name = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            // 模型标识高度依赖用户选择，给默认值不如让用户自己填
            defaultModelId = null,
            reasoningControl = ReasoningControl.ReasoningEnabledObject,
            // 它会把请求转交给背后的多家，每次可能不同，必须记录实际承接方
            routesToMultipleVendors = true,
        ),
    )

    /**
     * 地址来自公开资料、**尚未经过我们的真实请求验证**的接入点。
     *
     * 列出它们的唯一目的是降低用户的填写成本。每一项在首次成功调用之前
     * 都应被当作「可能是错的」。
     */
    val unverified: List<ProviderProfile> = listOf(
        ProviderProfile(
            id = "zhipu",
            name = "智谱 GLM",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
        ),
        ProviderProfile(
            id = "moonshot",
            name = "Moonshot Kimi",
            baseUrl = "https://api.moonshot.cn/v1",
        ),
        ProviderProfile(
            id = "dashscope",
            name = "阿里云百炼",
            // 这家的原生接口与通用格式不同，这里用的是它提供的兼容路径
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        ),
        ProviderProfile(
            id = "siliconflow",
            name = "SiliconFlow",
            baseUrl = "https://api.siliconflow.cn/v1",
        ),
        ProviderProfile(
            id = "minimax",
            name = "MiniMax",
            baseUrl = "https://api.minimax.chat/v1",
        ),
        ProviderProfile(
            id = "volcengine",
            name = "火山方舟",
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
        ),
        ProviderProfile(
            id = "openai",
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
        ),
    )

    /** 全部内置模板。 */
    val all: List<ProviderProfile> = verified + unverified

    /** 按标识取模板，取不到返回 null 而不是抛异常 —— 存量数据里可能有已下架的标识。 */
    fun findById(id: String): ProviderProfile? = all.firstOrNull { it.id == id }

    /**
     * 新建一个用户自填的接入点。
     *
     * 单独提供这个入口，是为了保证自填配置的 [ProviderProfile.origin] 一定正确，
     * 不依赖各个调用点自己记得传对。
     */
    fun userDefined(
        id: String,
        name: String,
        baseUrl: String,
        modelId: String? = null,
        reasoningControl: ReasoningControl = ReasoningControl.Unsupported,
    ): ProviderProfile = ProviderProfile(
        id = id,
        name = name,
        baseUrl = baseUrl,
        defaultModelId = modelId,
        reasoningControl = reasoningControl,
        origin = ProfileOrigin.UserDefined,
    )
}
