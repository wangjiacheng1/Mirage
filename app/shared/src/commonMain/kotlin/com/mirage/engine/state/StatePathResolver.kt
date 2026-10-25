package com.mirage.engine.state

/**
 * 一次路径归一的结果。
 *
 * @property values 归一之后的提议。键一律是我们认识的路径。
 * @property remapped 哪些键被改写过（原写法 → 归一后的路径）。
 *   留着它是为了能看出「模型其实在用另一套写法」，这是提示词出问题的早期信号。
 * @property unresolved 怎么试都对不上的键。它们原样留在 [values] 里，
 *   后面的检查会把它们挡掉 —— 这里只负责认领，不负责裁决。
 */
data class PathResolution(
    val values: Map<String, StateValue>,
    val remapped: Map<String, String> = emptyMap(),
    val unresolved: List<String> = emptyList(),
)

/**
 * ============================================================================
 * StatePathResolver —— 把模型写的路径对到我们的路径上
 * ============================================================================
 *
 * ## 为什么需要这一步
 *
 * 我们内部用斜杠分隔路径（`某组/某项`），但模型并不总是照着写：
 * 带状态玩法的卡在别的环境里普遍用点号（`某组.某项`），而且常常还带一层
 * 容器前缀。模型见过的大量例子都是那种写法，于是哪怕提示词里写的是斜杠，
 * 它也会时不时写回点号。
 *
 * 这种情况下，提议的内容其实完全正确，只是写法不同。
 * 直接按「这一项不存在」挡掉，整轮结算就全废了 —— 实测中就是这样：
 * 一整轮的数值变化因为写法不一致而全部丢失，用户只看到「结算失败」。
 *
 * ## 原则：只认写法差异，不猜字段
 *
 * 只做几种机械的改写（换分隔符、去掉容器前缀、去掉首尾空白），
 * 改写完必须**精确命中**字段清单里已有的路径才算数。
 * 绝不做模糊匹配、绝不按相似度挑一个最像的 —— 那等于替模型决定它想改哪一项，
 * 一旦挑错，数值会悄悄写到另一个字段上，而这种错几乎不可能被发现。
 */
object StatePathResolver {

    /** 别家框架常见的容器前缀。模型会连着这层一起写出来。 */
    private val CONTAINER_PREFIXES = listOf("stat_data", "statdata", "state", "变量")

    fun resolve(proposals: Map<String, StateValue>, schema: StateSchema): PathResolution {
        val values = mutableMapOf<String, StateValue>()
        val remapped = mutableMapOf<String, String>()
        val unresolved = mutableListOf<String>()

        proposals.forEach { (raw, value) ->
            if (schema.knownPaths.contains(raw)) {
                values[raw] = value
                return@forEach
            }
            val matched = candidatesOf(raw).firstOrNull { schema.knownPaths.contains(it) }
            if (matched != null) {
                // 同一项被两种写法各提一次时，保留先到的那个，并把后来的记成对不上。
                // 后到的覆盖先到的话，最终用哪个值将取决于遍历顺序，那是不可复现的
                if (values.containsKey(matched)) {
                    unresolved += raw
                } else {
                    values[matched] = value
                    remapped[raw] = matched
                }
            } else {
                values[raw] = value
                unresolved += raw
            }
        }
        return PathResolution(values, remapped, unresolved)
    }

    /**
     * 列出这个写法可能对应的几种路径。
     *
     * 顺序就是尝试顺序：先试最保守的改动（只换分隔符），再试去掉容器前缀。
     * 改动越小越可信，所以小的排前面。
     */
    private fun candidatesOf(raw: String): List<String> {
        val trimmed = raw.trim().trim('/', '.')
        if (trimmed.isEmpty()) return emptyList()

        val slashed = trimmed.replace('.', '/')
        val candidates = mutableListOf(trimmed, slashed)

        CONTAINER_PREFIXES.forEach { prefix ->
            listOf(trimmed, slashed).forEach { form ->
                if (form.startsWith("$prefix/", ignoreCase = true)) {
                    candidates += form.substring(prefix.length + 1)
                }
            }
        }
        return candidates.distinct().filter { it.isNotEmpty() }
    }
}
