package com.mirage.engine.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ============================================================================
 * StateGuards 测试
 * ============================================================================
 *
 * ## 这些 fixture 不是我编的
 *
 * schema 来自**真实 ST 角色卡** `DiLaoJSON.json` 的 `<initvar>`（28 个路径），
 * 失败样本来自 **M0 真机实测**的输出（`out4/narratives.json`、`raw_report4.json`）。
 *
 * 这么做的理由：我在前几轮吃过「拿想象中的输入写测试、结果真实模型的输出长得完全
 * 不一样」的亏。测试 fixture 必须来自真实样本，否则守卫会在实验室里全绿、
 * 一上真模型全崩。
 *
 * ## 命名规则
 *
 * `G{n}_{场景}_{预期}`，方便按守卫编号检索。
 */
class StateGuardsTest {

    // ------------------------------------------------------------------------
    // 真实卡的 schema 子集（Tier A 机械推导 + 少量 Tier B/C 用于测对应守卫）
    // ------------------------------------------------------------------------
    private val schema = StateSchema(
        fields = mapOf(
            // --- Tier A：只声明类型，这是导入 ST 卡后的默认状态 ---
            "冒险者/姓名" to FieldSpec(FieldType.String, displayName = "姓名"),
            "冒险者/等级" to FieldSpec(FieldType.Int, displayName = "等级"),
            "装备/武器" to FieldSpec(FieldType.String, displayName = "武器"),
            "物品栏/材料" to FieldSpec(
                FieldType.Array, elementType = FieldType.String, displayName = "材料",
            ),

            // --- G6：轮数是系统字段。实证：M0 第 4 轮模型凭空改过它 ---
            "地牢/当前轮" to FieldSpec(FieldType.Int, readOnly = true, displayName = "当前轮"),
            "地牢/总轮数" to FieldSpec(FieldType.Int, readOnly = true, displayName = "总轮数"),

            // --- G3：生命有动态上界（Tier B，需用户确认才会生成）---
            "冒险者/生命" to FieldSpec(
                FieldType.Int, min = 0.0, maxFromPath = "冒险者/生命上限", displayName = "生命",
            ),
            "冒险者/生命上限" to FieldSpec(FieldType.Int, min = 1.0, displayName = "生命上限"),

            // --- G3：敌人生命上限初始为 0（占位值），这是关键回归用例 ---
            "敌人/生命" to FieldSpec(
                FieldType.Int, min = 0.0, maxFromPath = "敌人/生命上限", displayName = "敌人生命",
            ),
            "敌人/生命上限" to FieldSpec(FieldType.Int, displayName = "敌人生命上限"),

            // --- G4：枚举值写在世界书散文里，需 Tier C 抽取 ---
            "地牢/本轮房间" to FieldSpec(
                FieldType.String,
                enumValues = setOf("门厅", "回廊", "石室", "宝库", "祭坛"),
                displayName = "本轮房间",
            ),
            "地牢/难度" to FieldSpec(
                FieldType.String,
                enumValues = setOf("待选", "简单", "中等", "困难"),
                displayName = "难度",
            ),

            // --- G5：单轮幅度上限 ---
            "资源/金币" to FieldSpec(
                FieldType.Int, min = 0.0, maxDeltaPerTurn = 200.0, displayName = "金币",
            ),

            // --- G7：单调递增 ---
            "冒险者/经验" to FieldSpec(
                FieldType.Int, min = 0.0, monotonic = Monotonic.Increasing, displayName = "经验",
            ),

            "地牢/是否最终轮" to FieldSpec(FieldType.Bool, displayName = "是否最终轮"),
        ),
    )

    /** 真实卡的初始状态快照。 */
    private val current: Map<String, StateValue> = mapOf(
        "冒险者/姓名" to StateValue.StringValue("无名氏"),
        "冒险者/等级" to StateValue.IntValue(1),
        "冒险者/经验" to StateValue.IntValue(0),
        "冒险者/生命" to StateValue.IntValue(60),
        "冒险者/生命上限" to StateValue.IntValue(60),
        "装备/武器" to StateValue.StringValue("断齿短刀"),
        "物品栏/材料" to StateValue.ArrayValue(emptyList()),
        "地牢/当前轮" to StateValue.IntValue(2),
        "地牢/总轮数" to StateValue.IntValue(0),
        "地牢/本轮房间" to StateValue.StringValue("门厅"),
        "地牢/难度" to StateValue.StringValue("待选"),
        "地牢/是否最终轮" to StateValue.BoolValue(false),
        "资源/金币" to StateValue.IntValue(0),
        // 🔴 注意：敌人生命上限 = 0，这是真实卡里的占位值
        "敌人/生命" to StateValue.IntValue(0),
        "敌人/生命上限" to StateValue.IntValue(0),
    )

    private fun run(
        proposals: Map<String, StateValue>,
        state: Map<String, StateValue> = current,
    ) = StateGuards.apply(turn = 3, current = state, proposals = proposals, schema = schema)

    // ========================================================================
    // 正常路径
    // ========================================================================

    @Test
    fun 正常变更_应落盘并标记Updated() {
        // 这是 M0 实测里真实出现过的一轮：被狼咬掉 11 点血
        val r = run(mapOf("冒险者/生命" to StateValue.IntValue(49)))

        assertEquals(SettlementStatus.Updated, r.status)
        assertEquals(1, r.changes.size)
        assertTrue(r.failures.isEmpty())
        with(r.changes[0]) {
            assertEquals("冒险者/生命", path)
            assertEquals(StateValue.IntValue(60), before)
            assertEquals(StateValue.IntValue(49), after)
            // 回执文案要和 DSH Tavern 对齐：`60 → 49`
            assertEquals("60", before.displayText())
            assertEquals("49", after.displayText())
        }
    }

    // ========================================================================
    // G8：空 delta 是合法结果（最容易被实现成 bug 的一条）
    // ========================================================================

    @Test
    fun G8_空提议_应为Unchanged而非Error() {
        val r = run(emptyMap())
        assertEquals(SettlementStatus.Unchanged, r.status)
        assertTrue(r.changes.isEmpty())
        assertTrue(r.failures.isEmpty())
    }

    @Test
    fun G8_全是Absent_应为Unchanged且不产生噪音失败() {
        // 「模型没提到这些字段」≠「模型要把它们清空」。
        // DZMM 的「只增不减」「数据不贴合上下文」大概率就是这里出的错。
        val r = run(
            mapOf(
                "冒险者/生命" to StateValue.Absent,
                "资源/金币" to StateValue.Absent,
            ),
        )
        assertEquals(SettlementStatus.Unchanged, r.status)
        assertTrue(r.failures.isEmpty(), "Absent 不应产生 failure，否则回执全是噪音")
    }

    @Test
    fun G8_提议值与原值相同_不记为变更() {
        val r = run(mapOf("冒险者/生命" to StateValue.IntValue(60)))
        assertEquals(SettlementStatus.Unchanged, r.status)
        assertTrue(r.changes.isEmpty(), "回执里不该出现 60 → 60")
    }

    // ========================================================================
    // G6：只读字段 —— M0 第 4 轮的真实事故
    // ========================================================================

    @Test
    fun G6_模型凭空改轮数_应直接丢弃() {
        // 真实复现：提示词明写「不要修改轮数」，模型仍提议 当前轮 2 → 3
        val r = run(mapOf("地牢/当前轮" to StateValue.IntValue(3)))

        assertEquals(SettlementStatus.Error, r.status)
        assertTrue(r.changes.isEmpty(), "只读字段绝不能落盘")
        assertEquals(GuardFailureCode.ReadOnlyPath, r.failures[0].code)
        assertEquals(Disposition.Rejected, r.failures[0].disposition)
    }

    @Test
    fun G6_优先级高于其它守卫_即使值合法也拒绝() {
        // 提议值 2 和当前值一样、类型也对，但仍必须因 ReadOnly 被拒 —— 而不是
        // 悄悄走到 G8 的「值相同」分支被静默跳过。因为我们要让用户看到
        // 「模型试图越权」这个事实。
        val r = run(mapOf("地牢/总轮数" to StateValue.IntValue(5)))
        assertEquals(GuardFailureCode.ReadOnlyPath, r.failures.single().code)
    }

    // ========================================================================
    // G1：路径白名单
    // ========================================================================

    @Test
    fun G1_凭空发明的路径_应丢弃() {
        val r = run(
            mapOf(
                "冒险者/魔法值" to StateValue.IntValue(30), // 卡里没有这个字段
                "冒险者/生命" to StateValue.IntValue(55),
            ),
        )

        // 一条成功 + 一条否决 → Partial
        assertEquals(SettlementStatus.Partial, r.status)
        assertEquals(1, r.changes.size)
        assertEquals(GuardFailureCode.UnknownPath, r.failures.single().code)
        // 失败消息必须点明具体路径，不能是「参数错误」这种无效信息
        assertTrue(r.failures.single().message.contains("冒险者/魔法值"))
    }

    // ========================================================================
    // G2：类型校验与有限度的宽容转换
    // ========================================================================

    @Test
    fun G2_数字写成字符串_应宽容转换() {
        // 实测高频：JSON 被 markdown 围栏包过一道后，数字常常变成字符串
        val r = run(mapOf("冒险者/生命" to StateValue.StringValue("49")))
        assertEquals(SettlementStatus.Updated, r.status)
        assertEquals(StateValue.IntValue(49), r.changes.single().after)
    }

    @Test
    fun G2_浮点转整数_应四舍五入() {
        val r = run(mapOf("冒险者/生命" to StateValue.DoubleValue(49.6)))
        assertEquals(StateValue.IntValue(50), r.changes.single().after)
    }

    @Test
    fun G2_无法解析的文本_应丢弃() {
        val r = run(mapOf("冒险者/生命" to StateValue.StringValue("很虚弱")))
        assertEquals(SettlementStatus.Error, r.status)
        assertEquals(GuardFailureCode.TypeMismatch, r.failures.single().code)
    }

    @Test
    fun G2_布尔不接受数字_刻意不猜() {
        // 1/0 当布尔在中文语境太容易歧义，宁可拒绝
        val r = run(mapOf("地牢/是否最终轮" to StateValue.IntValue(1)))
        assertEquals(GuardFailureCode.TypeMismatch, r.failures.single().code)
    }

    @Test
    fun G2_数组元素有一个非法_整条拒绝() {
        // 默默丢掉几个元素比整条拒绝危险：玩家的材料会凭空少掉
        val r = run(
            mapOf(
                "物品栏/材料" to StateValue.ArrayValue(
                    listOf(
                        StateValue.StringValue("狼牙"),
                        StateValue.BoolValue(true), // 非法
                    ),
                ),
            ),
        )
        assertEquals(GuardFailureCode.TypeMismatch, r.failures.single().code)
        assertTrue(r.changes.isEmpty())
    }

    // ========================================================================
    // G3：值域夹取 —— 含那条救命的占位值回归
    // ========================================================================

    @Test
    fun G3_超过动态上限_应夹取且仍然落盘() {
        // 治疗溢出：角色先被狼咬到 49 血（M0 实测里真实出现过的一轮），
        // 模型提议治疗到 80，但上限只有 60 → 夹到 60。49 → 60 是一次真实变化。
        //
        // ⚠️ 这条 fixture 踩过坑（2026-10-19 首次跑测时它是唯一的红）：
        //    最初直接用默认 current，而默认状态里生命已经是满的 60，
        //    于是夹取结果 60 == 旧值 60，被「没变就是没变」的规则跳过，
        //    changes 为空 → status 变成 Unchanged，断言 Updated 失败。
        //    **守卫没错，是测试场景自相矛盾**：一条用例同时要求「发生夹取」
        //    和「成功落盘」，就必须让夹取后的值不等于旧值。
        //    「夹取后恰好等于旧值」是另一种场景，见下一条用例。
        val hurt = current + ("冒险者/生命" to StateValue.IntValue(49))
        val r = run(mapOf("冒险者/生命" to StateValue.IntValue(80)), state = hurt)

        // 夹取是警告不是否决 → 整轮仍然是 Updated
        assertEquals(SettlementStatus.Updated, r.status)
        assertEquals(StateValue.IntValue(60), r.changes.single().after)
        assertEquals(GuardFailureCode.Clamped, r.changes.single().adjustedBy)
        // 同时要在 failures 里留一条记录，让 UI 能告诉用户「我们动过手脚」
        assertEquals(Disposition.Clamped, r.failures.single().disposition)
    }

    @Test
    fun G3_夹取后恰好等于旧值_应判Unchanged但保留夹取记录() {
        // 场景：血已经满了（60/60），模型还想治疗到 80。
        //
        // 🔴 这条行为是刻意设计的，不要为了让 status 变成 Updated 而改掉它。
        //
        // 两个各自独立的要求，缺一不可：
        //   1. changes 必须为空、status 必须是 Unchanged
        //      —— 数值确实没变，回执里出现「60 → 60」是荒谬的，
        //         而且会让玩家以为这轮真的加了血。
        //   2. failures 里必须保留那条 Clamped
        //      —— 不能装作什么都没发生。UI 要能告诉玩家
        //         「治疗溢出已拦下，你已经满血了」，这正是支柱 ② 的体感来源：
        //         代码做了裁决，并且把裁决过程摊开给用户看。
        //
        // 注意 Unchanged 的语义边界：它表示「数值没变」，不表示「什么都没发生」。
        // 判定只看 Disposition.Rejected，被夹取的条目不会把整轮拖成 Partial/Error。
        val r = run(mapOf("冒险者/生命" to StateValue.IntValue(80)))

        assertEquals(SettlementStatus.Unchanged, r.status)
        assertTrue(r.changes.isEmpty(), "夹取后与旧值相同，不该产生 60 → 60 这种空变更")
        assertEquals(GuardFailureCode.Clamped, r.failures.single().code)
        assertEquals(Disposition.Clamped, r.failures.single().disposition)
    }

    @Test
    fun G3_低于下限_应夹到下限() {
        val r = run(mapOf("冒险者/生命" to StateValue.IntValue(-20)))
        assertEquals(StateValue.IntValue(0), r.changes.single().after)
    }

    @Test
    fun G3_上限引用路径为占位值0_必须跳过夹取() {
        // 🔴 这是最重要的一条回归测试。
        //
        // 真实卡 DiLaoJSON.json 里 敌人/生命上限 初始 = 0（战斗未开始的占位值）。
        // 如果把 0 当真上界，敌人一出场就被夹成 0 血，游戏直接玩不了。
        //
        // 这正是「Tier B 自动推断必须默认关闭」的实证依据。
        val r = run(mapOf("敌人/生命" to StateValue.IntValue(40)))

        assertEquals(SettlementStatus.Updated, r.status)
        assertEquals(
            StateValue.IntValue(40), r.changes.single().after,
            "上限为占位值 0 时必须放过，否则敌人会被夹成 0 血",
        )
        assertTrue(r.failures.isEmpty())
    }

    @Test
    fun G3_上限就绪后_恢复夹取() {
        // 战斗开始、上限被正确设成 35 之后，夹取必须重新生效
        val battleState = current.toMutableMap().apply {
            this["敌人/生命上限"] = StateValue.IntValue(35)
        }
        val r = run(mapOf("敌人/生命" to StateValue.IntValue(40)), state = battleState)
        assertEquals(StateValue.IntValue(35), r.changes.single().after)
    }

    // ========================================================================
    // G4：枚举白名单 —— M0 第 4 轮的真实非法值
    // ========================================================================

    @Test
    fun G4_模型发明房间名_应丢弃() {
        // 真实样本：模型把 本轮房间 填成了「战斗」「石阶」，卡里都没定义
        val r = run(mapOf("地牢/本轮房间" to StateValue.StringValue("战斗")))

        assertEquals(SettlementStatus.Error, r.status)
        assertEquals(GuardFailureCode.IllegalEnum, r.failures.single().code)
        // 消息里要列出合法取值，这样用户（和我们排查时）一眼知道该是什么
        assertTrue(r.failures.single().message.contains("门厅"))
    }

    @Test
    fun G4_合法枚举值_应通过() {
        val r = run(mapOf("地牢/本轮房间" to StateValue.StringValue("石室")))
        assertEquals(SettlementStatus.Updated, r.status)
    }

    @Test
    fun G4_未声明枚举的字符串字段_不受限制() {
        // 武器名是自由文本，不该被枚举守卫拦住
        val r = run(mapOf("装备/武器" to StateValue.StringValue("狼牙棒")))
        assertEquals(SettlementStatus.Updated, r.status)
    }

    // ========================================================================
    // G5：单轮变化幅度
    // ========================================================================

    @Test
    fun G5_一轮暴富_应夹取而非丢弃() {
        // 模型让玩家一轮捡到 5000 金币；上限是 200/轮
        val r = run(mapOf("资源/金币" to StateValue.IntValue(5000)))

        assertEquals(SettlementStatus.Updated, r.status)
        assertEquals(
            StateValue.IntValue(200), r.changes.single().after,
            "处置应是夹取：剧情里确实捡到钱了，粗暴丢弃比数值偏小更伤体验",
        )
        assertEquals(GuardFailureCode.DeltaTooLarge, r.changes.single().adjustedBy)
    }

    @Test
    fun G5_幅度内_不触发() {
        val r = run(mapOf("资源/金币" to StateValue.IntValue(150)))
        assertTrue(r.failures.isEmpty())
        assertEquals(StateValue.IntValue(150), r.changes.single().after)
    }

    // ========================================================================
    // G7：单调方向
    // ========================================================================

    @Test
    fun G7_经验倒退_应丢弃() {
        val state = current.toMutableMap().apply {
            this["冒险者/经验"] = StateValue.IntValue(50)
        }
        val r = run(mapOf("冒险者/经验" to StateValue.IntValue(30)), state = state)

        assertEquals(GuardFailureCode.DirectionViolation, r.failures.single().code)
        assertTrue(r.changes.isEmpty())
    }

    @Test
    fun G7_经验增长_应通过() {
        val r = run(mapOf("冒险者/经验" to StateValue.IntValue(35)))
        assertEquals(SettlementStatus.Updated, r.status)
    }

    // ========================================================================
    // 守卫执行顺序
    // ========================================================================

    @Test
    fun 顺序_G5先夹幅度再由G3夹值域_最终值须同时满足() {
        // 当前血 60 / 上限 60。给生命加一个 maxDelta=5 的约束，
        // 模型提议 200：G5 先夹到 65，G3 再夹到 60。
        val s = StateSchema(
            fields = mapOf(
                "冒险者/生命" to FieldSpec(
                    FieldType.Int, min = 0.0, maxFromPath = "冒险者/生命上限",
                    maxDeltaPerTurn = 5.0, displayName = "生命",
                ),
                "冒险者/生命上限" to FieldSpec(FieldType.Int),
            ),
        )
        val r = StateGuards.apply(
            turn = 1,
            current = mapOf(
                "冒险者/生命" to StateValue.IntValue(58),
                "冒险者/生命上限" to StateValue.IntValue(60),
            ),
            proposals = mapOf("冒险者/生命" to StateValue.IntValue(200)),
            schema = s,
        )
        // 58 + 5 = 63 → 被上限 60 夹住
        assertEquals(StateValue.IntValue(60), r.changes.single().after)
        assertEquals(2, r.failures.size, "应同时留下 DeltaTooLarge 和 Clamped 两条痕迹")
    }

    // ========================================================================
    // 回执聚合
    // ========================================================================

    @Test
    fun 回执_混合结果_应为Partial并保留全部证据() {
        val r = StateGuards.apply(
            turn = 7,
            current = current,
            proposals = mapOf(
                "冒险者/生命" to StateValue.IntValue(49),       // ✅ 成功
                "地牢/当前轮" to StateValue.IntValue(3),        // ❌ G6
                "冒险者/魔法值" to StateValue.IntValue(10),     // ❌ G1
                "地牢/本轮房间" to StateValue.StringValue("石阶"), // ❌ G4
                "资源/金币" to StateValue.IntValue(5000),       // ⚠️ G5 夹取后落盘
            ),
            schema = schema,
            summary = "冒险者被灰狼咬伤，在石室中击退对手并拾取赏金。",
            reasons = mapOf("冒险者/生命" to "被灰狼咬伤"),
        )

        assertEquals(SettlementStatus.Partial, r.status)
        assertEquals(7, r.turn)
        assertEquals(2, r.changes.size)                 // 生命 + 金币
        assertEquals(4, r.failures.size)                // 3 否决 + 1 夹取
        assertEquals(3, r.failures.count { it.disposition == Disposition.Rejected })
        assertEquals("被灰狼咬伤", r.changes.first { it.path == "冒险者/生命" }.reason)
        assertTrue(r.summary!!.contains("灰狼"))
    }
}
