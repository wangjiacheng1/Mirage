package com.mirage.engine.llm.sse

/**
 * 把一行行文本组装成 [SseFrame]。
 *
 * 纯状态机：喂进去行，攒够一帧就吐出来。不碰网络、不碰协程，
 * 因此可以用一组字符串完整覆盖它的所有分支，这是把它单独拆出来的主要原因。
 *
 * 处理的几个容易出错的点：
 * - **以冒号开头的行是注释，必须丢掉。** 有的服务端会定期发注释行当心跳，
 *   用来防止中间设备把闲置连接掐断。把心跳当负载解析会直接导致解析失败。
 * - **空行才是一帧的结束标志**，不是每行一帧。
 * - 字段值前面的**一个**空格要去掉，多余的空格属于值本身。
 * - 没有冒号的行，整行是字段名、值为空。
 *
 * 一个实例只服务一条流。
 */
class SseFrameReader {

    private val dataLines = mutableListOf<String>()
    private var event: String? = null
    private var id: String? = null
    private var retryMillis: Long? = null

    /** 本帧是否出现过任何有效字段。用来区分「真的收到了空帧」和「只是连续空行」。 */
    private var hasField = false

    /**
     * 吃进一行。
     *
     * @return 该行结束了一帧时返回那一帧，否则返回 null。
     */
    fun accept(line: String): SseFrame? {
        // 空行：结束当前帧
        if (line.isEmpty()) {
            if (!hasField) return null
            val frame = SseFrame(
                event = event,
                data = dataLines.joinToString("\n"),
                id = id,
                retryMillis = retryMillis,
            )
            resetFrameScopedState()
            return frame
        }

        // 注释行：整行丢弃，且不算作有效字段，否则心跳会凭空产生空帧
        if (line.startsWith(":")) return null

        val separator = line.indexOf(':')
        val field: String
        val rawValue: String
        if (separator < 0) {
            field = line
            rawValue = ""
        } else {
            field = line.substring(0, separator)
            rawValue = line.substring(separator + 1)
        }
        // 协议规定只吞掉紧跟冒号的那一个空格
        val value = if (rawValue.startsWith(" ")) rawValue.substring(1) else rawValue

        when (field) {
            "data" -> {
                dataLines += value
                hasField = true
            }

            "event" -> {
                event = value
                hasField = true
            }

            "id" -> {
                // 含空字符的序号按协议要求忽略，不能当成合法值存下来
                if (!value.contains('\u0000')) {
                    id = value
                    hasField = true
                }
            }

            "retry" -> {
                // 只接受纯数字，非法值直接忽略而不是让整条流失败
                value.toLongOrNull()?.let {
                    retryMillis = it
                    hasField = true
                }
            }

            // 未知字段按协议要求忽略
            else -> Unit
        }
        return null
    }

    /**
     * 流结束时调用。
     *
     * 如果最后一帧没有以空行收尾，这里把它补交出来。
     * 不这么做的话，正常结束的流会丢掉最后一帧 —— 而最后一帧恰恰常常是
     * 携带收尾信息的那一帧，丢了最难排查。
     */
    fun flushPendingFrame(): SseFrame? {
        if (!hasField) return null
        val frame = SseFrame(
            event = event,
            data = dataLines.joinToString("\n"),
            id = id,
            retryMillis = retryMillis,
        )
        resetFrameScopedState()
        return frame
    }

    /**
     * 清掉只属于单帧的状态。
     *
     * [id] 和 [retryMillis] 按协议是跨帧延续的连接级状态，所以不在这里重置。
     */
    private fun resetFrameScopedState() {
        dataLines.clear()
        event = null
        hasField = false
    }
}
