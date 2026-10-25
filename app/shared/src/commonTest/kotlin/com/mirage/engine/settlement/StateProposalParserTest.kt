package com.mirage.engine.settlement

import com.mirage.engine.state.StateValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 模型输出解析的测试。
 *
 * 样本形状来自真实观察：模型只有一半左右的概率直接返回干净的结构化数据，
 * 其余会包在代码块里、或者在前后加上说明文字。
 * 这些兜底路径一旦失效，表现是「这一轮的状态变化整个丢失」——
 * 而叙事内容照常显示，所以从界面上完全看不出哪里不对。
 */
class StateProposalParserTest {

    private fun success(raw: String): ProposalParseResult.Success {
        val result = StateProposalParser.parse(raw)
        assertIs<ProposalParseResult.Success>(result, "应当解析成功，实际是 $result")
        return result
    }

    private fun failure(raw: String): ProposalParseResult.Failure {
        val result = StateProposalParser.parse(raw)
        assertIs<ProposalParseResult.Failure>(result, "应当解析失败，实际是 $result")
        return result
    }

    // ------------------------------------------------------------------
    // 直接解析
    // ------------------------------------------------------------------

    @Test
    fun 干净的结构化输出应直接解析() {
        val result = success("""{"delta":{"甲/乙":49}}""")
        assertEquals(StateValue.IntValue(49), result.proposal.values["甲/乙"])
        assertFalse(result.recoveredFromWrapper, "输出本来就干净，不该记成用了兜底")
    }

    @Test
    fun 包装结构里的说明与概括应被取出() {
        val result = success(
            """{"summary":"打了一架","delta":{"甲":1},"reasons":{"甲":"被击中"}}""",
        )
        assertEquals("打了一架", result.proposal.summary)
        assertEquals("被击中", result.proposal.reasons["甲"])
    }

    @Test
    fun 没有包装时整个对象都算变化() {
        // 模型未必按我们期望的形状包一层，为此判失败不划算 ——
        // 代价是整轮状态变化丢失
        val result = success("""{"甲":1,"乙":"值"}""")
        assertEquals(2, result.proposal.values.size)
        assertEquals(StateValue.StringValue("值"), result.proposal.values["乙"])
    }

    @Test
    fun 没有包装时说明性字段不应被当成状态() {
        // 否则会凭空多出两个名叫概括和说明的状态字段
        val result = success("""{"甲":1,"summary":"概括","reasons":{"甲":"原因"}}""")
        assertEquals(setOf("甲"), result.proposal.values.keys)
        assertEquals("概括", result.proposal.summary)
        assertEquals("原因", result.proposal.reasons["甲"])
    }

    // ------------------------------------------------------------------
    // 兜底路径
    // ------------------------------------------------------------------

    @Test
    fun 包在带语言标注的代码块里应能取出() {
        val raw = """
            ```json
            {"delta":{"甲":7}}
            ```
        """.trimIndent()
        val result = success(raw)
        assertEquals(StateValue.IntValue(7), result.proposal.values["甲"])
        assertTrue(result.recoveredFromWrapper, "用了兜底就要如实记下来")
    }

    @Test
    fun 包在不带语言标注的代码块里应能取出() {
        val raw = """
            ```
            {"delta":{"甲":7}}
            ```
        """.trimIndent()
        assertEquals(StateValue.IntValue(7), success(raw).proposal.values["甲"])
    }

    @Test
    fun 前后带说明文字应能取出() {
        val raw = """好的，本轮变化如下：{"delta":{"甲":3}} 以上。"""
        val result = success(raw)
        assertEquals(StateValue.IntValue(3), result.proposal.values["甲"])
        assertTrue(result.recoveredFromWrapper)
    }

    @Test
    fun 代码块外还有说明文字时也应能取出() {
        val raw = """
            明白了，结算结果：
            ```json
            {"delta":{"甲":3}}
            ```
            如有问题请指出。
        """.trimIndent()
        assertEquals(StateValue.IntValue(3), success(raw).proposal.values["甲"])
    }

    @Test
    fun 只有开头围栏没有结尾时应退回按括号截取() {
        // 这种情况多半是输出被截断了，强行按围栏拼出来的内容是残缺的
        val raw = "```json\n{\"delta\":{\"甲\":5}}"
        assertEquals(StateValue.IntValue(5), success(raw).proposal.values["甲"])
    }

    @Test
    fun 嵌套结构应被展平成路径() {
        // 模型不知道我们内部用扁平路径，用嵌套表达层级是很自然的写法
        val result = success("""{"delta":{"冒险者":{"生命":49,"等级":2}}}""")
        assertEquals(StateValue.IntValue(49), result.proposal.values["冒险者/生命"])
        assertEquals(StateValue.IntValue(2), result.proposal.values["冒险者/等级"])
    }

    // ------------------------------------------------------------------
    // 值的映射
    // ------------------------------------------------------------------

    @Test
    fun 各种基本类型应映射正确() {
        val result = success(
            """{"delta":{"整":5,"小数":1.5,"文":"abc","真":true,"空":null}}""",
        )
        val values = result.proposal.values
        assertEquals(StateValue.IntValue(5), values["整"])
        assertEquals(StateValue.DoubleValue(1.5), values["小数"])
        assertEquals(StateValue.StringValue("abc"), values["文"])
        assertEquals(StateValue.BoolValue(true), values["真"])
        assertEquals(StateValue.NullValue, values["空"])
    }

    @Test
    fun 引号包起来的数字应保持为文本() {
        // 「写成文本的数字」和「数字」在后续的类型裁决里是两种不同情况，
        // 这里提前归一会让那一层失去判断依据
        val result = success("""{"delta":{"甲":"49"}}""")
        assertEquals(StateValue.StringValue("49"), result.proposal.values["甲"])
    }

    @Test
    fun 数组应逐项映射() {
        val result = success("""{"delta":{"物品":["刀","布"]}}""")
        assertEquals(
            StateValue.ArrayValue(
                listOf(StateValue.StringValue("刀"), StateValue.StringValue("布")),
            ),
            result.proposal.values["物品"],
        )
    }

    @Test
    fun 数组里有无法表达的元素时应整条放弃() {
        // 默默丢掉几个元素会让列表悄悄变短，这种缺失几乎不可能被及时发现
        val result = success("""{"delta":{"甲":1,"物品":["刀",{"嵌套":1}]}}""")
        assertEquals(setOf("甲"), result.proposal.values.keys, "整个数组应被放弃，而不是只留一半")
    }

    @Test
    fun 空对象不应产生任何字段() {
        val result = success("""{"delta":{"甲":1,"空对象":{}}}""")
        assertEquals(setOf("甲"), result.proposal.values.keys)
    }

    // ------------------------------------------------------------------
    // 失败
    // ------------------------------------------------------------------

    @Test
    fun 空输出应被识别为空响应() {
        assertEquals(ProposalParseFailure.EmptyResponse, failure("").code)
        assertEquals(ProposalParseFailure.EmptyResponse, failure("   \n  ").code)
    }

    @Test
    fun 纯叙述文字应被识别为没有结构化内容() {
        val result = failure("这一轮风平浪静，没有任何数值发生变化。")
        assertEquals(ProposalParseFailure.NoStructuredContent, result.code)
        assertTrue(result.preview.isNotEmpty(), "失败时要留下片段，否则无从排查")
    }

    @Test
    fun 坏掉的结构应与没有结构区分开() {
        // 「压根没返回结构化数据」和「返回了但是坏的」对排查的指向完全不同：
        // 前者多半是提示词问题，后者多半是输出被截断
        val result = failure("""{"delta":{"甲":""")
        assertEquals(ProposalParseFailure.MalformedStructure, result.code)
    }

    @Test
    fun 内容完整但取不出字段应判为形状不符() {
        // 和「结构坏掉」分开：这种情况说明输出是完整的，只是组织方式和预期不同，
        // 该去调提示词，而不是去怀疑输出被截断
        assertEquals(ProposalParseFailure.UnexpectedShape, failure("{}").code)
    }

    @Test
    fun 明确给出空变化应视为成功而不是失败() {
        // 🔴「本轮确实没变化」和「结算出问题了」对使用者的含义完全相反：
        // 前者是正常剧情，后者需要重算。把前者判成失败会抹掉这个区分，
        // 而这正是竞品做得不好、我们要补上的地方
        val result = success("""{"summary":"风平浪静","delta":{}}""")
        assertTrue(result.proposal.values.isEmpty())
        assertEquals("风平浪静", result.proposal.summary)
    }

    @Test
    fun 失败片段应被截断() {
        val huge = "不是结构化内容。".repeat(500)
        val result = failure(huge)
        assertTrue(result.preview.length < huge.length, "超长内容不该整段留着")
    }

    // ------------------------------------------------------------------
    // 兜底标记
    // ------------------------------------------------------------------

    @Test
    fun 兜底标记应如实反映是否绕过了直接解析() {
        // 这个标记用来统计兜底的触发频率。实测直接解析只有一半成功率，
        // 这个比例一旦变化，说明模型或提示词那边发生了我们没察觉的变化
        assertFalse(success("""{"甲":1}""").recoveredFromWrapper)
        assertTrue(success("""前言 {"甲":1} 后记""").recoveredFromWrapper)
    }
}
