package com.mirage.engine.settlement

import com.mirage.engine.llm.ChatCompletionClient
import com.mirage.engine.llm.ProviderProfile
import com.mirage.engine.llm.ReasoningControl
import com.mirage.engine.llm.StreamErrorCode
import com.mirage.engine.net.HttpStreamEvent
import com.mirage.engine.net.ScriptedHttpStreamTransport
import com.mirage.engine.net.TransportFailureKind
import com.mirage.engine.state.FieldSpec
import com.mirage.engine.state.FieldType
import com.mirage.engine.state.GuardFailureCode
import com.mirage.engine.state.SettlementStatus
import com.mirage.engine.state.StateSchema
import com.mirage.engine.state.StateValue
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 结算全链路的测试。
 *
 * 验的是这条链：叙事 → 构造请求 → 调用 → 解析 → **代码裁决** → 回执。
 * 其中只有「调用」一步的结果不可信，其余都是确定性的，
 * 所以整条链都能用一个假传输离线跑完 —— 包括那些真机上极难复现的失败时序。
 *
 * 最关键的一组断言是「模型提议 ≠ 最终数值」：模型说什么不重要，
 * 代码认什么才重要。这组断言失效意味着护城河被绕过去了。
 */
class SettlementCoordinatorTest {

    private val schema = StateSchema(
        fields = mapOf(
            // 有上限的数值，用来验证超限会被夹取
            "甲/乙" to FieldSpec(type = FieldType.Int, min = 0.0, max = 60.0),
            "甲/丙" to FieldSpec(type = FieldType.String),
            // 系统维护的字段，模型不该改
            "系统/计数" to FieldSpec(type = FieldType.Int, readOnly = true),
        ),
    )

    private val snapshot = mapOf(
        "甲/乙" to StateValue.IntValue(49),
        "甲/丙" to StateValue.StringValue("原值"),
        "系统/计数" to StateValue.IntValue(3),
    )

    private val profile = ProviderProfile(
        id = "p",
        name = "P",
        baseUrl = "https://example.com/v1",
        reasoningControl = ReasoningControl.ThinkingTypeObject,
    )

    private fun task(turn: Int = 4, revision: Long = 7) = SettlementTask(
        turn = turn,
        basedOnRevision = revision,
        narrative = "狼咬了一口，随后被击退。",
        stateSnapshot = snapshot,
        schema = schema,
    )

    /** 让模型「返回」一段指定正文。 */
    private fun replying(vararg chunks: String): List<HttpStreamEvent> =
        ScriptedHttpStreamTransport.completeStream(
            *chunks.map { """{"choices":[{"delta":{"content":${quote(it)}}}]}""" }.toTypedArray(),
        )

    /** 把一段文本转成 JSON 字符串字面量。 */
    private fun quote(text: String): String =
        "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private suspend fun settle(
        script: List<HttpStreamEvent>,
        turn: Int = 4,
        revision: Long = 7,
    ): Pair<SettlementOutcome, ScriptedHttpStreamTransport> {
        val transport = ScriptedHttpStreamTransport(script)
        val outcome = SettlementCoordinator(ChatCompletionClient(transport)).settle(
            profile = profile,
            credential = "secret",
            modelId = "cheap-model",
            task = task(turn, revision),
        )
        return outcome to transport
    }

    // ------------------------------------------------------------------
    // 发出去的请求
    // ------------------------------------------------------------------

    @Test
    fun 结算应使用单独指定的模型() = runTest {
        // 结算是一次格式明确的归纳工作，用更便宜的模型就够，
        // 实测可以把这部分开销降到三分之一
        val (_, transport) = settle(replying("""{"delta":{}}"""))
        assertTrue(assertNotNull(transport.received).body.contains("\"model\":\"cheap-model\""))
    }

    @Test
    fun 结算请求应关闭思考() = runTest {
        // 不关的话额度会被推理过程吃掉，而结算的长度上限本来就卡得很紧
        val (_, transport) = settle(replying("""{"delta":{}}"""))
        val body = assertNotNull(transport.received).body
        assertTrue(body.contains("\"thinking\""))
        assertTrue(body.contains("disabled"))
    }

    @Test
    fun 叙事内容应被送进结算请求() = runTest {
        val (_, transport) = settle(replying("""{"delta":{}}"""))
        assertTrue(assertNotNull(transport.received).body.contains("狼咬了一口"))
    }

    // ------------------------------------------------------------------
    // 🔴 模型提议 ≠ 最终数值
    // ------------------------------------------------------------------

    @Test
    fun 合法提议应原样落下() = runTest {
        val (outcome, _) = settle(replying("""{"summary":"受伤","delta":{"甲/乙":38}}"""))
        assertEquals(SettlementStatus.Updated, outcome.receipt.status)
        val change = outcome.receipt.changes.single()
        assertEquals("甲/乙", change.path)
        assertEquals(StateValue.IntValue(49), change.before)
        assertEquals(StateValue.IntValue(38), change.after)
        assertEquals("受伤", outcome.receipt.summary)
    }

    @Test
    fun 超出上限的提议应被夹取而不是照单全收() = runTest {
        // 模型提议 80，上限是 60 → 实际落下 60，并留一条「动过手脚」的记录。
        // 这是整套设计的落点：模型说什么不重要，代码认什么才重要
        val (outcome, _) = settle(replying("""{"delta":{"甲/乙":80}}"""))
        val change = outcome.receipt.changes.single()
        assertEquals(StateValue.IntValue(60), change.after, "必须按上限夹取，不能照抄模型给的 80")
        assertEquals(GuardFailureCode.Clamped, change.adjustedBy)
        assertTrue(
            outcome.receipt.failures.any { it.code == GuardFailureCode.Clamped },
            "被修正的事实必须留痕，否则使用者无法理解数值为何和剧情对不上",
        )
    }

    @Test
    fun 改系统字段的提议应被拒绝() = runTest {
        // 实测中模型在被明确要求不要改这类字段时依然会去改，
        // 所以必须由代码兜住，而不是指望它守规矩
        val (outcome, _) = settle(replying("""{"delta":{"系统/计数":99}}"""))
        assertTrue(
            outcome.receipt.changes.none { it.path == "系统/计数" },
            "系统维护的字段不接受来自模型的修改",
        )
        // 唯一一条提议被否决、一条变化都没落下 → 整轮判为出错。
        // 这和「部分成功」不同：后者至少有东西落下了（见下一条用例）
        assertEquals(SettlementStatus.Error, outcome.receipt.status)
    }

    @Test
    fun 不存在的路径应被拒绝而不是凭空新建() = runTest {
        val (outcome, _) = settle(replying("""{"delta":{"凭空/冒出来":1}}"""))
        assertTrue(outcome.receipt.changes.isEmpty())
        assertEquals(SettlementStatus.Error, outcome.receipt.status)
    }

    @Test
    fun 部分合法时合法的应落下不合法的应留痕() = runTest {
        val (outcome, _) = settle(
            replying("""{"delta":{"甲/丙":"新值","系统/计数":99}}"""),
        )
        assertEquals(StateValue.StringValue("新值"), outcome.receipt.changes.single().after)
        assertTrue(outcome.receipt.failures.isNotEmpty())
        assertEquals(SettlementStatus.Partial, outcome.receipt.status)
    }

    // ------------------------------------------------------------------
    // 无变化 vs 出错
    // ------------------------------------------------------------------

    @Test
    fun 模型明确表示无变化应判为无变化而不是出错() = runTest {
        // 🔴「确实没变」和「结算出问题了」对使用者的含义完全相反：
        // 前者是正常剧情，后者需要重算
        val (outcome, _) = settle(replying("""{"summary":"风平浪静","delta":{}}"""))
        assertEquals(SettlementStatus.Unchanged, outcome.receipt.status)
        assertTrue(outcome.receipt.changes.isEmpty())
        assertFalse(outcome.hasApplicableChanges)
    }

    // ------------------------------------------------------------------
    // 失败
    // ------------------------------------------------------------------

    @Test
    fun 调用失败应判为出错且不产生任何变化() = runTest {
        val (outcome, _) = settle(
            listOf(HttpStreamEvent.HttpFailure(401, """{"error":{"message":"bad key"}}""")),
        )
        assertEquals(SettlementStatus.Error, outcome.receipt.status)
        assertTrue(outcome.receipt.changes.isEmpty())
        assertEquals(StreamErrorCode.Unauthorized, outcome.diagnostics.streamError?.code)
    }

    @Test
    fun 超时应被如实归类() = runTest {
        val (outcome, _) = settle(
            listOf(HttpStreamEvent.TransportFailure(TransportFailureKind.IdleTimeout)),
        )
        assertEquals(SettlementStatus.Error, outcome.receipt.status)
        assertEquals(StreamErrorCode.IdleTimeout, outcome.diagnostics.streamError?.code)
    }

    @Test
    fun 输出无法解析应记下失败类别() = runTest {
        val (outcome, _) = settle(replying("这一轮我觉得没什么好说的。"))
        assertEquals(SettlementStatus.Error, outcome.receipt.status)
        assertEquals(
            ProposalParseFailure.NoStructuredContent,
            outcome.diagnostics.parseFailure,
        )
    }

    @Test
    fun 失败时不应把原因塞进裁决拦截列表() = runTest {
        // 那个列表记的是「裁决环节拦下了什么」。调用失败和解析失败
        // 发生在裁决之前，根本还没走到那一步，混进去会让两类问题无法区分
        val (outcome, _) = settle(replying("不是结构化内容"))
        assertEquals(emptyList(), outcome.receipt.failures)
        assertNotNull(outcome.diagnostics.parseFailure)
    }

    // ------------------------------------------------------------------
    // 兜底与留痕
    // ------------------------------------------------------------------

    @Test
    fun 包在代码块里的输出应能被取出并记下用了兜底() = runTest {
        val (outcome, _) = settle(replying("```json\n{\"delta\":{\"甲/乙\":38}}\n```"))
        assertEquals(SettlementStatus.Updated, outcome.receipt.status)
        assertTrue(outcome.diagnostics.recoveredFromWrapper, "兜底触发频率需要可统计")
    }

    @Test
    fun 分片到达的输出应被完整拼接() = runTest {
        // 结算输出同样是分片返回的，少拼一片就会变成解析失败
        val (outcome, _) = settle(replying("""{"delta":""", """{"甲/乙":38}}"""))
        assertEquals(SettlementStatus.Updated, outcome.receipt.status)
    }

    @Test
    fun 成功时也应留下原始输出() = runTest {
        // 它是把「模型说了什么」和「代码最终认了什么」对照起来看的唯一依据
        val (outcome, _) = settle(replying("""{"delta":{"甲/乙":80}}"""))
        assertTrue(outcome.diagnostics.rawOutput?.contains("80") == true)
    }

    @Test
    fun 思考过程不应混进待解析的内容() = runTest {
        // 即使显式关闭了思考，也要防着服务方不理这个参数。
        // 一段推理混进去，解析必然失败
        val script = ScriptedHttpStreamTransport.completeStream(
            """{"choices":[{"delta":{"reasoning_content":"让我想想血量应该是多少"}}]}""",
            """{"choices":[{"delta":{"content":"{\"delta\":{\"甲/乙\":38}}"}}]}""",
        )
        val transport = ScriptedHttpStreamTransport(script)
        val outcome = SettlementCoordinator(ChatCompletionClient(transport)).settle(
            profile = profile,
            credential = "secret",
            modelId = "m",
            task = task(),
        )
        assertEquals(SettlementStatus.Updated, outcome.receipt.status)
        assertFalse(outcome.diagnostics.rawOutput?.contains("让我想想") == true)
    }

    // ------------------------------------------------------------------
    // 轮次与版本
    // ------------------------------------------------------------------

    @Test
    fun 结果应带回轮次与所基于的版本() = runTest {
        // 结算是异步的，回来时必须能认出「这是哪一轮、基于哪个版本算的」
        val (outcome, _) = settle(replying("""{"delta":{}}"""), turn = 9, revision = 21)
        assertEquals(9, outcome.turn)
        assertEquals(21, outcome.basedOnRevision)
        assertEquals(9, outcome.receipt.turn)
    }

    @Test
    fun 标记过期应清空变化但保留回执() = runTest {
        // 变化是基于一份已经不成立的前提算出来的，部分采纳比全部丢弃更危险。
        // 但回执要留着 —— 使用者有权知道这一轮白跑了一次，而且是花了钱的
        val (outcome, _) = settle(replying("""{"delta":{"甲/乙":38}}"""))
        val stale = outcome.markStale()
        assertEquals(SettlementStatus.Stale, stale.receipt.status)
        assertTrue(stale.receipt.changes.isEmpty())
        assertEquals(outcome.turn, stale.receipt.turn)
    }

    @Test
    fun 标记中断应与过期区分开() = runTest {
        // 过期是「算完了但没用上」，中断是「压根没算完」。
        // 只有后者值得提示可以重新结算
        val (outcome, _) = settle(replying("""{"delta":{"甲/乙":38}}"""))
        assertEquals(SettlementStatus.Interrupted, outcome.markInterrupted().receipt.status)
        assertEquals(SettlementStatus.Stale, outcome.markStale().receipt.status)
    }

    @Test
    fun 结算用量应被单独记账() = runTest {
        // 这是一次使用者看不见的额外调用，看不见的开销更需要如实记账，
        // 否则账单上会出现无法解释的数字
        val script = ScriptedHttpStreamTransport.lines(
            """data: {"choices":[{"delta":{"content":"{\"delta\":{}}"}}]}""", "",
            """data: {"choices":[{"delta":{}}],"usage":{"prompt_tokens":120,"completion_tokens":30}}""", "",
            "data: [DONE]", "",
        )
        val transport = ScriptedHttpStreamTransport(script)
        val outcome = SettlementCoordinator(ChatCompletionClient(transport)).settle(
            profile = profile, credential = "k", modelId = "m", task = task(),
        )
        assertEquals(120, outcome.usage.promptTokens)
        assertEquals(150, outcome.usage.totalTokens)
        assertNull(outcome.usage.cost, "服务方没给费用就该是空，不能填 0 冒充")
    }
}
