package com.mirage.android

import com.mirage.engine.state.FieldType
import com.mirage.engine.state.FieldSpec
import com.mirage.engine.state.Monotonic
import com.mirage.engine.state.SchemaOrigin
import com.mirage.engine.state.StateSchema
import com.mirage.engine.state.StateValue

/**
 * ⚠️ 临时占位，接入外部设定文件之后整体删除。
 *
 * 存在的理由只有一个：结算链路需要一份「有哪些数值、各自什么约束」的定义才能跑，
 * 而真正的来源还没做。没有它的话，这条链路在真机上一行都走不通，
 * 只能靠单测覆盖 —— 而单测验不了「人看到这张卡片是什么感受」。
 *
 * 这里刻意把各类约束都放了一个，让每种拦截在真机上都能被触发看到：
 * 有上下界的、有动态上界的、有只读的、有只增的、有枚举的。
 * 真实导入时**不会**是这个样子：外部来源能机械推导出的只有路径和类型，
 * 其余约束默认关闭，由使用者在导入时逐条确认。
 */
object PlaceholderStateProfile {

    const val HEALTH = "角色/生命"
    private const val HEALTH_MAX = "角色/生命上限"
    private const val COIN = "角色/金币"
    private const val PLACE = "角色/位置"
    private const val MOOD = "角色/心情"
    private const val ROUND = "进度/轮次"

    val schema = StateSchema(
        fields = mapOf(
            HEALTH to FieldSpec(
                type = FieldType.Int,
                min = 0.0,
                // 上界取自另一个字段的当前值：上限本身会变，写死就不对了
                maxFromPath = HEALTH_MAX,
                maxDeltaPerTurn = 40.0,
                displayName = "生命",
            ),
            HEALTH_MAX to FieldSpec(type = FieldType.Int, min = 1.0, displayName = "生命上限"),
            COIN to FieldSpec(type = FieldType.Int, min = 0.0, displayName = "金币"),
            PLACE to FieldSpec(type = FieldType.String, displayName = "位置"),
            MOOD to FieldSpec(
                type = FieldType.String,
                enumValues = setOf("平静", "紧张", "愉快", "愤怒"),
                displayName = "心情",
            ),
            ROUND to FieldSpec(
                type = FieldType.Int,
                // 轮次由程序自己推进，模型无权提议 —— 实测中它确实会凭空去改
                readOnly = true,
                monotonic = Monotonic.Increasing,
                displayName = "轮次",
            ),
        ),
        // 不是从外部设定推导出来的，如实标记来源，避免界面上把它说成卡里带的
        origin = SchemaOrigin.UserEdited,
    )

    val initialValues: Map<String, StateValue> = mapOf(
        HEALTH to StateValue.IntValue(60),
        HEALTH_MAX to StateValue.IntValue(60),
        COIN to StateValue.IntValue(10),
        PLACE to StateValue.StringValue("门厅"),
        MOOD to StateValue.StringValue("平静"),
        ROUND to StateValue.IntValue(1),
    )
}
