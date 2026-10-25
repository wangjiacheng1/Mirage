package com.mirage.engine.state

import kotlin.math.abs

/**
 * ============================================================================
 * 状态守卫 —— 「约束下沉到代码层」的实现
 * ============================================================================
 *
 * ## 一句话
 *
 * **模型只负责「提议」，代码负责「裁决」。**
 *
 * ## 为什么必须这样做（这是实测结论，不是设计洁癖）
 *
 * 我们跑过真实模型的对照实验，三个结果直接否掉了「靠提示词约束」的路线：
 *
 * 1. **第 4 轮负向测试失败**：提示词里明写了「不要修改轮数」，模型依然凭空提议
 *    `某个只读计数字段: 2→3`。→ 催生 G6（只读字段）。
 * 2. **D5 对照组反直觉**：把严格约束从提示词里**删掉**之后，断言通过率
 *    从 85% **升到** 97%。说明长篇约束不但没用，还在挤占模型的注意力。
 *    → 约束应该从提示词搬到代码里，提示词专心讲叙事。
 * 3. **D7 增量语义劣化**：让模型输出「+5 / -3」这种增量，断言通过率掉到 67%；
 *    改成输出绝对新值就恢复正常。→ 定下 E-3b：**delta 一律用绝对新值**。
 *
 * 一个旁证：上游同类框架的 `set` 命令本身就无条件要求
 * 「路径必须已存在，否则报错跳过」（`update_variables.ts`:
 * `if (path !== '' && !_.has(variables.stat_data, path)) { outError(...); continue }`）。
 * 也就是说，**行业标杆早就在代码层做路径白名单了**，我们不是在发明新规矩。
 *
 * ## 守卫清单
 *
 * | 守卫 | 规则                        | 失败码              | 处置            |
 * |------|-----------------------------|---------------------|-----------------|
 * | G1   | 路径必须在 schema 里        | UnknownPath         | 丢弃            |
 * | G2   | 类型必须一致                | TypeMismatch        | 尽力转换，否则丢弃 |
 * | G3   | 值域夹取 min/max            | Clamped             | **夹取，不拒绝** |
 * | G4   | 枚举值白名单                | IllegalEnum         | 丢弃            |
 * | G5   | 单轮变化幅度上限            | DeltaTooLarge       | 夹取            |
 * | G6   | 只读/系统字段               | ReadOnlyPath        | **直接丢弃**     |
 * | G7   | 单调方向                    | DirectionViolation  | 丢弃            |
 * | G8   | 空 delta 是合法结果         | —                   | 置 Unchanged    |
 *
 * ## 执行顺序很重要
 *
 * G6 → G1 → G2 → G4 → G7 → G5 → G3
 *
 * 理由：
 * - **G6 必须最先**。只读字段哪怕类型、值域全对，也不该被碰；先判它能省掉后面全部计算，
 *   也避免出现「轮数被夹取到合法范围然后落盘」这种荒谬结果。
 * - **G1 在 G2 前**：路径都不存在，谈类型没有意义。
 * - **G3 放最后**：夹取是「修正」而非「否决」，必须等所有否决性检查都通过了才做，
 *   否则会把一个本来要丢弃的值先夹一遍，白费功夫还可能误导日志。
 * - G5 在 G3 前：先限制「变化幅度」，再限制「绝对值域」。两者都夹时，
 *   最终值要同时满足，所以顺序上先窄后宽更直观。
 *
 * ## 这个类是纯函数、无状态、无依赖
 *
 * 不碰数据库、不碰网络、不碰 Android API。所以它可以在 commonTest 里裸跑，
 * 也可以在 iOS 上复用。状态引擎是 Mirage 的护城河，必须保持这种可测试性。
 */
object StateGuards {

    /**
     * 对一批 模型提议执行全部守卫，产出结算回执。
     *
     * @param turn        轮次号，原样写进回执。
     * @param current     当前状态快照（路径 → 值）。**守卫只读它，不修改。**
     *                    之所以要求传快照而不是可变状态：重算功能依赖
     *                    「结算前快照」。同类实现里有一句
     *                    `'这轮旧记录没有结算前快照，无法安全重新结算变量'`，
     *                    说明它也是这么设计的，而且踩过没快照的坑。
     * @param proposals   模型提议的变更，路径 → 新值（绝对值，不是增减量）。
     *                    值为 [StateValue.Absent] 的条目会被直接忽略。
     * @param schema      约束契约。
     * @param summary     模型给的整轮自然语言理由，原样透传进回执。
     * @param reasons     每条提议的单独理由，路径 → 理由文本。可缺省。
     */
    fun apply(
        turn: Int,
        current: Map<String, StateValue>,
        proposals: Map<String, StateValue>,
        schema: StateSchema,
        summary: String? = null,
        reasons: Map<String, String> = emptyMap(),
    ): SettlementReceipt {
        val changes = mutableListOf<StateChange>()
        val failures = mutableListOf<GuardFailure>()

        for ((path, rawProposed) in proposals) {
            // ---- G8 前置：Absent 表示「模型没提这个字段」，静默跳过 ----------
            // 注意这里 continue 之前**不记录任何东西**：没提到就是没提到，
            // 既不是变更也不是失败。把它记成 failure 会让回执充满噪音。
            if (rawProposed is StateValue.Absent) continue

            val spec = schema.specOf(path)

            // ---- G6 只读 / 系统字段（最先判，直接丢弃）------------------------
            // 轮数这类字段只能由引擎在推进剧情时自增。模型提议一律拒绝，
            // 哪怕它提议的值恰好是对的 —— 因为「恰好对」是运气，不是契约。
            if (spec?.readOnly == true) {
                failures += GuardFailure(
                    path = path,
                    code = GuardFailureCode.ReadOnlyPath,
                    proposed = rawProposed,
                    disposition = Disposition.Rejected,
                    displayName = spec.displayName,
                )
                continue
            }

            // ---- G1 路径白名单 -----------------------------------------------
            // 模型凭空发明变量是高频现象（实测），这里是第一道闸。
            if (spec == null) {
                failures += GuardFailure(
                    path = path,
                    code = GuardFailureCode.UnknownPath,
                    proposed = rawProposed,
                    disposition = Disposition.Rejected,
                )
                continue
            }

            val before = current[path] ?: StateValue.NullValue

            // ---- G2 类型校验（带有限度的宽容转换）-----------------------------
            val coerced = coerce(rawProposed, spec)
            if (coerced == null) {
                failures += GuardFailure(
                    path = path,
                    code = GuardFailureCode.TypeMismatch,
                    proposed = rawProposed,
                    disposition = Disposition.Rejected,
                    displayName = spec.displayName,
                    detail = GuardFailureDetail.TypeExpectation(spec.type, spec.elementType),
                )
                continue
            }

            // ---- G4 枚举白名单 ------------------------------------------------
            // 实测样本：某个枚举字段被模型填成了定义里根本没有的
            // 「战斗」「石阶」。叙事上说得通，但面板上会出现一个谁都不认识的房间。
            val enumValues = spec.enumValues
            if (enumValues != null && coerced is StateValue.StringValue &&
                coerced.value !in enumValues
            ) {
                failures += GuardFailure(
                    path = path,
                    code = GuardFailureCode.IllegalEnum,
                    proposed = rawProposed,
                    disposition = Disposition.Rejected,
                    displayName = spec.displayName,
                    detail = GuardFailureDetail.AllowedValues(enumValues.toList()),
                )
                continue
            }

            // 下面三个守卫（G7/G5/G3）都只对数值生效。
            // 非数值（字符串、布尔、数组）走到这里就算通过了。
            val newNum = coerced.asNumberOrNull()
            val oldNum = before.asNumberOrNull()

            if (newNum == null) {
                changes += StateChange(path, ChangeOperation.Set, before, coerced, reasons[path])
                continue
            }

            // ---- G7 单调方向 ---------------------------------------------------
            // 只在旧值也是数值时才有意义；旧值是 null（首次赋值）时不判方向。
            val requiredDirection = spec.monotonic
            if (oldNum != null && requiredDirection != null) {
                val violated = when (requiredDirection) {
                    Monotonic.Increasing -> newNum < oldNum
                    Monotonic.Decreasing -> newNum > oldNum
                }
                if (violated) {
                    failures += GuardFailure(
                        path = path,
                        code = GuardFailureCode.DirectionViolation,
                        proposed = rawProposed,
                        disposition = Disposition.Rejected,
                        displayName = spec.displayName,
                        detail = GuardFailureDetail.DirectionRequirement(requiredDirection),
                    )
                    continue
                }
            }

            // 从这里开始，值可能被「修正」而不是被拒绝。
            // adjusted 记录最终落盘值，adjustedBy 记录是谁动的手。
            var adjusted = newNum
            var adjustedBy: GuardFailureCode? = null

            // ---- G5 单轮变化幅度 ------------------------------------------------
            // 处置是 clamp 不是 reject：剧情里确实发生了事，粗暴丢弃会让使用者
            // 觉得「我砍了一刀但怪没掉血」，那比数值偏小难受得多。
            val maxDelta = spec.maxDeltaPerTurn
            if (maxDelta != null && oldNum != null) {
                val delta = adjusted - oldNum
                if (abs(delta) > maxDelta) {
                    val capped = if (delta > 0) oldNum + maxDelta else oldNum - maxDelta
                    failures += GuardFailure(
                        path = path,
                        code = GuardFailureCode.DeltaTooLarge,
                        proposed = rawProposed,
                        disposition = Disposition.Clamped,
                        displayName = spec.displayName,
                        detail = GuardFailureDetail.DeltaLimit(
                            limit = maxDelta,
                            attempted = delta,
                            applied = capped - oldNum,
                        ),
                    )
                    adjusted = capped
                    adjustedBy = GuardFailureCode.DeltaTooLarge
                }
            }

            // ---- G3 值域夹取 ------------------------------------------------------
            val lower = spec.min
            val upper = resolveUpperBound(spec, current)
            var clampedByRange = false

            if (lower != null && adjusted < lower) {
                adjusted = lower
                clampedByRange = true
            }
            if (upper != null && adjusted > upper) {
                adjusted = upper
                clampedByRange = true
            }
            if (clampedByRange) {
                failures += GuardFailure(
                    path = path,
                    code = GuardFailureCode.Clamped,
                    proposed = rawProposed,
                    disposition = Disposition.Clamped,
                    displayName = spec.displayName,
                    detail = GuardFailureDetail.RangeClamp(
                        applied = rebuildNumber(adjusted, spec.type, coerced),
                        lowerBound = lower,
                        upperBound = upper,
                    ),
                )
                if (adjustedBy == null) adjustedBy = GuardFailureCode.Clamped
            }

            // 把 Double 还原成原本的类型，避免整数字段落盘成 49.0
            val finalValue = rebuildNumber(adjusted, spec.type, coerced)

            // 夹取之后可能和旧值相等了（例如旧值已在上限，模型又想加）。
            // 这种情况不记 change —— 没变就是没变，回执里不该出现 `100 → 100`。
            if (finalValue == before) continue

            changes += StateChange(
                path = path,
                operation = ChangeOperation.Set,
                before = before,
                after = finalValue,
                reason = reasons[path],
                adjustedBy = adjustedBy,
            )
        }

        return SettlementReceipt(
            turn = turn,
            status = resolveStatus(changes, failures),
            summary = summary,
            changes = changes,
            failures = failures,
        )
    }

    /**
     * ---- G8：空 delta 是合法结果 ----------------------------------------------
     *
     * 整轮状态的判定。核心是**不能把「没变化」当成「失败」**。
     *
     * 这是实测中同类产品的分水岭：做得好的会用不同颜色和不同文案明确区分，
     * 而表现差的产品会让使用者分不清「这轮剧情本来就不涉及数值」和「系统崩了」。
     */
    private fun resolveStatus(
        changes: List<StateChange>,
        failures: List<GuardFailure>,
    ): SettlementStatus {
        // 只统计「真正被否决」的失败。被夹取的条目已经落盘了，
        // 它既算成功变更也算一条警告，不应该把整轮拖成 Partial。
        val rejected = failures.count { it.disposition == Disposition.Rejected }
        return when {
            changes.isEmpty() && rejected == 0 -> SettlementStatus.Unchanged
            changes.isEmpty() -> SettlementStatus.Error
            rejected == 0 -> SettlementStatus.Updated
            else -> SettlementStatus.Partial
        }
    }

    /**
     * G3 的动态上界求值。
     *
     * 🔴 这里藏着一个必须守住的实证教训。
     *
     * 实测过的一份外部数据里，`某个上限字段` 的初始值是 **0**
     * —— 它是「战斗还没开始」的占位值，不是真的上限。
     * 如果这里老老实实把 0 当上界用，那么相关数值一出现就会被夹到 0，
     * 游戏直接玩不下去。
     *
     * 所以规则是：**引用路径的值 ≤ 0 时，视为「上界未就绪」，跳过夹取**。
     * 宁可放过一个超限值，也不能让内容无法进行 —— 这就是那条兼容硬底线：
     * 导入的 外部数据默认不得因为我们的守卫而比在原生酒馆里表现更差。
     */
    private fun resolveUpperBound(spec: FieldSpec, current: Map<String, StateValue>): Double? {
        val fromPath = spec.maxFromPath
        if (fromPath != null) {
            val dynamic = current[fromPath]?.asNumberOrNull()
            if (dynamic != null && dynamic > 0.0) return dynamic
            // 动态上界不可用时，退回静态 max（通常也是 null，即不夹）
        }
        return spec.max
    }

    /**
     * G2 的类型转换。**有限度的宽容**。
     *
     * 为什么要宽容：实测里模型经常把数字写成字符串（`"49"` 而不是 `49`），
     * 尤其是在 JSON 被 markdown 围栏包过一道之后。为这种小事丢弃一条合法变更，
     * 会让使用者觉得系统很蠢。
     *
     * 为什么要有限度：我们只做**无歧义**的转换。
     * - `"49"` → 49 ✅（字符串里确实只有一个数字）
     * - `49` → `"49"` ✅（数字转字符串永远无歧义）
     * - `"很多"` → ? ❌ 拒绝，不猜
     * - `1` → `true` ❌ **刻意不支持**。中文语境里 1/0 当布尔太容易出歧义
     *   （「剩余 1 次」vs「是」），猜错的代价高于收益。
     *
     * @return 转换后的值；无法安全转换时返回 null（调用方记 TypeMismatch）。
     */
    private fun coerce(value: StateValue, spec: FieldSpec): StateValue? = when (spec.type) {
        FieldType.Any -> value

        FieldType.Int -> when (value) {
            is StateValue.IntValue -> value
            // 浮点转整数用四舍五入而不是截断：模型给 49.6 时，
            // 使用者心理预期是 50 而不是 49。
            is StateValue.DoubleValue -> StateValue.IntValue(kotlin.math.round(value.value).toLong())
            is StateValue.StringValue -> value.value.trim().toLongOrNull()?.let(StateValue::IntValue)
                ?: value.value.trim().toDoubleOrNull()
                    ?.let { StateValue.IntValue(kotlin.math.round(it).toLong()) }
            else -> null
        }

        FieldType.Double -> when (value) {
            is StateValue.DoubleValue -> value
            is StateValue.IntValue -> StateValue.DoubleValue(value.value.toDouble())
            is StateValue.StringValue -> value.value.trim().toDoubleOrNull()
                ?.let(StateValue::DoubleValue)
            else -> null
        }

        FieldType.String -> when (value) {
            is StateValue.StringValue -> value
            is StateValue.IntValue -> StateValue.StringValue(value.value.toString())
            is StateValue.DoubleValue -> StateValue.StringValue(value.value.toString())
            // 布尔不转字符串：true 该写成 "true" 还是 "是"？没有标准答案，不猜。
            else -> null
        }

        FieldType.Bool -> when (value) {
            is StateValue.BoolValue -> value
            is StateValue.StringValue -> when (value.value.trim().lowercase()) {
                "true", "是", "yes" -> StateValue.BoolValue(true)
                "false", "否", "no" -> StateValue.BoolValue(false)
                else -> null
            }
            else -> null
        }

        FieldType.Array -> when (value) {
            is StateValue.ArrayValue -> {
                val elementSpec = spec.elementType
                if (elementSpec == null) {
                    value
                } else {
                    // 逐元素转换。**一个元素转不了，整条数组就拒绝** ——
                    // 默默丢掉几个元素比整条拒绝更危险（列表内容会凭空少掉）。
                    val converted = value.items.map { item ->
                        coerce(item, FieldSpec(type = elementSpec)) ?: return null
                    }
                    StateValue.ArrayValue(converted)
                }
            }
            else -> null
        }
    }

    /**
     * 把夹取完的 Double 还原成字段声明的类型。
     *
     * @param fallback 没有发生夹取时的原值，直接复用以避免精度抖动。
     */
    private fun rebuildNumber(
        adjusted: Double,
        type: FieldType,
        fallback: StateValue,
    ): StateValue = when (type) {
        FieldType.Int -> StateValue.IntValue(kotlin.math.round(adjusted).toLong())
        FieldType.Double -> StateValue.DoubleValue(adjusted)
        // Any 类型：保持原来是整数就还是整数
        FieldType.Any -> if (fallback is StateValue.IntValue) {
            StateValue.IntValue(kotlin.math.round(adjusted).toLong())
        } else {
            StateValue.DoubleValue(adjusted)
        }
        else -> fallback
    }
}
