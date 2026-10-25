package com.mirage.engine.net

/**
 * 把请求和响应整理成便于阅读的诊断文本。
 *
 * 🔴 **这里是凭据脱敏的唯一执行点，而且脱敏不可关闭、不可绕过。**
 *
 * 把脱敏放在格式化内部、而不是交给调用处处理，是一个刻意的防御设计：
 * 只要有任何一个调用处忘了脱敏，凭据就会进日志，而日志又最容易被复制进
 * 问题反馈、截图和聊天记录里。做成「调用方根本拿不到未脱敏版本」之后，
 * 这类疏漏在结构上就不可能发生。
 *
 * 输出按行返回，因为不少平台的日志设施对单条消息长度有上限，
 * 超长内容会被悄悄截断，而被截掉的往往正是要看的部分。
 */
object TransportLogFormat {

    /**
     * 必须脱敏的请求头名字（小写比较）。
     *
     * 除了这份名单，任何名字里带有下列词根的请求头也一并脱敏，
     * 用来兜住各家自定义的凭据头 —— 宁可多遮一个无关的头，
     * 也不要漏掉一个带凭据的头。
     */
    private val SENSITIVE_HEADER_NAMES = setOf(
        "authorization",
        "proxy-authorization",
        "cookie",
        "set-cookie",
    )

    private val SENSITIVE_NAME_FRAGMENTS = listOf("key", "token", "secret", "credential", "auth")

    /** 单行输出的长度上限，超出的部分截断并标注。 */
    private const val MAX_LINE_CHARS = 2000

    /**
     * 整理一次请求。
     *
     * 请求体原样输出（必要时分段），因为排查问题时最常要看的就是
     * 「到底发出去了什么结构」。请求体本身不含凭据 —— 凭据只在请求头里。
     */
    fun request(request: HttpStreamRequest): List<String> = buildList {
        add("--> POST ${request.url}")
        maskHeaders(request.headers).forEach { (name, value) -> add("--> $name: $value") }
        add("--> content-type: ${request.contentType}")
        add("--> accept: ${request.accept}")
        add("--> body (${request.body.length} chars):")
        addAll(segment(request.body).map { "--> $it" })
    }

    /** 整理响应状态。 */
    fun responseStatus(status: Int): String = "<-- $status"

    /**
     * 整理响应中的一行。
     *
     * 空行会被显式标注出来。在按行组织的协议里空行是分隔符，
     * 如果日志里只显示成一个看不见的空白，就完全看不出分帧在哪里发生。
     */
    fun responseLine(text: String): String =
        if (text.isEmpty()) "<-- (空行)" else "<-- ${truncate(text)}"

    /** 整理一次失败。 */
    fun failure(description: String): String = "<-- 失败: ${truncate(description)}"

    /** 整理流的正常结束。 */
    fun responseEnd(): String = "<-- 结束"

    /**
     * 对请求头做脱敏。
     *
     * 保留「长度」而不是整体替换成一个固定串，是因为排查时最常见的问题
     * 其实是「到底带没带凭据」「是不是带了个空值」「是不是多了换行」。
     * 长度足以回答这些，又不泄露内容本身。
     */
    private fun maskHeaders(headers: Map<String, String>): Map<String, String> =
        headers.mapValues { (name, value) ->
            if (isSensitive(name)) mask(value) else value
        }

    private fun isSensitive(name: String): Boolean {
        val lower = name.lowercase()
        if (lower in SENSITIVE_HEADER_NAMES) return true
        return SENSITIVE_NAME_FRAGMENTS.any { lower.contains(it) }
    }

    /**
     * 遮蔽一个值，但保留它的形态线索。
     *
     * 认证方案前缀（例如 Bearer）会保留，因为写错方案也是常见问题，
     * 而方案名本身不是机密。前缀之后的全部内容一律不输出。
     */
    private fun mask(value: String): String {
        val separator = value.indexOf(' ')
        if (separator > 0) {
            val scheme = value.substring(0, separator)
            val rest = value.substring(separator + 1)
            return "$scheme ****(${rest.length} chars)"
        }
        return "****(${value.length} chars)"
    }

    /** 把长文本切成多段，避免被日志设施整条丢弃。 */
    private fun segment(text: String): List<String> =
        if (text.length <= MAX_LINE_CHARS) listOf(text) else text.chunked(MAX_LINE_CHARS)

    private fun truncate(text: String): String =
        if (text.length <= MAX_LINE_CHARS) {
            text
        } else {
            text.take(MAX_LINE_CHARS) + "…(省略 ${text.length - MAX_LINE_CHARS} 字符)"
        }
}
