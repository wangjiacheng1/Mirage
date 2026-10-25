package com.mirage.engine.settlement

import com.mirage.engine.state.StateValue
import com.mirage.engine.state.StateValueJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 从模型输出里提取状态提议。
 *
 * ## 为什么需要层层兜底
 *
 * 实测中，模型**只有一半左右的概率**会直接返回干净的结构化数据。
 * 另一半会把它包在代码块里，或者在前后加上「好的，以下是本轮变化」之类的说明。
 * 这不是提示词能彻底消除的 —— 收紧措辞反而让整体表现变差。
 * 所以正确做法是在解析侧容错，而不是指望输出侧完美。
 *
 * 尝试顺序是从严到宽，一旦某一层成功就不再继续：
 *
 * 1. 直接解析整段输出；
 * 2. 剥掉代码块围栏后再解析；
 * 3. 截取最外层的一对大括号再解析。
 *
 * 顺序不能颠倒：先用最宽松的方式，会在输出本来就干净时多做无谓的字符串处理，
 * 也更容易在特殊内容上截错边界。
 *
 * ## 为什么容忍多种形状
 *
 * 模型可能返回带包装的结构，也可能直接返回一个扁平映射，还可能用嵌套对象
 * 表达层级。这些都是同一个意思的不同写法，没有理由因为写法不同就判定失败 ——
 * 判定失败意味着这一轮的状态变化整个丢失，代价远大于多写几行兼容。
 *
 * 但**容忍写法不等于容忍内容**：取出来的东西一律只是「提议」，
 * 是否合法由后面的裁决环节判断，这里不做任何校验也不做任何猜测。
 */
object StateProposalParser {

    /** 排查片段的长度上限。 */
    private const val PREVIEW_LENGTH = 300

    /** 包装结构里存放变化的字段名。 */
    private const val FIELD_DELTA = "delta"
    private const val FIELD_REASONS = "reasons"
    private const val FIELD_SUMMARY = "summary"

    /** 路径分隔符。嵌套结构会被展平成用它连接的路径。 */
    private const val PATH_SEPARATOR = "/"

    private val json = Json { isLenient = true }

    fun parse(raw: String): ProposalParseResult {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return ProposalParseResult.Failure(ProposalParseFailure.EmptyResponse, "")
        }

        val candidates = buildCandidates(trimmed)
        // 两种失败要分开记：「看着像结构化数据但解析不了」通常是输出被截断，
        // 「解析成功但取不出字段」通常是提示词没把形状说清楚。
        // 混成一类会让排查从一开始就走错方向
        var sawMalformedJson = false
        var sawWrongShape = false

        candidates.forEachIndexed { index, candidate ->
            val root = runCatching { json.parseToJsonElement(candidate) }.getOrNull()
            if (root == null) {
                // 候选文本看起来像结构化数据却解析不了，记下来用于区分失败原因：
                // 「压根没有结构化内容」和「有但是坏的」对排查的指向完全不同
                if (candidate.startsWith("{")) sawMalformedJson = true
                return@forEachIndexed
            }
            if (root !is JsonObject) return@forEachIndexed

            val proposal = toProposal(root)
            if (proposal != null) {
                return ProposalParseResult.Success(
                    proposal = proposal,
                    // 第一个候选就是原文，用到后面的候选说明依赖了兜底
                    recoveredFromWrapper = index > 0,
                )
            }
            sawWrongShape = true
        }

        // 形状不符的信息量最大（说明内容是完整的、只是组织方式不对），优先采用
        val code = when {
            sawWrongShape -> ProposalParseFailure.UnexpectedShape
            sawMalformedJson -> ProposalParseFailure.MalformedStructure
            else -> ProposalParseFailure.NoStructuredContent
        }
        return ProposalParseResult.Failure(code, trimmed.take(PREVIEW_LENGTH))
    }

    /**
     * 按「从严到宽」生成候选文本。
     *
     * 去重是必要的：输出本来就干净时，三种处理会得到同一段文本，
     * 重复解析没有意义，还会让「是否用了兜底」的判断失真。
     */
    private fun buildCandidates(trimmed: String): List<String> = buildList {
        add(trimmed)
        stripCodeFence(trimmed)?.let(::add)
        extractOutermostObject(trimmed)?.let(::add)
    }.distinct()

    /**
     * 剥掉代码块围栏。
     *
     * 只处理成对出现的情况。只有开头没有结尾时不做处理 ——
     * 那说明输出被截断了，强行拼出来的内容多半是残缺的结构，
     * 交给后面按「最外层大括号」的方式处理更稳妥。
     */
    private fun stripCodeFence(text: String): String? {
        val fenceStart = text.indexOf(FENCE)
        if (fenceStart < 0) return null

        val afterFence = fenceStart + FENCE.length
        // 围栏后面可能跟着语言标注，要跳到那一行的末尾
        val contentStart = text.indexOf('\n', afterFence).takeIf { it >= 0 }?.plus(1) ?: return null
        val fenceEnd = text.indexOf(FENCE, contentStart)
        if (fenceEnd < 0) return null

        return text.substring(contentStart, fenceEnd).trim().takeIf { it.isNotEmpty() }
    }

    /**
     * 截取最外层的一对大括号。
     *
     * 用于处理模型在结构化数据前后添上说明文字的情况。
     * 取最外层而不是第一个闭合的括号，是因为内容本身就是嵌套结构，
     * 取第一个闭合括号只会拿到残缺的一小段。
     */
    private fun extractOutermostObject(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return text.substring(start, end + 1)
    }

    /**
     * 把结构化内容整理成提议。
     *
     * @return 取不出任何字段时返回 null，让调用方继续尝试下一个候选。
     */
    private fun toProposal(root: JsonObject): StateProposal? {
        // 带包装的写法优先；没有包装则把整个对象当作变化本身
        val deltaNode = root[FIELD_DELTA] as? JsonObject
        val wrapped = deltaNode != null
        val source = deltaNode ?: root

        val values = mutableMapOf<String, StateValue>()
        source.forEach { (key, element) ->
            // 不带包装时，说明性字段不属于状态变化，要排除掉，
            // 否则它们会被当成两个凭空出现的状态字段
            if (!wrapped && (key == FIELD_REASONS || key == FIELD_SUMMARY)) return@forEach
            flattenInto(prefix = key, element = element, sink = values)
        }
        // 🔴 明确给出了存放变化的字段时，空集合是**合法结果**，不是失败：
        // 它表示「本轮确实没有任何数值发生变化」。
        // 判成失败会抹掉一个关键区分 ——「确实没变」和「结算出问题了」
        // 对使用者的含义完全相反，前者是正常剧情，后者需要重算。
        //
        // 反过来，没有这个字段、整个内容又是空的，就无从分辨这两种情况，
        // 只能当作形状不符交给上层处理。
        if (values.isEmpty() && !wrapped) return null

        return StateProposal(
            values = values,
            reasons = readReasons(root),
            summary = (root[FIELD_SUMMARY] as? JsonPrimitive)
                ?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() },
        )
    }

    private fun readReasons(root: JsonObject): Map<String, String> {
        val node = root[FIELD_REASONS] as? JsonObject ?: return emptyMap()
        val result = mutableMapOf<String, String>()
        node.forEach { (key, element) ->
            (element as? JsonPrimitive)?.takeIf { it.isString }?.let { result[key] = it.content }
        }
        return result
    }

    /**
     * 把一个节点展平成「路径 → 值」写入结果。
     *
     * 嵌套对象会被展开成用分隔符连接的路径。模型用嵌套结构表达层级是很自然的写法，
     * 因为它不知道我们内部用扁平路径。为此判定失败不划算 —— 代价是整轮状态变化丢失。
     *
     * 无法表达为状态值的内容（例如对象套在数组里）会被整体跳过而不是部分保留：
     * 部分保留会产生一个看似成功、实则缺了几项的结果，比明确缺失更难发现。
     */
    private fun flattenInto(prefix: String, element: JsonElement, sink: MutableMap<String, StateValue>) {
        if (element is JsonObject) {
            if (element.isEmpty()) return
            element.forEach { (key, child) ->
                flattenInto(prefix = prefix + PATH_SEPARATOR + key, element = child, sink = sink)
            }
            return
        }
        StateValueJson.decode(element)?.let { sink[prefix] = it }
    }

    private const val FENCE = "```"
}
