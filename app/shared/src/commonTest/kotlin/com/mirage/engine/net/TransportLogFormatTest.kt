package com.mirage.engine.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 诊断输出的测试。
 *
 * 这一组里最重要的不是「格式好不好看」，而是**凭据绝不出现在输出里**。
 * 日志是最容易被复制进问题反馈、截图和聊天记录的东西，一旦漏出去就等于公开。
 * 所以这里用「原值的任何一段都不得出现在任何一行里」这种最严的断言方式，
 * 而不是只检查某个字段被替换掉了。
 */
class TransportLogFormatTest {

    /** 一个形态逼真但完全虚构的凭据。 */
    private val fakeCredential = "sk-abcdef0123456789abcdef0123456789"

    private fun requestWith(headers: Map<String, String>) = HttpStreamRequest(
        url = "https://example.com/v1/chat/completions",
        body = """{"model":"m","stream":true}""",
        headers = headers,
    )

    // ------------------------------------------------------------------
    // 脱敏
    // ------------------------------------------------------------------

    @Test
    fun 授权头的内容绝不出现在输出里() {
        val lines = TransportLogFormat.request(
            requestWith(mapOf("Authorization" to "Bearer $fakeCredential")),
        )
        val joined = lines.joinToString("\n")
        assertFalse(joined.contains(fakeCredential), "凭据原值不得出现在诊断输出里")
        // 连片段都不能漏：截断式泄露同样是泄露
        assertFalse(joined.contains(fakeCredential.take(12)), "凭据的任何片段都不得出现")
    }

    @Test
    fun 应保留认证方案与长度以便排查() {
        // 排查时最常见的问题是「到底带没带凭据」「是不是带了空值」「方案名写错了」
        // 保留方案名和长度足以回答这些，又不泄露内容
        val lines = TransportLogFormat.request(
            requestWith(mapOf("Authorization" to "Bearer $fakeCredential")),
        )
        val authLine = lines.single { it.contains("Authorization") }
        assertTrue(authLine.contains("Bearer"), "方案名本身不是机密，写错方案也是常见问题")
        assertTrue(authLine.contains("${fakeCredential.length} chars"))
    }

    @Test
    fun 没有方案前缀的凭据也要被完全遮蔽() {
        val lines = TransportLogFormat.request(requestWith(mapOf("X-Api-Key" to fakeCredential)))
        val joined = lines.joinToString("\n")
        assertFalse(joined.contains(fakeCredential))
        assertTrue(joined.contains("****"))
    }

    @Test
    fun 名字里带凭据词根的自定义头都要遮蔽() {
        // 各家自定义凭据头的命名五花八门，靠白名单必然漏，
        // 所以按词根兜底 —— 宁可多遮一个无关的头，也不要漏掉一个带凭据的
        listOf(
            "X-Secret-Thing",
            "My-Token",
            "App-Key",
            "Credential-Id",
            "X-Auth-Value",
        ).forEach { headerName ->
            val joined = TransportLogFormat
                .request(requestWith(mapOf(headerName to fakeCredential)))
                .joinToString("\n")
            assertFalse(joined.contains(fakeCredential), "$headerName 的值应被遮蔽")
        }
    }

    @Test
    fun 头名字的大小写不应影响脱敏() {
        listOf("authorization", "AUTHORIZATION", "Authorization").forEach { name ->
            val joined = TransportLogFormat
                .request(requestWith(mapOf(name to "Bearer $fakeCredential")))
                .joinToString("\n")
            assertFalse(joined.contains(fakeCredential), "$name 应被识别为敏感头")
        }
    }

    @Test
    fun 无关的头应原样保留() {
        // 全部遮蔽会让日志失去排查价值，所以遮蔽范围要准确
        val joined = TransportLogFormat
            .request(requestWith(mapOf("X-Title" to "Mirage")))
            .joinToString("\n")
        assertTrue(joined.contains("X-Title: Mirage"))
    }

    // ------------------------------------------------------------------
    // 内容可读性
    // ------------------------------------------------------------------

    @Test
    fun 应包含地址与请求体() {
        val lines = TransportLogFormat.request(requestWith(emptyMap()))
        val joined = lines.joinToString("\n")
        assertTrue(joined.contains("https://example.com/v1/chat/completions"))
        assertTrue(joined.contains("""{"model":"m","stream":true}"""))
    }

    @Test
    fun 超长请求体应分段而不是被整条丢弃() {
        // 不少平台的日志设施对单条消息有长度上限，超长会被悄悄截断甚至整条丢掉，
        // 而被丢掉的往往正是要看的那部分
        val huge = "x".repeat(5000)
        val lines = TransportLogFormat.request(
            HttpStreamRequest(url = "https://e.com", body = huge),
        )
        val bodyLines = lines.filter { it.startsWith("--> x") }
        assertTrue(bodyLines.size >= 2, "超长内容应被切成多行")
        val recovered = bodyLines.joinToString("") { it.removePrefix("--> ") }
        assertEquals(huge, recovered, "分段之后拼回来必须和原文一致，不能丢字符")
    }

    @Test
    fun 空行应被显式标注() {
        // 空行在按行组织的协议里是分隔符，显示成看不见的空白就看不出分帧在哪
        assertTrue(TransportLogFormat.responseLine("").contains("空行"))
        assertTrue(TransportLogFormat.responseLine("data: x").contains("data: x"))
    }

    @Test
    fun 状态与结束应有明确标记() {
        assertTrue(TransportLogFormat.responseStatus(200).contains("200"))
        assertTrue(TransportLogFormat.responseEnd().isNotBlank())
    }

    @Test
    fun 超长的单行应被截断并标注省略量() {
        val long = "y".repeat(5000)
        val line = TransportLogFormat.responseLine(long)
        assertTrue(line.length < long.length, "应当截断")
        assertTrue(line.contains("省略"), "必须标注有内容被省略，否则会误以为这就是全部")
    }

    // ------------------------------------------------------------------
    // 开关
    // ------------------------------------------------------------------

    @Test
    fun 默认实现应什么都不做() {
        // 诊断输出默认必须是关的：它会把完整请求与响应写出去，量很大
        TransportLog.Disabled.write("任何内容")
    }

    @Test
    fun 自定义实现应能收到输出() {
        val collected = mutableListOf<String>()
        val log = TransportLog { collected += it }
        TransportLogFormat.request(requestWith(emptyMap())).forEach(log::write)
        assertTrue(collected.isNotEmpty())
    }
}
