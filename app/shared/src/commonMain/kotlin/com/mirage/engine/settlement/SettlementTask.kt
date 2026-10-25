package com.mirage.engine.settlement

import com.mirage.engine.llm.StreamError
import com.mirage.engine.llm.TokenUsage
import com.mirage.engine.state.SettlementReceipt
import com.mirage.engine.state.SettlementStatus
import com.mirage.engine.state.StateSchema
import com.mirage.engine.state.StateValue

/**
 * 一次结算要处理的输入。
 * 给AI看的工作材料
 * 把它做成一个显式的对象而不是一串参数，是因为**结算是异步的**：
 * 任务发起时的上下文必须被完整带走并固定下来。
 * 如果执行时再去读「当前状态」，读到的可能已经是后面几轮的结果了，
 * 算出来的变化会张冠李戴。
 */
data class SettlementTask(
    /** 这次结算对应的是第几轮。 */
    val turn: Int,

    /**
     * 发起时所基于的状态版本号。
     *
     * 结算结果回来时要拿它和当时的版本比对。不一致说明状态已经被后续动作改过，
     * 这份结果就是过期的，不能再往上套 —— 套上去会把更晚发生的变化覆盖掉。
     */
    val basedOnRevision: Long,

    /** 本轮的叙事内容，也就是要结算的对象。 */
    val narrative: String,

    /**
     * 发起时的状态快照。
     *
     * 必须是快照而不是引用：它既是给模型看的上下文，也是裁决时的比对基准。
     * 两者必须是同一份数据，否则会出现「拿 A 时刻的状态生成提议、
     * 却按 B 时刻的状态做裁决」这种错位。
     */
    val stateSnapshot: Map<String, StateValue>,

    /** 字段定义。决定哪些路径合法、各自是什么类型。 */
    val schema: StateSchema,
)

/**
 * 排查用的过程信息。
 *
 * 和结算回执分开放：回执是「结论」，要展示给使用者；
 * 这些是「过程」，只在排查时才需要。混在一起会让回执带上一堆
 * 对使用者毫无意义的字段。
 */
data class SettlementDiagnostics(
    /** 模型返回的原始文本。截断保存。 */
    val rawOutput: String? = null,

    /**
     * 是否依赖了兜底手段才取出结构化内容。
     *
     * 需要统计它的触发频率：直接解析的成功率本来就只有一半左右，
     * 这个比例一旦明显变化，说明模型或提示词那边发生了我们没察觉的变化。
     */
    val recoveredFromWrapper: Boolean = false,

    /**
     * 模型用了另一种路径写法、被我们改写过的那些项（原写法 → 归一后的路径）。
     *
     * 它是提示词出问题的早期信号：偶尔出现属正常，一旦每轮都有，
     * 说明模型整体在按另一套写法输出，该去查提示词里是不是混进了别家的说明。
     */
    val remappedPaths: Map<String, String> = emptyMap(),

    /** 解析失败的类别。成功时为空。 */
    val parseFailure: ProposalParseFailure? = null,

    /** 调用失败的信息。成功时为空。 */
    val streamError: StreamError? = null,
)

/**
 * 一次结算的完整结果。
 *
 * @property usage 这次结算消耗的额度与费用。单独带出来是因为结算是一次
 *   使用者看不见的额外调用 —— 看不见的开销更需要被如实记账，
 *   否则账单上会出现无法解释的数字。
 */
data class SettlementOutcome(
    val turn: Int,
    val basedOnRevision: Long,
    val receipt: SettlementReceipt,
    val usage: TokenUsage = TokenUsage(),
    val diagnostics: SettlementDiagnostics = SettlementDiagnostics(),
) {
    /** 这次结算是否产生了可以落盘的变化。 */
    val hasApplicableChanges: Boolean
        get() = receipt.changes.isNotEmpty()

    /**
     * 标记为过期。
     *
     * 在结果回来时发现状态版本已经变了，就走这个方法。
     * **变化会被整体清空**：它们是基于一份已经不成立的前提算出来的，
     * 部分采纳比全部丢弃更危险 —— 那会产生一个谁也说不清来历的中间状态。
     *
     * 保留回执本身而不是直接丢掉，是为了让这件事在界面上可见：
     * 使用者有权知道「这一轮的结算白跑了一次，而且是花了钱的」。
     */
    fun markStale(): SettlementOutcome = copy(
        receipt = receipt.copy(
            status = SettlementStatus.Stale,
            changes = emptyList(),
        ),
    )

    /**
     * 标记为被中断。
     *
     * 与过期的区别：过期是「算完了但没用上」，中断是「压根没算完」。
     * 两者对使用者的含义不同 —— 前者说明操作太快，后者说明被打断了，
     * 而且只有后者值得提示「可以重新结算」。
     */
    fun markInterrupted(): SettlementOutcome = copy(
        receipt = receipt.copy(
            status = SettlementStatus.Interrupted,
            changes = emptyList(),
        ),
    )
}
