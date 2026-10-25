package com.mirage.engine.llm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 请求体组装的测试。
 *
 * 这里断言的重点不只是「值对不对」，更是「**某个键到底在不在**」。
 * 多发一个键可能让服务商切到另一套处理逻辑，少发一个键可能让开销翻好几倍，
 * 而这两种错误都不会报错，只会表现为行为或账单上的异常，事后极难归因。
 */
class ChatRequestBodyBuilderTest {

    private val messages = listOf(
        ChatMessage(ChatRole.System, "设定"),
        ChatMessage(ChatRole.User, "开始"),
    )

    private fun request(
        modelId: String = "m",
        stream: Boolean = true,
        temperature: Double? = null,
        topP: Double? = null,
        maxOutputTokens: Int? = null,
        reasoningEnabled: Boolean = false,
    ) = ChatCompletionRequest(
        modelId = modelId,
        messages = messages,
        stream = stream,
        temperature = temperature,
        topP = topP,
        maxOutputTokens = maxOutputTokens,
        reasoningEnabled = reasoningEnabled,
    )

    private fun profile(
        reasoning: ReasoningControl = ReasoningControl.Unsupported,
        usage: UsageRequestStyle = UsageRequestStyle.NotRequested,
    ) = ProviderProfile(
        id = "p",
        name = "P",
        baseUrl = "https://example.com/v1",
        reasoningControl = reasoning,
        usageRequestStyle = usage,
    )

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.content
    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.obj(key: String) = this[key] as? JsonObject

    // ------------------------------------------------------------------
    // 基本结构
    // ------------------------------------------------------------------

    @Test
    fun 应包含模型与流式标记() {
        val body = ChatRequestBodyBuilder.build(profile(), request(modelId = "some-model"))
        assertEquals("some-model", body.str("model"))
        assertEquals(true, body.bool("stream"))
    }

    @Test
    fun 消息应按顺序展开为角色与正文() {
        val body = ChatRequestBodyBuilder.build(profile(), request())
        val list = body["messages"] as? JsonArray
        assertNotNull(list)
        assertEquals(2, list.size)

        val first = list[0] as JsonObject
        assertEquals("system", first.str("role"))
        assertEquals("设定", first.str("content"))

        val second = list[1] as JsonObject
        assertEquals("user", second.str("role"))
        assertEquals("开始", second.str("content"))
    }

    @Test
    fun 角色字面量应为小写() {
        // 大小写不一致会被部分服务商直接拒绝，而错误提示通常很笼统
        assertEquals("system", ChatRole.System.wireValue())
        assertEquals("user", ChatRole.User.wireValue())
        assertEquals("assistant", ChatRole.Assistant.wireValue())
    }

    // ------------------------------------------------------------------
    // 可选键的「在不在」
    // ------------------------------------------------------------------

    @Test
    fun 未设置的可选参数不应出现在请求里() {
        // 发一个等于默认值的键，和不发这个键，对服务商可能是两种行为
        val body = ChatRequestBodyBuilder.build(profile(), request())
        assertFalse(body.containsKey("temperature"))
        assertFalse(body.containsKey("top_p"))
        assertFalse(body.containsKey("max_tokens"))
    }

    @Test
    fun 设置了的可选参数应原样出现() {
        val body = ChatRequestBodyBuilder.build(
            profile(),
            request(temperature = 0.8, topP = 0.9, maxOutputTokens = 2048),
        )
        assertEquals(0.8, (body["temperature"] as JsonPrimitive).content.toDouble())
        assertEquals(0.9, (body["top_p"] as JsonPrimitive).content.toDouble())
        assertEquals(2048, body.int("max_tokens"))
    }

    // ------------------------------------------------------------------
    // 思考过程控制
    // ------------------------------------------------------------------

    @Test
    fun 关闭思考时仍然要把参数发出去而不是省略() {
        // 多数服务商默认开启思考，省略等于保持开启 ——
        // 那正是开销最高的状态，所以必须显式写出来
        val body = ChatRequestBodyBuilder.build(
            profile(reasoning = ReasoningControl.ThinkingTypeObject),
            request(reasoningEnabled = false),
        )
        val thinking = body.obj("thinking")
        assertNotNull(thinking, "关闭思考必须显式发参数，省略会让它保持开启")
        assertEquals("disabled", thinking.str("type"))
    }

    @Test
    fun 开启思考时应发出对应取值() {
        val body = ChatRequestBodyBuilder.build(
            profile(reasoning = ReasoningControl.ThinkingTypeObject),
            request(reasoningEnabled = true),
        )
        assertEquals("enabled", body.obj("thinking")?.str("type"))
    }

    @Test
    fun 布尔形状的思考参数应独立映射() {
        val off = ChatRequestBodyBuilder.build(
            profile(reasoning = ReasoningControl.ReasoningEnabledObject),
            request(reasoningEnabled = false),
        )
        assertEquals(false, off.obj("reasoning")?.bool("enabled"))
        assertFalse(off.containsKey("thinking"), "不该把另一种形状也发出去")

        val on = ChatRequestBodyBuilder.build(
            profile(reasoning = ReasoningControl.ReasoningEnabledObject),
            request(reasoningEnabled = true),
        )
        assertEquals(true, on.obj("reasoning")?.bool("enabled"))
    }

    @Test
    fun 不知道参数形状时一个相关键都不应发() {
        // 发对方不认识的键有被整个请求拒绝的风险，代价比思考没关掉更大
        val body = ChatRequestBodyBuilder.build(
            profile(reasoning = ReasoningControl.Unsupported),
            request(reasoningEnabled = false),
        )
        assertFalse(body.containsKey("thinking"))
        assertFalse(body.containsKey("reasoning"))
    }

    // ------------------------------------------------------------------
    // 索要用量
    // ------------------------------------------------------------------

    @Test
    fun 默认不索要用量() {
        val body = ChatRequestBodyBuilder.build(profile(), request())
        assertFalse(body.containsKey("stream_options"))
        assertFalse(body.containsKey("usage"))
    }

    @Test
    fun 两种索要用量的形状应分别生效() {
        val viaOptions = ChatRequestBodyBuilder.build(
            profile(usage = UsageRequestStyle.StreamOptionsFlag),
            request(),
        )
        assertEquals(true, viaOptions.obj("stream_options")?.bool("include_usage"))

        val viaObject = ChatRequestBodyBuilder.build(
            profile(usage = UsageRequestStyle.UsageIncludeObject),
            request(),
        )
        assertEquals(true, viaObject.obj("usage")?.bool("include"))
    }

    @Test
    fun 非流式时不应索要流式用量() {
        val body = ChatRequestBodyBuilder.build(
            profile(usage = UsageRequestStyle.StreamOptionsFlag),
            request(stream = false),
        )
        assertFalse(body.containsKey("stream_options"), "非流式响应本来就带用量")
        assertEquals(false, body.bool("stream"))
    }

    // ------------------------------------------------------------------
    // 发送前自检
    // ------------------------------------------------------------------

    @Test
    fun 空消息列表应被查出() {
        val defects = ChatCompletionRequest("m", emptyList()).findDefects()
        assertTrue(defects.any { it.code == RequestDefectCode.EmptyMessages })
    }

    @Test
    fun 空白模型标识应被查出() {
        val defects = ChatCompletionRequest("   ", messages).findDefects()
        assertTrue(defects.any { it.code == RequestDefectCode.BlankModelId })
    }

    @Test
    fun 空白正文应被查出并指出是第几条() {
        // 长列表里不给下标的话，只能人肉比对
        val defects = ChatCompletionRequest(
            "m",
            listOf(
                ChatMessage(ChatRole.System, "ok"),
                ChatMessage(ChatRole.User, "   "),
            ),
        ).findDefects()
        val defect = defects.single { it.code == RequestDefectCode.BlankMessageContent }
        assertEquals(1, defect.messageIndex)
    }

    @Test
    fun 越界的采样参数应被查出() {
        val defects = ChatCompletionRequest(
            "m", messages, temperature = 5.0, topP = 0.0, maxOutputTokens = 0,
        ).findDefects()
        val codes = defects.map { it.code }.toSet()
        assertTrue(RequestDefectCode.TemperatureOutOfRange in codes)
        assertTrue(RequestDefectCode.TopPOutOfRange in codes)
        assertTrue(RequestDefectCode.NonPositiveMaxOutputTokens in codes)
    }

    @Test
    fun 合法请求不应被挑出毛病() {
        // 自检只查确定是错的，不查看起来可疑的 ——
        // 误拦一个合法用法比漏过一个错误更难排查
        assertTrue(request(temperature = 2.0, topP = 1.0, maxOutputTokens = 1).findDefects().isEmpty())
    }

    @Test
    fun 组装非法请求应当场失败而不是发出去() {
        assertFailsWith<IllegalArgumentException> {
            ChatRequestBodyBuilder.build(profile(), ChatCompletionRequest("m", emptyList()))
        }
    }

    // ------------------------------------------------------------------
    // 接入点配置
    // ------------------------------------------------------------------

    @Test
    fun 地址拼接应容忍末尾斜杠() {
        val withSlash = ProviderProfile(id = "a", name = "A", baseUrl = "https://x.com/v1/")
        val without = ProviderProfile(id = "b", name = "B", baseUrl = "https://x.com/v1")
        assertEquals("https://x.com/v1/chat/completions", withSlash.chatCompletionsUrl)
        assertEquals("https://x.com/v1/chat/completions", without.chatCompletionsUrl)
    }

    @Test
    fun 模板标识应唯一否则存量配置会指向错误的项() {
        val ids = ProviderCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun 取不到的模板应返回空而不是抛异常() {
        // 存量数据里可能留有已下架的标识，不能让它导致崩溃
        assertEquals(null, ProviderCatalog.findById("已经不存在的标识"))
        assertNotNull(ProviderCatalog.findById("deepseek"))
    }

    @Test
    fun 未验证的模板不应预填模型标识() {
        // 填一个过期或拼错的模型名，报错会指向「模型不存在」，
        // 让用户以为是自己配错了，排查方向完全跑偏
        ProviderCatalog.unverified.forEach {
            assertEquals(null, it.defaultModelId, "${it.id} 未经验证，不应预填模型")
        }
    }

    @Test
    fun 用户自建的接入点来源应自动标对() {
        val custom = ProviderCatalog.userDefined("mine", "我的接入点", "https://my.host/v1")
        assertEquals(ProfileOrigin.UserDefined, custom.origin)
        assertEquals(ProfileOrigin.BuiltIn, ProviderCatalog.verified.first().origin)
    }
}
