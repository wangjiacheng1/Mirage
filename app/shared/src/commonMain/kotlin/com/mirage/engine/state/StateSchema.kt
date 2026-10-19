package com.mirage.engine.state

/**
 * ============================================================================
 * StateSchema —— 状态契约（守卫的「法律文本」）
 * ============================================================================
 *
 * ## 它从哪来？
 *
 * 三层来源，对应 docs/06 §9 的 Tier A / B / C：
 *
 * - **Tier A（默认开，零成本）**：从 ST 角色卡的 `<initvar>` 初始值**机械推导**。
 *   有哪些路径、每个路径是什么类型，100% 可得、不需要调用任何模型。
 *   对应守卫 G1（路径白名单）、G2（类型）、G8（空 delta 合法）。
 *
 * - **Tier B（默认关，导入时让用户确认）**：从命名约定**推断**。
 *   例如看到 `生命` 和 `生命上限` 成对出现，就猜 `生命 ∈ [0, 生命上限]`。
 *   🔴 必须默认关。实证理由：真实卡 DiLaoJSON.json 里 `敌人/生命上限` 初始值是 0
 *   （战斗未开始时的占位值），机械推断会得出 max=0，于是敌人一出场就被夹成 0 血，
 *   游戏直接玩不了。这是「看起来很聪明的自动推断」毁掉用户体验的典型。
 *
 * - **Tier C（AI 补，可选）**：枚举值、取值范围往往只写在世界书散文里。
 *   例如 `难度` 的「简单/中等/困难」写在 entries[2] 的说明文字中，
 *   `本轮房间` 的可选值写在 entries[4]。需要调一次模型把它抽出来。
 *
 * ## 🔴 兼容硬底线
 *
 * 导入的 ST 卡，**默认不得因为我们的守卫而比在 SillyTavern / DSH Tavern 里表现更差**。
 * 所以 Tier A 之外的所有约束默认关闭，由用户在导入预检界面主动打开。
 * 这条底线直接决定了下面每个字段的 `default` 取值，改动前请先读 docs/06 §9。
 */
data class StateSchema(
    /**
     * 路径 → 字段定义。
     *
     * key 用 `/` 分隔的路径，例如 `"冒险者/生命"`。
     * 之所以用 `/` 而不是 MVU 的 `.`：角色卡里的中文键名可能自带点号
     * （例如技能名 "疾风·斩"），用 `.` 做分隔符会产生歧义。
     * 导入 ST 卡时由 adapter 负责把 `冒险者.生命` 转成 `冒险者/生命`，
     * 导出时再转回去 —— **我们绝不改写卡的原始数据格式**（docs/06 §9 兼容底线）。
     */
    val fields: Map<String, FieldSpec>,

    /**
     * 来源标记。用于在 UI 上诚实地告诉用户「这条约束是卡里写的，还是我们猜的」。
     * 用户有权知道哪些限制不是角色卡作者的本意。
     */
    val origin: SchemaOrigin = SchemaOrigin.DerivedFromInitVar,
) {
    /** G1 要用的快速查找集合。预先算好，避免每轮结算都遍历 map。 */
    val knownPaths: Set<String> = fields.keys

    fun specOf(path: String): FieldSpec? = fields[path]
}

enum class SchemaOrigin {
    /** Tier A：从 `<initvar>` 机械推导，可信度最高。 */
    DerivedFromInitVar,

    /** Tier B：命名约定推断，用户已在导入预检里确认过。 */
    InferredAndConfirmed,

    /** Tier C：模型抽取，可能有误，UI 上应标注。 */
    ModelExtracted,

    /** 用户在 Mirage 里手工编辑过。 */
    UserEdited,
}

/**
 * 单个字段的约束定义。
 *
 * 每个可选约束都对应一个守卫；**为 null 表示该守卫对这个字段不生效**。
 * 这个「null = 不检查」的设计是刻意的：它让「Tier A 只填 type」天然等价于
 * 「只开 G1+G2+G8」，不需要额外的开关字段。
 */
data class FieldSpec(
    /** G2 用：期望类型。这是 Tier A 唯一必填项。 */
    val type: FieldType,

    /** 数组元素类型。仅当 [type] 为 [FieldType.Array] 时有意义。 */
    val elementType: FieldType? = null,

    /**
     * G3 用：值域下界。null = 不限制下界。
     * Tier B 才会填（例如 `生命` 的 min=0）。
     */
    val min: Double? = null,

    /** G3 用：值域上界。null = 不限制上界。 */
    val max: Double? = null,

    /**
     * G3 的动态上界：另一个字段的当前值作为上界。
     *
     * 例如 `冒险者/生命` 的 `maxFromPath = "冒险者/生命上限"`。
     * 之所以需要这个而不能把 max 写死：上限本身会变（升级、buff）。
     *
     * 🔴 注意它和 Tier B 占位值陷阱的关系：如果引用的路径当前值是 0（未初始化），
     * [StateGuards] 会**跳过**这条夹取而不是把值夹成 0，见 G3 实现里的注释。
     */
    val maxFromPath: String? = null,

    /**
     * G4 用：允许的取值集合。null = 不限制。
     * 一般由 Tier C 从世界书散文里抽取得到。
     */
    val enumValues: Set<String>? = null,

    /**
     * G5 用：单轮最大变化幅度（绝对值）。null = 不限制。
     *
     * 用来兜住「LLM 一轮把好感度从 10 干到 95」这种叙事失控。
     * 注意处置是 **clamp 而不是丢弃** —— 剧情里确实发生了变化，
     * 粗暴丢弃会让玩家觉得「我做了事但系统没反应」，比数值偏小更伤体验。
     */
    val maxDeltaPerTurn: Double? = null,

    /**
     * G6 用：只读 / 系统字段。
     *
     * `true` 表示这个字段**只能由引擎自增**，LLM 无权提议。
     * 典型是 `地牢/当前轮`、`地牢/总轮数`。
     *
     * 🔴 这不是假想需求。M0 第 4 轮负向测试里，模型在我们明确要求
     * 「不要改轮数」的情况下，依然凭空提议了 `/地牢/当前轮: 2→3`。
     * 提示词约束挡不住，必须由代码挡 —— 这就是 ADR-012「约束下沉到代码层」的由来。
     */
    val readOnly: Boolean = false,

    /**
     * G7 用：单调方向。null = 不限制方向。
     * 例如「已解锁成就数」只能增不能减。
     */
    val monotonic: Monotonic? = null,

    /** 给玩家看的中文名。为空则直接显示路径。 */
    val displayName: String? = null,
)

enum class FieldType { Int, Double, String, Bool, Array, Any }

enum class Monotonic {
    /** 只能变大或不变。 */
    Increasing,

    /** 只能变小或不变。 */
    Decreasing,
}
