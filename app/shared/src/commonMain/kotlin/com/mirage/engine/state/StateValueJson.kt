package com.mirage.engine.state

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 状态值与结构化文本之间的互转。
 *
 * 两个方向都会被用到：读取模型输出时要把文本转成状态值，
 * 而把当前状态交给模型作为上下文时要反向转一次。
 * 这两次转换必须严格互为逆运算，否则会出现「发出去的和读回来的对不上」
 * 这种极难定位的问题 —— 表现是某个字段每轮都被判定为发生了变化。
 * 放在同一处是为了让这种对称性显而易见、也便于一起测。
 */
object StateValueJson {

    /**
     * 把状态值转成结构化节点。
     *
     * @return [StateValue.Absent] 返回 null。它表示「这个字段没有被提到」，
     *   不是一个可以被序列化的值。把它写成任何具体内容都会丢失这层含义，
     *   而「没提到」和「被设成空」正是最容易混淆、后果也最严重的一对概念。
     */
    fun encode(value: StateValue): JsonElement? = when (value) {
        is StateValue.IntValue -> JsonPrimitive(value.value)
        is StateValue.DoubleValue -> JsonPrimitive(value.value)
        is StateValue.StringValue -> JsonPrimitive(value.value)
        is StateValue.BoolValue -> JsonPrimitive(value.value)
        is StateValue.ArrayValue -> buildJsonArray {
            value.items.forEach { item -> encode(item)?.let { add(it) } }
        }

        StateValue.NullValue -> JsonNull
        StateValue.Absent -> null
    }

    /**
     * 把结构化节点转成状态值。
     *
     * 判定顺序有讲究：空值必须最先判，因为它在结构上也属于基本类型；
     * 字符串必须在数字之前判，否则引号包起来的数字会被当成数字 ——
     * 而「写成文本的数字」和「数字」在类型裁决环节是两种不同情况，
     * 提前归一会让那一层失去判断依据。
     *
     * @return 无法表达为状态值时返回 null（例如对象，它应当先被展平成路径）。
     */
    fun decode(element: JsonElement): StateValue? = when (element) {
        is JsonNull -> StateValue.NullValue

        is JsonPrimitive -> when {
            element.isString -> StateValue.StringValue(element.content)
            element.booleanOrNull != null -> StateValue.BoolValue(element.booleanOrNull!!)
            element.longOrNull != null -> StateValue.IntValue(element.longOrNull!!)
            element.doubleOrNull != null -> StateValue.DoubleValue(element.doubleOrNull!!)
            else -> null
        }

        is JsonArray -> {
            // 有一个元素转不了就整条放弃。默默丢掉几个元素会让列表悄悄变短，
            // 而这种缺失几乎不可能被及时发现
            val items = element.map { decode(it) }
            if (items.any { it == null }) null else StateValue.ArrayValue(items.filterNotNull())
        }

        is JsonObject -> null
    }

    /**
     * 状态值转成一段文本，用于存库。
     *
     * 存文本而不是给每种类型建一列：状态值有六种形态（整数、小数、文本、是否、
     * 列表、空），分列存要六列加一个类型标记，读的时候还要按标记挑列。
     * 而我们从不按状态值的内容查询（只按路径查），所以分列的唯一好处用不上。
     */
    fun encodeToText(value: StateValue): String =
        encode(value)?.let { json.encodeToString(JsonElement.serializer(), it) } ?: "null"

    /** 从库里读出来的文本转回状态值。认不出来时返回空值，不抛异常。 */
    fun decodeFromText(text: String): StateValue = try {
        decode(json.parseToJsonElement(text)) ?: StateValue.NullValue
    } catch (failure: Exception) {
        // 存坏了一条就让整局存档打不开，代价太大。
        // 退回空值至少能让其余内容正常显示出来
        StateValue.NullValue
    }

    private val json = Json

    /**
     * 一整张状态栏转成文本。给「存一份快照加速回放」用。
     */
    fun encodeMap(values: Map<String, StateValue>): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            values.forEach { (path, value) -> encode(value)?.let { put(path, it) } }
        },
    )

    /** 文本转回一整张状态栏。 */
    fun decodeMap(text: String): Map<String, StateValue> = try {
        (json.parseToJsonElement(text) as? JsonObject)
            ?.mapNotNull { (path, element) -> decode(element)?.let { path to it } }
            ?.toMap()
            ?: emptyMap()
    } catch (failure: Exception) {
        emptyMap()
    }
}
