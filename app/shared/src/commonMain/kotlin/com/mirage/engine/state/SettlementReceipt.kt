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
 * ## 结构上的两个要点
 *
 * **一、整轮有一个总状态，每条拦截还各自带分类。**
 * 只有总状态，界面就只能笼统地说「部分失败」；只有逐条分类，
 * 又回答不了「这一轮整体算成了没有」。两者缺一不可。
 *
 * **二、拦截的分类是机器可读的枚举，不是一句话。** 这带来三件事：
 *  - 界面可以按拦截类型给出不同的后续建议；
 *  - 可以统计哪类拦截最常触发，反过来优化给模型的提示；
 *  - 测试可以断言具体分类，而不是去匹配一段中文。
 */
data class SettlementReceipt(
    /** 这份回执属于第几轮。用于和消息树上的节点对齐。 */
    val turn: Int,

    /** 整轮状态。取值语义见 [SettlementStatus]。 */
    val status: SettlementStatus,

    /**
     * 自然语言总结：「本轮为什么是这个结果」。
     *
     * 来自模型结算时返回的 reason 字段。用于在回执里说明「为什么会这样变」。
     * 它让每一条变化都有据可查 —— 上游同类框架里每条命令
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
 * 🔴 注意 [Unchanged] 和 [Error] 必须分开 —— 这是实测中同类实现做得好、
 * 而做得差的产品分不清的关键差异点。「这轮内容本来就没改数值」和「这轮改失败了」
 * 对使用者是完全不同的信息，合并成一个「没更新」会让人完全无法判断要不要重试。
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
     * 典型触发：使用者在结算还没回来的时候就重新生成了正文。
     * 此时**必须丢弃**这个结果，绝不能覆盖当前状态，否则会出现
     * 「我明明重写了这一轮，但数值还是按旧剧情算的」。
     *
     * 同类实现里把这个状态叫做「已过期」。
     */
    Stale,

    /**
     * 被中断：用户主动停止，或超时。
     *
     * 一条值得照搬的超时规则：
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
    /** 变更后**实际落盘**的值。注意可能被 G3/G5 夹取过，不等于 模型提议的值。 */
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
 * 与上游同类框架的命令集保持一致，便于与外部数据双向互通。
 * 上游框架里的别名归一规则：remove→delete, assign→insert, unset→delete，
 * 我们在 adapter 层做同样的归一，核心层只认下面这几个。
 */
enum class ChangeOperation {
    /** 绝对赋值。**这是 Mirage 的主力操作**，见下方说明。 */
    Set,

    /** 数值增减（对应上游框架的 `add`）。 */
    Delta,

    /** 往数组/对象里插入。 */
    Insert,

    /** 删除。 */
    Delete,
}

/**
 * 一条被拦下或被修正的提议。
 *
 * 🔴 这个结构里**不含任何给使用者看的文案**，只有分类和格式化所需的参数。
 * 具体说什么由展示层按 [code] 取模板，再用 [displayName] 和 [detail] 里的值填充。
 *
 * 这样安排的理由：同一条拦截在不同位置需要不同的详略程度（列表里一句话带过，
 * 详情里要说清上下界是多少），还要能换语言、能改措辞。
 * 把句子拼死在这里，这几件事一件都做不到，而且每改一个字都要动引擎。
 */
data class GuardFailure(
    val path: String,
    val code: GuardFailureCode,
    /** 模型原本想设的值。保留它便于排查「模型到底在想什么」。 */
    val proposed: StateValue,
    /**
     * 处置方式：丢弃，还是修正后仍然落盘。
     * 注意 [Disposition.Clamped] 的条目会**同时**出现在变化列表和这里 ——
     * 前者记录实际落盘值，后者记录「我们动过手脚」这件事。
     */
    val disposition: Disposition,
    /**
     * 字段的展示名。为空时由展示层退回使用 [path]。
     *
     * 放在这里而不是让展示层去查字段定义：回执可能被保存下来事后查看，
     * 而那时字段定义可能已经改过了 —— 回执必须自洽，不依赖外部状态。
     */
    val displayName: String? = null,
    /** 格式化所需的补充参数。含义由 [code] 决定，见各变体说明。 */
    val detail: GuardFailureDetail? = null,
)

/**
 * 拦截的补充参数。
 *
 * 每个变体对应一类拦截。刻意不合并成一个「什么字段都有」的结构：
 * 那样每个使用处都得自己判断这次哪些字段有意义，而编译器帮不上忙。
 */
sealed interface GuardFailureDetail {

    /** 类型不符。[expected] 是字段声明的类型。 */
    data class TypeExpectation(
        val expected: FieldType,
        val expectedElement: FieldType? = null,
    ) : GuardFailureDetail

    /** 取值不在允许集合内。 */
    data class AllowedValues(val values: List<String>) : GuardFailureDetail

    /** 违反了单调方向。[required] 是字段要求的方向。 */
    data class DirectionRequirement(val required: Monotonic) : GuardFailureDetail

    /**
     * 单轮变化幅度超限。
     *
     * @property limit 允许的单轮最大变化量。
     * @property attempted 模型实际想造成的变化量。
     * @property applied 按上限截断后实际采用的变化量。
     */
    data class DeltaLimit(
        val limit: Double,
        val attempted: Double,
        val applied: Double,
    ) : GuardFailureDetail

    /**
     * 超出值域被夹取。
     *
     * 上下界都可能为空：有的字段只有下界、有的只有上界，
     * 而上界还可能是从另一个字段读来的运行时值。
     */
    data class RangeClamp(
        val applied: StateValue,
        val lowerBound: Double? = null,
        val upperBound: Double? = null,
    ) : GuardFailureDetail
}

enum class Disposition {
    /** 整条提议丢弃，状态保持原值。 */
    Rejected,

    /** 提议被修正后落盘（例如夹到上限）。 */
    Clamped,
}

/**
 * 拦截分类。每个分类对应一条守卫。
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
