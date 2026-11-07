package com.mirage.engine.card

import com.mirage.engine.state.FieldSpec
import com.mirage.engine.state.FieldType
import com.mirage.engine.state.SchemaOrigin
import com.mirage.engine.state.StateSchema
import com.mirage.engine.state.StateValue
import com.mirage.engine.state.StateValueJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 从卡里读出来的状态栏初始内容。
 *
 * @property values 状态栏一开始每一项是多少。
 * @property schema 状态栏有哪些项、各自是什么类型。
 * @property metadata 卡里那一小段元信息，原样留着。我们现在不解读它的含义 ——
 *   只知道有人写了这些字段，不知道对方的框架拿它做什么，猜语义会猜错。
 * @property sourceEntryIndex 初始内容是从设定本的第几条读出来的，排查时有用。
 * @property notes 读取过程中跳过或拿不准的地方，逐条记下来给导入界面显示。
 */
data class CardStateInit(
    val values: Map<String, StateValue>,
    val schema: StateSchema,
    val metadata: JsonObject? = null,
    val sourceEntryIndex: Int = -1,
    val notes: List<StateInitNote> = emptyList(),
)

/** 读取时跳过或拿不准的一处。只给类别和位置，话由界面来说。 */
data class StateInitNote(
    val code: StateInitNoteCode,
    val path: String,
)

enum class StateInitNoteCode {
    /** 这一项的值我们表达不了（例如数组里套着对象），已跳过。 */
    ValueNotSupported,

    /** 这是个空数组，所以不知道里面该装什么类型。先不限制。 */
    ArrayElementTypeUnknown,

    /** 数组里混着不同类型的元素，没法给出统一的元素类型。先不限制。 */
    ArrayElementTypeMixed,

    /** 这一组是空的，底下没有任何一项。 */
    EmptyGroup,
}

/**
 * ============================================================================
 * CardStateInitializer —— 从卡里读出状态栏的初始内容
 * ============================================================================
 *
 * 带状态玩法的卡，会在设定本里放一条纯 JSON 的条目，写着状态栏一开始长什么样。
 * 这里把那一条找出来，拆成两样东西：
 *
 * - **一开始每项是多少** —— 直接拿去初始化状态栏；
 * - **有哪些项、各自什么类型** —— 拿去当检查依据：模型以后提到清单外的项、
 *   或者把数字项改成一句话，都会在这一步之后被挡掉。
 *
 * ## 这里只做「数一数、看一眼类型」，不做任何推测
 *
 * 路径和类型是直接摆在卡里的，数出来就行，不会错。
 * 但「这项的上限是多少」「这项只能增不能减」这类限制，卡里**没写**，
 * 只能靠猜。而猜错的代价极大，下面这条是真卡里的实例：
 *
 * 某张卡的「敌人」一组里，上限类字段的初始值写的是 **0** ——
 * 因为开局还没有敌人，这里只是个占位。要是照着它猜出「上限 = 0」，
 * 那么一进战斗，敌人的数值就会被按 0 夹死，这张卡直接玩不了。
 *
 * 所以这里产出的检查依据**只含路径和类型**。上限、方向这类限制一律不填，
 * 要开得由用户在导入时自己逐条确认。宁可少拦一个，也不能让一张在别处
 * 能正常玩的卡，到我们这里反而玩不了。
 */
object CardStateInitializer {

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    /** 卡作者标记初值条目时常用的记号。 */
    private const val MARKER = "initvar"

    /** 元信息字段的前缀。带这个前缀的键不是状态栏里的项，要跳过。 */
    private const val META_PREFIX = "$"

    /**
     * 读出状态栏初始内容。
     *
     * @return 这张卡没带状态玩法时返回 null —— 那是正常情况，不是失败。
     * 纯聊天的卡本来就没有状态栏，这时整套结算都不该启用。
     */
    fun extract(card: CharacterCard): CardStateInit? {
        val entries = card.worldBook?.entries ?: return null

        // 先找作者打过记号的那一条。记号是最可靠的线索：
        // 作者自己知道哪条是初值，而我们靠内容猜总会有猜错的时候
        val marked = entries.indexOfFirst { it.comment.contains(MARKER, ignoreCase = true) }
        if (marked >= 0) {
            parseContent(entries[marked].content)?.let { return build(it, marked) }
        }

        // 没有记号就退一步：找一条整体就是个 JSON 对象、而且能拆出项来的条目。
        // 条件要求「能拆出项」，是为了避开那些同样以大括号开头、
        // 但其实是示例或说明的条目
        entries.forEachIndexed { index, entry ->
            if (index == marked) return@forEachIndexed
            val obj = parseContent(entry.content) ?: return@forEachIndexed
            val built = build(obj, index)
            if (built.values.isNotEmpty()) return built
        }
        return null
    }

    /**
     * 条目内容转成 JSON 对象。
     *
     * 只接受「整条内容就是一个对象」的情况。前后混着说明文字的不认 ——
     * 那种条目是写给模型看的说明，不是初值，认错会把一堆说明文字
     * 当成状态项塞进状态栏。
     */
    private fun parseContent(content: String): JsonObject? {
        val trimmed = content.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
        return try {
            json.parseToJsonElement(trimmed) as? JsonObject
        } catch (failure: Exception) {
            null
        }
    }

    private fun build(root: JsonObject, entryIndex: Int): CardStateInit {
        val values = mutableMapOf<String, StateValue>()
        val fields = mutableMapOf<String, FieldSpec>()
        val notes = mutableListOf<StateInitNote>()

        flatten(prefix = "", obj = root, values = values, fields = fields, notes = notes)

        return CardStateInit(
            values = values,
            schema = StateSchema(fields = fields, origin = SchemaOrigin.DerivedFromInitVar),
            // 元信息原样留着。它可能是对方框架的开关，含义我们没有验证过，
            // 所以只保管、不解读，也绝不根据它改变我们自己的检查行为
            metadata = root[META_PREFIX + "meta"] as? JsonObject,
            sourceEntryIndex = entryIndex,
            notes = notes,
        )
    }

    /**
     * 把嵌套结构压成一层路径。
     *
     * 卡里写的是分组嵌套（一组底下若干项），而检查依据需要的是
     * 「一条路径对一项」的平铺形式 —— 只有平铺才能用一次查表判断
     * 「模型说的这一项到底存不存在」，不用递归去比对树。
     *
     * 分隔符用斜杠而不是点号：项的名字本身就可能带点号，用点号会分不清
     * 「一组底下的一项」和「一个名字里带点的项」。
     */
    private fun flatten(
        prefix: String,
        obj: JsonObject,
        values: MutableMap<String, StateValue>,
        fields: MutableMap<String, FieldSpec>,
        notes: MutableList<StateInitNote>,
    ) {
        for ((key, element) in obj) {
            if (key.startsWith(META_PREFIX)) continue
            val path = if (prefix.isEmpty()) key else "$prefix/$key"

            if (element is JsonObject) {
                if (element.keys.none { !it.startsWith(META_PREFIX) }) {
                    notes += StateInitNote(StateInitNoteCode.EmptyGroup, path)
                    continue
                }
                flatten(path, element, values, fields, notes)
                continue
            }

            val value = StateValueJson.decode(element)
            if (value == null) {
                // 表达不了就跳过，而不是硬塞一个近似值。硬塞会让状态栏里
                // 出现一项「看着有值、实际和卡里写的不是一回事」的内容
                notes += StateInitNote(StateInitNoteCode.ValueNotSupported, path)
                continue
            }

            values[path] = value
            fields[path] = FieldSpec(
                type = typeOf(value),
                elementType = elementTypeOf(element, path, notes),
                // 显示名就用路径最后一段。整条路径太长，状态栏上放不下，
                // 而最后一段就是作者给这一项起的名字
                displayName = path.substringAfterLast('/'),
            )
        }
    }

    /** 从初始值看出这一项是什么类型。 */
    private fun typeOf(value: StateValue): FieldType = when (value) {
        is StateValue.IntValue -> FieldType.Int
        is StateValue.DoubleValue -> FieldType.Double
        is StateValue.StringValue -> FieldType.String
        is StateValue.BoolValue -> FieldType.Bool
        is StateValue.ArrayValue -> FieldType.Array

        // 初始值就是空的，看不出类型。留成「任意」而不是挑一个猜 ——
        // 猜错会让这一项以后的正常改动被当成类型不符而挡掉
        StateValue.NullValue, StateValue.Absent -> FieldType.Any
    }

    /**
     * 数组里装的是什么类型。
     *
     * 空数组、或者里面混着不同类型时都返回 null（不限制）。
     * 这两种情况下任何结论都是猜的，而猜错的后果是把本该允许的改动挡掉。
     */
    private fun elementTypeOf(
        element: JsonElement,
        path: String,
        notes: MutableList<StateInitNote>,
    ): FieldType? {
        if (element !is JsonArray) return null
        if (element.isEmpty()) {
            notes += StateInitNote(StateInitNoteCode.ArrayElementTypeUnknown, path)
            return null
        }
        val types = element.mapNotNull { item -> StateValueJson.decode(item)?.let { typeOf(it) } }
        val single = types.distinct().singleOrNull()
        if (single == null) {
            notes += StateInitNote(StateInitNoteCode.ArrayElementTypeMixed, path)
        }
        return single
    }
}
