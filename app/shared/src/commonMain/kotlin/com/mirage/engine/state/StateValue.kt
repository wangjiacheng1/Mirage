package com.mirage.engine.state

/**
 * ============================================================================
 * StateValue —— Mirage 状态系统的「值」类型
 * ============================================================================
 *
 * ## 为什么要自己定义一个值类型，而不直接用 kotlinx.serialization 的 JsonElement？
 *
 * 1. **守卫逻辑要做数值比较和夹取（clamp）**。JsonElement 里数字是字符串形态
 *    （`JsonPrimitive.content`），每次比较都要 parse，容易出 bug，也丢失
 *    「这到底是 Int 还是 Double」的信息。
 * 2. **引擎核心不应该依赖序列化框架**。G1~G8 守卫是整套设计的核心，
 *    它必须是纯 Kotlin、零依赖、可以在 commonTest 里裸跑的代码。JSON 的解析/
 *    生成放在边界层（adapter）里做，核心层只认 StateValue。
 * 3. **我们需要一个 JSON 没有的概念：`Absent`**。
 *    模型提议里「没有提到这个字段」和「把这个字段设成 null」是两件完全不同的事。
 *    JSON 里两者都容易塌缩成 null，我们必须区分开，否则会发生
 *    「模型没提到某个字段 → 该字段被清空」这种灾难。
 *
 * ## 设计取舍
 *
 * 刻意**不支持嵌套对象**作为 StateValue。Mirage 的状态是「扁平路径 → 标量/数组」
 * 的映射（例如 `某分组/某字段` → 49）。嵌套结构在 schema 定义时被展平成路径，
 * 这样 G1 路径白名单才能做精确的集合查找，而不用递归比对树形结构。
 * 这个决定来自对上游同类框架的观察：它的 `_.set('某分组.某字段', 49)`
 * 本质上也是「路径 + 标量」，嵌套只是书写形式。
 */
sealed interface StateValue {

    /**
     * 整数。
     *
     * 角色扮演场景里的绝大多数数值（生命、金币、等级、好感度）都是整数。
     * 单独建一个 Int 分支而不是统一用 Double，是为了避免
     * 「生命值 49 显示成 49.0」这种在面板上非常刺眼的问题。
     */
    data class IntValue(val value: Long) : StateValue

    /**
     * 浮点数。用于少数确实需要小数的场合（如倍率、百分比）。
     */
    data class DoubleValue(val value: Double) : StateValue

    /** 字符串。例如 `某个枚举字段` → "门厅"。 */
    data class StringValue(val value: String) : StateValue

    /** 布尔。例如 `某个布尔字段` → false。 */
    data class BoolValue(val value: Boolean) : StateValue

    /**
     * 数组。例如 `某个列表字段` → ["狼牙", "破布"]。
     *
     * 元素类型由 schema 的 `elementType` 约束，G2 会逐元素校验。
     */
    data class ArrayValue(val items: List<StateValue>) : StateValue

    /**
     * 显式的 null。
     *
     * 「这个字段现在确实没有值」，例如战斗结束后 `某个文本字段` → null。
     * 注意这和 [Absent] 的区别，见下。
     */
    data object NullValue : StateValue

    /**
     * **字段缺席** —— 不是一个真正的值，而是「模型本轮没有提到这个路径」。
     *
     * 🔴 这是整个状态系统最容易出事的地方，所以单独建一个类型让编译器帮我们盯着。
     *
     * 典型事故（实测中有同类产品正是这样，表现为数值「只增不减」）：
     * 把「没提到」当成「设为 0 / 清空」，于是使用者的数据在某一轮凭空消失。
     *
     * Mirage 的规则：[Absent] 永远表示「保持原值不变」，任何守卫遇到它都直接
     * 跳过该路径，不产生 change、不产生 failure。
     */
    data object Absent : StateValue
}
/**
 * 取数值（Long/Double 统一成 Double 便于比较与夹取）。
 *
 * 返回 null 表示「这不是一个数值」—— 调用方（G3 值域、G5 幅度、G7 单调）
 * 必须显式处理 null，不能默默当成 0。
 */
fun StateValue.asNumberOrNull(): Double? = when (this) {
    is StateValue.IntValue -> value.toDouble()
    is StateValue.DoubleValue -> value
    else -> null
}
