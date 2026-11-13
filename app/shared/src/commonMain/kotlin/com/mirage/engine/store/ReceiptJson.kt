package com.mirage.engine.store

import com.mirage.engine.state.ChangeOperation
import com.mirage.engine.state.Disposition
import com.mirage.engine.state.FieldType
import com.mirage.engine.state.GuardFailure
import com.mirage.engine.state.GuardFailureCode
import com.mirage.engine.state.GuardFailureDetail
import com.mirage.engine.state.Monotonic
import com.mirage.engine.state.StateChange
import com.mirage.engine.state.StateValueJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull

/**
 * 结算回执与存库文本之间的互转。
 *
 * 回执里的变化和拦截都存成一段文本，不各建一张表。理由是我们从不按它们查询
 * （永远是「把某一轮的回执整个取出来显示」），分表只换来几次 join 和一堆外键。
 *
 * ## 为什么手写而不是给引擎类型加序列化标注
 *
 * 拦截的补充参数是一组**形状各不相同**的变体（有的带上下界、有的带允许值列表）。
 * 自动序列化这种结构要额外配置类型标记，而那个标记会成为存档格式的一部分 ——
 * 以后改类名就等于改了用户硬盘上的数据格式。手写之后，字段名由我们自己定，
 * 改代码不会动到已经存下去的东西。
 */
object ReceiptJson {

    private val json = Json { ignoreUnknownKeys = true }

    /** 变化列表转文本。 */
    fun encodeChanges(changes: List<StateChange>): String = json.encodeToString(
        JsonArray.serializer(),
        buildJsonArray {
            changes.forEach { change ->
                add(
                    buildJsonObject {
                        put("path", JsonPrimitive(change.path))
                        put("operation", JsonPrimitive(change.operation.name))
                        put("before", JsonPrimitive(StateValueJson.encodeToText(change.before)))
                        put("after", JsonPrimitive(StateValueJson.encodeToText(change.after)))
                        change.reason?.let { put("reason", JsonPrimitive(it)) }
                        change.adjustedBy?.let { put("adjustedBy", JsonPrimitive(it.name)) }
                    },
                )
            }
        },
    )

    /** 文本转回变化列表。 */
    fun decodeChanges(text: String): List<StateChange> = readArray(text).mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        StateChange(
            path = obj.text("path") ?: return@mapNotNull null,
            operation = enumOf(obj.text("operation"), ChangeOperation.entries, ChangeOperation.Set),
            before = StateValueJson.decodeFromText(obj.text("before").orEmpty()),
            after = StateValueJson.decodeFromText(obj.text("after").orEmpty()),
            reason = obj.text("reason"),
            adjustedBy = obj.text("adjustedBy")?.let { name ->
                GuardFailureCode.entries.firstOrNull { it.name == name }
            },
        )
    }

    /** 拦截列表转文本。 */
    fun encodeFailures(failures: List<GuardFailure>): String = json.encodeToString(
        JsonArray.serializer(),
        buildJsonArray {
            failures.forEach { failure ->
                add(
                    buildJsonObject {
                        put("path", JsonPrimitive(failure.path))
                        put("code", JsonPrimitive(failure.code.name))
                        put("proposed", JsonPrimitive(StateValueJson.encodeToText(failure.proposed)))
                        put("disposition", JsonPrimitive(failure.disposition.name))
                        failure.displayName?.let { put("displayName", JsonPrimitive(it)) }
                        failure.detail?.let { put("detail", encodeDetail(it)) }
                    },
                )
            }
        },
    )

    /** 文本转回拦截列表。 */
    fun decodeFailures(text: String): List<GuardFailure> = readArray(text).mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        GuardFailure(
            path = obj.text("path") ?: return@mapNotNull null,
            code = enumOf(obj.text("code"), GuardFailureCode.entries, GuardFailureCode.UnknownPath),
            proposed = StateValueJson.decodeFromText(obj.text("proposed").orEmpty()),
            disposition = enumOf(obj.text("disposition"), Disposition.entries, Disposition.Rejected),
            displayName = obj.text("displayName"),
            detail = (obj["detail"] as? JsonObject)?.let { decodeDetail(it) },
        )
    }

    /**
     * 拦截的补充参数转文本。
     *
     * 用一个 kind 字段区分是哪一种。字段名写死在这里，和类名脱钩 ——
     * 以后重命名那些类，已经存下去的数据不受影响。
     */
    private fun encodeDetail(detail: GuardFailureDetail): JsonObject = buildJsonObject {
        when (detail) {
            is GuardFailureDetail.TypeExpectation -> {
                put("kind", JsonPrimitive("type"))
                put("expected", JsonPrimitive(detail.expected.name))
                detail.expectedElement?.let { put("expectedElement", JsonPrimitive(it.name)) }
            }

            is GuardFailureDetail.AllowedValues -> {
                put("kind", JsonPrimitive("allowed"))
                put(
                    "values",
                    buildJsonArray { detail.values.forEach { add(JsonPrimitive(it)) } },
                )
            }

            is GuardFailureDetail.DirectionRequirement -> {
                put("kind", JsonPrimitive("direction"))
                put("required", JsonPrimitive(detail.required.name))
            }

            is GuardFailureDetail.DeltaLimit -> {
                put("kind", JsonPrimitive("delta"))
                put("limit", JsonPrimitive(detail.limit))
                put("attempted", JsonPrimitive(detail.attempted))
                put("applied", JsonPrimitive(detail.applied))
            }

            is GuardFailureDetail.RangeClamp -> {
                put("kind", JsonPrimitive("range"))
                put("applied", JsonPrimitive(StateValueJson.encodeToText(detail.applied)))
                detail.lowerBound?.let { put("lowerBound", JsonPrimitive(it)) }
                detail.upperBound?.let { put("upperBound", JsonPrimitive(it)) }
            }
        }
    }

    /** 认不出的类别返回空，界面会退回到不带数字的简版说法。 */
    private fun decodeDetail(obj: JsonObject): GuardFailureDetail? = when (obj.text("kind")) {
        "type" -> GuardFailureDetail.TypeExpectation(
            expected = enumOf(obj.text("expected"), FieldType.entries, FieldType.Any),
            expectedElement = obj.text("expectedElement")?.let { name ->
                FieldType.entries.firstOrNull { it.name == name }
            },
        )

        "allowed" -> GuardFailureDetail.AllowedValues(
            values = (obj["values"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content }
                ?: emptyList(),
        )

        "direction" -> GuardFailureDetail.DirectionRequirement(
            required = enumOf(obj.text("required"), Monotonic.entries, Monotonic.Increasing),
        )

        "delta" -> GuardFailureDetail.DeltaLimit(
            limit = obj.number("limit") ?: 0.0,
            attempted = obj.number("attempted") ?: 0.0,
            applied = obj.number("applied") ?: 0.0,
        )

        "range" -> GuardFailureDetail.RangeClamp(
            applied = StateValueJson.decodeFromText(obj.text("applied").orEmpty()),
            lowerBound = obj.number("lowerBound"),
            upperBound = obj.number("upperBound"),
        )

        else -> null
    }

    /** 读不出来就当空列表。一条坏记录不该让整局存档打不开。 */
    private fun readArray(text: String): List<kotlinx.serialization.json.JsonElement> = try {
        (json.parseToJsonElement(text) as? JsonArray) ?: emptyList()
    } catch (failure: Exception) {
        emptyList()
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.booleanOrNull == null }?.content

    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

    /** 名字对不上就退回默认值，不抛异常。 */
    private fun <T : Enum<T>> enumOf(name: String?, values: List<T>, fallback: T): T =
        values.firstOrNull { it.name == name } ?: fallback
}
