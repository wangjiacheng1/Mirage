package com.mirage.engine.settlement

import com.mirage.engine.llm.ChatCompletionClient
import com.mirage.engine.llm.ChatStreamEvent
import com.mirage.engine.llm.ProviderProfile
import com.mirage.engine.llm.StreamError
import com.mirage.engine.llm.TokenUsage
import com.mirage.engine.state.SettlementReceipt
import com.mirage.engine.state.SettlementStatus
import com.mirage.engine.state.StateGuards
import com.mirage.engine.state.StatePathResolver
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * 把一次结算从头跑到尾。
 *
 * ## 这条链路
 *
 * ```
 * 叙事内容 ──► 构造结算请求 ──► 调用模型 ──► 解析输出 ──► 代码裁决 ──► 回执
 * ```
 *
 * 其中**只有「调用模型」这一步的结果是不可信的**，其余每一步都是确定性的。
 * 这个分界是整套设计的核心：模型的输出只被当作「提议」，
 * 它要不要生效、生效成什么样，由裁决环节说了算。
 *
 * ## 为什么复用叙事那一套调用链
 *
 * 结算完全可以用非流式请求完成，但那需要再写一条读取整个响应体的通道。
 * 复用已有的流式通道，只是把分片内容攒起来再一次性处理，代价可以忽略，
 * 却省掉了一整条需要独立测试和维护的路径。
 * 顺带还白拿了超时判定和错误归类。
 *
 * ## 关于思考过程
 *
 * 结算请求会显式关闭思考，但**仍然要防着服务方不理这个参数**。
 * 这里只累积正文，思考内容即使返回了也不会进入待解析的文本 ——
 * 否则一段推理过程混进去，解析必然失败。
 */
class SettlementCoordinator(
    private val chatClient: ChatCompletionClient,
) {

    /**
     * 执行一次结算。
     *
     * **不处理取消。** 协程被取消时异常会原样向上传播，由调用方决定如何记录
     * （通常是把任务标为被中断）。在这里吞掉取消会让上层无法区分
     * 「算完了」和「被打断了」。
     *
     * @param modelId 结算用的模型。刻意和叙事分开传 —— 结算是一次格式明确的
     *   归纳工作，用更便宜的模型就足够，实测可以把这部分开销降到三分之一。
     */
    suspend fun settle(
        profile: ProviderProfile,
        credential: String,
        modelId: String,
        task: SettlementTask,
        instruction: String = SettlementPromptBuilder.DEFAULT_INSTRUCTION,
        fieldNotes: String? = null,
        idleTimeout: Duration = DEFAULT_IDLE_TIMEOUT,
    ): SettlementOutcome {
        val request = SettlementPromptBuilder.buildRequest(
            modelId = modelId,
            narrative = task.narrative,
            currentState = task.stateSnapshot,
            schema = task.schema,
            instruction = instruction,
            fieldNotes = fieldNotes,
        )

        val collected = StringBuilder()
        var usage = TokenUsage()
        var streamError: StreamError? = null

        chatClient.stream(
            profile = profile,
            credential = credential,
            request = request,
            idleTimeout = idleTimeout,
        ).collect { event ->
            when (event) {
                // 只收正文。思考过程即使返回了也要挡在外面，混进去解析必然失败
                is ChatStreamEvent.TextDelta -> collected.append(event.text)

                is ChatStreamEvent.Completed -> usage = event.usage

                // 只记第一个错误。后续错误往往是同一个问题的连带表现，
                // 用后来的覆盖掉最初的那个会让排查从根因偏开
                is ChatStreamEvent.Failed -> if (streamError == null) streamError = event.error

                else -> Unit
            }
        }

        val raw = collected.toString()

        streamError?.let { error ->
            return failed(task, usage, SettlementDiagnostics(rawOutput = preview(raw), streamError = error))
        }

        return when (val parsed = StateProposalParser.parse(raw)) {
            is ProposalParseResult.Failure -> failed(
                task = task,
                usage = usage,
                diagnostics = SettlementDiagnostics(
                    rawOutput = preview(raw),
                    parseFailure = parsed.code,
                ),
            )

            is ProposalParseResult.Success -> adjudicate(task, usage, parsed, raw)
        }
    }

    /**
     * 把提议交给裁决环节，得到回执。
     *
     * 这一步是整套设计的落点：模型提了什么不重要，**代码认什么才重要**。
     * 回执里会同时包含「实际落下的变化」和「被拦下或被修正的项」，
     * 两者都要留痕 —— 只留结果不留拦截记录，使用者就无法理解
     * 为什么剧情说加了 20 点而数值只加了 11。
     *
     * 提议为空（模型明确表示本轮没有变化）同样走这条路，
     * 裁决环节会自然得出「无变化」，不需要在这里特殊处理。
     * 这正是「确实没变」和「结算出错」能被区分开的地方。
     */
    private fun adjudicate(
        task: SettlementTask,
        usage: TokenUsage,
        parsed: ProposalParseResult.Success,
        raw: String,
    ): SettlementOutcome {
        val proposal = parsed.proposal

        // 先把路径写法归一：模型常常用点号或带着一层前缀写路径，
        // 内容其实完全正确。不归一的话，这种写法差异会让整轮结算全部作废
        val resolution = StatePathResolver.resolve(proposal.values, task.schema)

        val receipt = StateGuards.apply(
            turn = task.turn,
            current = task.stateSnapshot,
            proposals = resolution.values,
            schema = task.schema,
            summary = proposal.summary,
            // 说明也要跟着改键，否则归一之后这一项的说明就对不上了
            reasons = proposal.reasons.mapKeys { (key, _) -> resolution.remapped[key] ?: key },
        )
        return SettlementOutcome(
            turn = task.turn,
            basedOnRevision = task.basedOnRevision,
            receipt = receipt,
            usage = usage,
            diagnostics = SettlementDiagnostics(
                // 成功时也留一份原始输出。它是把「模型说了什么」和
                // 「代码最终认了什么」对照起来看的唯一依据，
                // 而这正是让裁决过程可被检验、而不是黑箱的关键
                rawOutput = preview(raw),
                recoveredFromWrapper = parsed.recoveredFromWrapper,
                remappedPaths = resolution.remapped,
            ),
        )
    }

    /**
     * 构造一份失败回执。
     *
     * 失败时**不产生任何变化**，但依然返回一份带轮次的回执 ——
     * 界面需要知道「这一轮尝试过结算且失败了」，而不是看起来什么都没发生。
     * 后者会让使用者以为本轮确实没有数值变化，而那是完全相反的结论。
     *
     * 注意这里不往回执的拦截列表里塞东西：那个列表记的是「裁决环节拦下了什么」，
     * 而调用失败和解析失败发生在裁决之前，根本还没到裁决这一步。
     * 原因放在排查信息里，由界面按类别取文案。
     */
    private fun failed(
        task: SettlementTask,
        usage: TokenUsage,
        diagnostics: SettlementDiagnostics,
    ) = SettlementOutcome(
        turn = task.turn,
        basedOnRevision = task.basedOnRevision,
        receipt = SettlementReceipt(turn = task.turn, status = SettlementStatus.Error),
        usage = usage,
        diagnostics = diagnostics,
    )

    private fun preview(text: String): String? =
        text.takeIf { it.isNotBlank() }?.take(MAX_RAW_PREVIEW)

    private companion object {
        /**
         * 结算的默认空闲上限。
         *
         * 比叙事调用宽松得多。结算用的模型可能较慢，而这是一次后台工作，
         * 多等一会儿不影响使用者继续往下玩；反过来误判超时则意味着
         * 这一轮的数值变化白白丢失，还花了钱。
         */
        val DEFAULT_IDLE_TIMEOUT: Duration = 5.minutes

        /** 原始输出留痕的长度上限。 */
        const val MAX_RAW_PREVIEW = 1000
    }
}
