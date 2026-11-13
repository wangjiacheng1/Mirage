package com.mirage.engine.store

import com.mirage.engine.state.ChangeOperation
import com.mirage.engine.state.Disposition
import com.mirage.engine.state.FieldType
import com.mirage.engine.state.GuardFailure
import com.mirage.engine.state.GuardFailureCode
import com.mirage.engine.state.GuardFailureDetail
import com.mirage.engine.state.Monotonic
import com.mirage.engine.state.StateChange
import com.mirage.engine.state.StateValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 结算回执存库与读回的单测。
 *
 * 回执是「为什么数值变成这样」的唯一交代。存丢了，用户重启后会看到
 * 一个变过的数字却没有任何说明 —— 比干脆不显示更让人起疑。
 */
class ReceiptJsonTest {

    @Test
    fun 变化列表存读一致() {
        val changes = listOf(
            StateChange(
                path = "某组/甲",
                operation = ChangeOperation.Set,
                before = StateValue.IntValue(60),
                after = StateValue.IntValue(49),
                reason = "被咬了一口",
                adjustedBy = GuardFailureCode.Clamped,
            ),
            StateChange(
                path = "某组/乙",
                operation = ChangeOperation.Set,
                before = StateValue.StringValue("门厅"),
                after = StateValue.StringValue("走廊"),
            ),
        )

        assertEquals(changes, ReceiptJson.decodeChanges(ReceiptJson.encodeChanges(changes)))
    }

    @Test
    fun 五种拦截补充参数都存得回来() {
        val failures = listOf(
            GuardFailure(
                path = "某组/甲",
                code = GuardFailureCode.TypeMismatch,
                proposed = StateValue.StringValue("很多"),
                disposition = Disposition.Rejected,
                displayName = "甲",
                detail = GuardFailureDetail.TypeExpectation(FieldType.Int, FieldType.String),
            ),
            GuardFailure(
                path = "某组/乙",
                code = GuardFailureCode.IllegalEnum,
                proposed = StateValue.StringValue("战斗"),
                disposition = Disposition.Rejected,
                detail = GuardFailureDetail.AllowedValues(listOf("平静", "紧张")),
            ),
            GuardFailure(
                path = "某组/丙",
                code = GuardFailureCode.DirectionViolation,
                proposed = StateValue.IntValue(1),
                disposition = Disposition.Rejected,
                detail = GuardFailureDetail.DirectionRequirement(Monotonic.Increasing),
            ),
            GuardFailure(
                path = "某组/丁",
                code = GuardFailureCode.DeltaTooLarge,
                proposed = StateValue.IntValue(99),
                disposition = Disposition.Clamped,
                detail = GuardFailureDetail.DeltaLimit(limit = 40.0, attempted = 60.0, applied = 40.0),
            ),
            GuardFailure(
                path = "某组/戊",
                code = GuardFailureCode.Clamped,
                proposed = StateValue.IntValue(80),
                disposition = Disposition.Clamped,
                detail = GuardFailureDetail.RangeClamp(
                    applied = StateValue.IntValue(60),
                    lowerBound = 0.0,
                    upperBound = 60.0,
                ),
            ),
        )

        assertEquals(failures, ReceiptJson.decodeFailures(ReceiptJson.encodeFailures(failures)))
    }

    @Test
    fun 没有补充参数的拦截也存得回来() {
        val failures = listOf(
            GuardFailure(
                path = "某组/甲",
                code = GuardFailureCode.UnknownPath,
                proposed = StateValue.IntValue(1),
                disposition = Disposition.Rejected,
            ),
        )

        val restored = ReceiptJson.decodeFailures(ReceiptJson.encodeFailures(failures))
        assertEquals(failures, restored)
        assertNull(restored.single().detail)
    }

    @Test
    fun 空列表存读一致() {
        assertEquals(emptyList(), ReceiptJson.decodeChanges(ReceiptJson.encodeChanges(emptyList())))
        assertEquals(emptyList(), ReceiptJson.decodeFailures(ReceiptJson.encodeFailures(emptyList())))
    }

    @Test
    fun 内容坏掉时当空列表而不是抛异常() {
        // 一条坏记录不该让整局存档打不开
        assertEquals(emptyList(), ReceiptJson.decodeChanges("这不是 JSON"))
        assertEquals(emptyList(), ReceiptJson.decodeFailures("{坏掉的内容"))
    }

    @Test
    fun 认不出的补充参数退回空而不是整条丢掉() {
        // 旧版本读到新版本写的数据时会这样。拦截本身还得显示出来，
        // 只是少了括号里那几个数字
        val restored = ReceiptJson.decodeFailures(
            """[{"path":"某组/甲","code":"Clamped","proposed":"1","disposition":"Clamped",
               "detail":{"kind":"将来才有的种类"}}]""",
        )

        assertEquals(1, restored.size)
        assertNull(restored.single().detail)
    }
}
