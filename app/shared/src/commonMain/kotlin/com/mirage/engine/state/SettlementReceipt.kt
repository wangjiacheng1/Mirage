package com.mirage.engine.state

/**
 * ============================================================================
 * SettlementReceipt —— 结算回执
 * ============================================================================
 *
 * 一轮状态结算结束后，引擎产出的**唯一对外结果**。它同时承担两个职责：
 *  1. 给 UI 渲染「本轮变量怎么了」的那张卡片；
 *  2. 给排查问题留下完整证据（哪条被改了、哪条被拒了、为什么）。
 *
 * ## 这个模型是抄来的，而且是有意抄的
 *
 * 证据等级 **E1-源码**。来源：DSH Tavern `tavern-plugin/lib/client.js` 的
 * `TavernMvuReceipt` 组件（约 13120~13175 行）。原始结构：
 *
 * ```js
 * receipt = { status, summary, changes[], sideEffects[], failures[], turn }
 * status ∈ ["pending","updated","partial","error","stale","interrupted","unchanged"]
 * operationLabels = { set:"设置", add:"新增", delta:"增减", delete:"删除", move:"移动" }
 * ```
 *
 * 为什么直接对齐而不自己设计：
 * - 用户实测了 SillyTavern / DSH Tavern / DZMM 三家，结论是 DSH 的结算 UX **最好**
 *   （失败有提示卡、有精确到字段路径的原因、有重算按钮）。
 * - 我自己在第二、三轮曾凭截图推断「它失败了没人管」「它不区分失败和无变化」，
 *   **两次都错**。读完源码才发现它区分得比我设想的还细（7 态，我当时只看到 4 态）。
 *   教训记在 AGENTS.md 铁律一：看不到实物就写「未知」，不要推断。
 *
 * ## 我们在抄的基础上要补的
 *
 * DSH 的 status 是**整轮**的；但它的 `failures` 里每条失败只有自然语言描述。
 * Mirage 给每条失败加了**机器可读的 [GuardFailureCode]**，这样：
 *  - UI 可以按失败类型给不同的处置建议；
 *  - 我们能统计「哪类守卫最常触发」，反过来优化提示词；
 *  - 自动化测试可以断言具体失败码，而不是匹配中文字符串。
 */
data class SettlementReceipt(
    /** 这份回执属于第几轮。用于和消息树上的节点对齐。 */
    val turn: Int,

    /** 整轮状态。取值语义见 [SettlementStatus]。 */
    val status: SettlementStatus,

    /**
     * 自然语言总结：「本轮为什么是这个结果」。
     *
     * 来自模型结算时返回的 reason 字段。对应 DSH 回执卡片上的「原因：……」那一行。
     * 这就是我之前列为未决项的「自然语言变化溯源」—— MVU 源码里每条 Command
     * 都带 `reason`，渲染时拼成 `(原因)` 附在变更后面，确认存在。
     */
    val summary: String? = null,

    /** 成功落盘的变更，按提议顺序。 */
    val changes: List<StateChange> = emptyList(),

    /** 被守卫拦下的提议。每条都带失败码和人话解释。 */
    val failures: List<GuardFailure> = emptyList(),
)

/**
 * 整轮结算状态。
 *
 * 🔴 注意 [Unchanged] 和 [Error] 必须分开 —— 这是用户实测里 DSH 做得好、
 * 而 DZMM 做得差的关键差异点。「这轮剧情本来就没改数值」和「这轮改失败了」
 * 对玩家是完全不同的信息，合并成一个「没更新」会让人完全无法判断要不要重试。
 */
enum class SettlementStatus {
    /** 已排队/进行中。UI 显示加载态，重试按钮文案为「重新投递结算」。 */
    Pending,

    /** 全部提议都落盘了。 */
    Updated,

    /** 部分成功部分失败。UI 要同时显示成功数和失败数。 */
    Partial,

    /** 全部失败。 */
    Error,

    /**
     * 结果过期：这次结算基于的是旧的剧情版本（branch/revision 已经变了）。
     *
     * 典型触发：玩家在结算还没回来的时候就重新生成了正文。
     * 此时**必须丢弃**这个结果，绝不能覆盖当前状态，否则会出现
     * 「我明明重写了这一轮，但数值还是按旧剧情算的」。
     *
     * DSH 源码里对应 `stale`，文案「变量结算已过期，未覆盖当前状态」。
     */
    Stale,

    /**
     * 被中断：用户主动停止，或超时。
     *
     * DSH 的超时规则（E1，CONTEXT.md「Background Operation」）值得照抄：
     * **后台模型阶段连续 5 分钟没有有效流式输出才算失败**，
     * 而「运行时长本身不构成失败」。思考、正文、工具参数增量都会刷新进度。
     * 这条很重要 —— 推理模型思考两分钟是正常的，按总时长超时会误杀。
     */
    Interrupted,

    /**
     * 本轮确实没有数值变化。
     *
     * 🔴 这是**正常结果，不是失败**（对应守卫 G8）。
     * 空 delta `{}` 必须走到这里，而不是被当成「解析失败」。
     */
    Unchanged,
}

/** 一条成功落盘的变更。 */
data class StateChange(
    val path: String,
    val operation: ChangeOperation,
    /** 变更前的值。用于回执显示 `60 → 49`，也用于回滚。 */
    val before: StateValue,
    /** 变更后**实际落盘**的值。注意可能被 G3/G5 夹取过，不等于 LLM 提议的值。 */
    val after: StateValue,
    /** 模型给的变更理由，单条粒度。 */
    val reason: String? = null,
    /**
     * 如果这条变更被守卫修改过（夹取/截断），记录在这里。
     * UI 上应当提示用户「系统把 120 夹到了上限 100」，保持透明。
     */
    val adjustedBy: GuardFailureCode? = null,
)

/**
 * 变更操作类型。
 *
 * 对齐 MVU（magvarupdate）的命令集，便于 ST 卡双向互通。
 * MVU 源码 `update_variables.ts` 里的别名归一：remove→delete, assign→insert, unset→delete，
 * 我们在 adapter 层做同样的归一，核心层只认下面这几个。
 */
enum class ChangeOperation {
    /** 绝对赋值。**这是 Mirage 的主力操作**，见下方说明。 */
    Set,

    /** 数值增减（MVU 的 `add`）。 */
    Delta,

    /** 往数组/对象里插入。 */
    Insert,

    /** 删除。 */
    Delete,
}

/**
 * 一条被守卫拦下（或修正）的提议。
 */
data class GuardFailure(
    val path: String,
    val code: GuardFailureCode,
    /** LLM 原本想设的值。保留它，便于排查「模型到底在想什么」。 */
    val proposed: StateValue,
    /** 给玩家看的人话解释。要具体到字段，不要写「参数错误」这种废话。 */
    val message: String,
    /**
     * 处置方式：丢弃，还是夹取后仍然落盘。
     * 注意 [Disposition.Clamped] 的条目会**同时**出现在 changes 和 failures 里 ——
     * 前者记录实际落盘值，后者记录「我们动过手脚」这件事。
     */
    val disposition: Disposition,
)

enum class Disposition {
    /** 整条提议丢弃，状态保持原值。 */
    Rejected,

    /** 提议被修正后落盘（例如夹到上限）。 */
    Clamped,
}

/**
 * 守卫失败码。每个码对应 ADR-012 里的一条守卫。
 *
 * 用枚举而不是字符串，是为了让测试能精确断言，也让 UI 能做 when 穷尽匹配。
 */
enum class GuardFailureCode {
    /** G1：路径不在 schema 里。模型凭空发明了一个变量。 */
    UnknownPath,

    /** G2：类型对不上，且无法安全转换。 */
    TypeMismatch,

    /** G3：超出值域，已夹取。**这是警告不是拒绝**。 */
    Clamped,

    /** G4：不在允许的枚举集合里。 */
    IllegalEnum,

    /** G5：单轮变化幅度过大，已夹取。 */
    DeltaTooLarge,

    /** G6：试图修改只读/系统字段。直接丢弃，不给商量余地。 */
    ReadOnlyPath,

    /** G7：违反单调方向（例如只增字段被要求减少）。 */
    DirectionViolation,
}
