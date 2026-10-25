package com.mirage.engine.settlement

import com.mirage.engine.llm.ChatRole
import com.mirage.engine.llm.findDefects
import com.mirage.engine.state.FieldSpec
import com.mirage.engine.state.FieldType
import com.mirage.engine.state.Monotonic
import com.mirage.engine.state.StateSchema
import com.mirage.engine.state.StateValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 结算输入构造的测试。
 *
 * 这里有两类断言，后一类更重要：
 * 一是「该出现的内容在不在」，二是**「不该出现的约束确实没有出现」**。
 * 后者容易被忽略，但它守的是一条刻意的设计选择 —— 约束由代码裁决，
 * 不写进提示词。一旦有人出于好意把上下限补进提示词，正确率反而会下降，
 * 而这种退化在功能上完全看不出来。
 */
class SettlementPromptBuilderTest {

    private val schema = StateSchema(
        fields = mapOf(
            "甲/乙" to FieldSpec(
                type = FieldType.Int,
                min = 0.0,
                max = 60.0,
                maxDeltaPerTurn = 20.0,
                monotonic = Monotonic.Increasing,
            ),
            "甲/丙" to FieldSpec(
                type = FieldType.String,
                enumValues = setOf("选项一", "选项二"),
            ),
            "丁" to FieldSpec(type = FieldType.Array, elementType = FieldType.String),
            "系统/计数" to FieldSpec(type = FieldType.Int, readOnly = true),
        ),
    )

    private val currentState = mapOf(
        "甲/乙" to StateValue.IntValue(49),
        "甲/丙" to StateValue.StringValue("选项一"),
        "丁" to StateValue.ArrayValue(listOf(StateValue.StringValue("某物"))),
        "系统/计数" to StateValue.IntValue(3),
        "未提到的" to StateValue.Absent,
    )

    private fun systemText(): String =
        SettlementPromptBuilder.buildMessages("某段剧情", currentState, schema)
            .first { it.role == ChatRole.System }
            .content

    // ------------------------------------------------------------------
    // 消息结构
    // ------------------------------------------------------------------

    @Test
    fun 应产出系统与用户两条消息() {
        val messages = SettlementPromptBuilder.buildMessages("某段剧情", currentState, schema)
        assertEquals(2, messages.size)
        assertEquals(ChatRole.System, messages[0].role)
        assertEquals(ChatRole.User, messages[1].role)
    }

    @Test
    fun 叙事应单独放在后一条消息里() {
        // 指令每轮一样、叙事每轮不同，分开能让服务商的前缀缓存生效，
        // 调试时也能一眼看出这轮喂进去的是哪一段
        val messages = SettlementPromptBuilder.buildMessages("狼扑了上来", currentState, schema)
        assertEquals("狼扑了上来", messages[1].content)
        assertFalse(messages[0].content.contains("狼扑了上来"))
    }

    // ------------------------------------------------------------------
    // 可修改字段清单
    // ------------------------------------------------------------------

    @Test
    fun 可修改字段应被列出() {
        val text = systemText()
        assertTrue(text.contains("甲/乙"))
        assertTrue(text.contains("甲/丙"))
        assertTrue(text.contains("丁"))
    }

    @Test
    fun 只读字段不应出现在可修改清单里() {
        // 实测中模型在被明确要求不要改这类字段时依然会去改，
        // 所以更有效的办法是根本不告诉它这些字段可以改
        val editableSection = systemText().substringAfter("可修改的字段：").substringBefore("当前数值：")
        assertFalse(editableSection.contains("系统/计数"), "只读字段不该出现在可改清单")
    }

    @Test
    fun 只读字段的当前值仍应作为上下文给出() {
        // 看不到它的当前值，模型就无法理解剧情处在什么阶段
        val stateSection = systemText().substringAfter("当前数值：")
        assertTrue(stateSection.contains("系统/计数"))
    }

    @Test
    fun 枚举可选值应列出() {
        // 可选值属于「该填什么」的信息，不是约束，列出来能明显减少无效提议
        assertTrue(systemText().contains("选项一"))
        assertTrue(systemText().contains("选项二"))
    }

    @Test
    fun 类型应以自然语言描述() {
        val text = systemText()
        assertTrue(text.contains("整数"))
        assertTrue(text.contains("文本"))
        assertTrue(text.contains("列表"))
    }

    @Test
    fun 没有可改字段时不应留下空白清单() {
        val text = SettlementPromptBuilder
            .buildMessages("剧情", emptyMap(), StateSchema(fields = emptyMap()))
            .first().content
        val section = text.substringAfter("可修改的字段：").substringBefore("当前数值：")
        assertTrue(section.isNotBlank(), "空白清单会让模型困惑，应给出明确说明")
    }

    // ------------------------------------------------------------------
    // 🔴 不该出现的约束
    // ------------------------------------------------------------------

    @Test
    fun 不应把取值范围写进提示词() {
        // 约束由裁决环节执行。写进提示词既不会提高遵守率，
        // 又会让提示词变长、增加模型顾此失彼的机会 —— 实测正确率反而下降
        val text = systemText()
        assertFalse(text.contains("60"), "上限不该出现在提示词里")
        assertFalse(text.contains("最大"), "不该描述上限")
        assertFalse(text.contains("最小"), "不该描述下限")
    }

    @Test
    fun 不应把单轮幅度与单调方向写进提示词() {
        val text = systemText()
        assertFalse(text.contains("20"), "单轮幅度上限不该出现")
        assertFalse(text.contains("只能增加"))
        assertFalse(text.contains("递增"))
    }

    // ------------------------------------------------------------------
    // 状态快照
    // ------------------------------------------------------------------

    @Test
    fun 当前状态应以结构化形式给出() {
        // 用和期望输出一致的结构表达输入，模型更容易照着格式回答
        val stateSection = systemText().substringAfter("当前数值：").trim()
        assertTrue(stateSection.startsWith("{"))
        assertTrue(stateSection.contains("\"甲/乙\":49"))
    }

    @Test
    fun 没有被提到的值不应出现在快照里() {
        // 它不代表任何具体取值。写成空会让模型以为这个字段当前是空的，
        // 进而可能去「修正」一个本来就不存在的问题
        assertFalse(systemText().contains("未提到的"))
    }

    @Test
    fun 快照应能被解析回原值() {
        // 两个方向必须互为逆运算，否则会出现「发出去的和读回来的对不上」，
        // 表现是某个字段每轮都被判定为发生了变化，极难定位
        val stateSection = systemText().substringAfter("当前数值：").trim()
        val parsed = StateProposalParser.parse(stateSection)
        val success = parsed as? ProposalParseResult.Success
        assertTrue(success != null, "快照本身应是合法的结构化内容，实际：$parsed")
        assertEquals(StateValue.IntValue(49), success.proposal.values["甲/乙"])
        assertEquals(StateValue.StringValue("选项一"), success.proposal.values["甲/丙"])
    }

    // ------------------------------------------------------------------
    // 请求参数
    // ------------------------------------------------------------------

    @Test
    fun 结算请求应关闭思考并设定长度上限() {
        // 这是整条链路上唯一可以安全设上限的调用：输出是结构化对象、规模可预期。
        // 但上限和思考共享额度，所以必须先关思考，否则上限可能被思考吃光、正文一字不剩
        val request = SettlementPromptBuilder.buildRequest(
            modelId = "m",
            narrative = "剧情",
            currentState = currentState,
            schema = schema,
        )
        assertFalse(request.reasoningEnabled, "结算不需要推理过程，而思考的开销数倍于正文")
        assertEquals(SettlementPromptBuilder.DEFAULT_MAX_OUTPUT_TOKENS, request.maxOutputTokens)
        assertEquals("m", request.modelId)
    }

    @Test
    fun 结算请求应通过发送前自检() {
        val request = SettlementPromptBuilder.buildRequest(
            modelId = "m",
            narrative = "剧情",
            currentState = currentState,
            schema = schema,
        )
        assertEquals(emptyList(), request.findDefects(), "组装出来的请求本身不该有问题")
    }

    @Test
    fun 指令应可被整套替换() {
        // 不同玩法对「什么算变化」的判断尺度不同，这应当是可配置的
        val text = SettlementPromptBuilder
            .buildMessages("剧情", currentState, schema, instruction = "自定义指令内容")
            .first().content
        assertTrue(text.startsWith("自定义指令内容"))
        assertFalse(text.contains("数值结算器"))
    }
}

/**
 * 卡自带字段说明的处理。
 *
 * 单独一组用例，因为这里刚出过一次真实事故：卡里的规则被直接当成了结算指令，
 * 模型照着那套框架的提交方式输出，我们一个字段都没认出来，整轮结算作废。
 */
class SettlementPromptFieldNotesTest {

    private val schema = StateSchema(
        fields = mapOf("某组/甲" to FieldSpec(type = FieldType.Int)),
    )

    private fun context(fieldNotes: String?): String =
        SettlementPromptBuilder.buildMessages(
            narrative = "这一轮发生的事",
            currentState = mapOf("某组/甲" to StateValue.IntValue(1)),
            schema = schema,
            fieldNotes = fieldNotes,
        ).first().content

    @Test
    fun 卡里的字段说明会被带上() {
        val text = context("甲：受击时减少")

        assertTrue(text.contains("甲：受击时减少"))
    }

    @Test
    fun 带上字段说明时必须声明不采用它的提交方式() {
        val text = context("用某种工具提交，路径相对于某个容器")

        // 不声明的话，模型会按卡里描述的那套格式输出，我们这边认不出来
        assertTrue(text.contains("不要采用它提到的提交方式"))
    }

    @Test
    fun 字段说明之后要把输出格式再强调一遍() {
        val text = context("一段很长的字段说明")

        val notesAt = text.indexOf("一段很长的字段说明")
        val reminderAt = text.indexOf("再次强调输出格式")
        assertTrue(reminderAt > notesAt, "格式要求必须排在参考内容之后，才离输出最近")
    }

    @Test
    fun 没有字段说明时不出现多余的段落() {
        val text = context(null)

        assertTrue(!text.contains("再次强调输出格式"))
    }
}
