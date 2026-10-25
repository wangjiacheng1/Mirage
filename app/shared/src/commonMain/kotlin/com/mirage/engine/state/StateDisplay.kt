package com.mirage.engine.state

/**
 * 状态栏上一项的**显示形态**。
 *
 * 这和「这一项是什么类型」是两件事，必须分开：
 * 类型管的是「允许改成什么」（数字项不能被改成一句话），
 * 显示形态管的是「这一项在状态栏上长什么样」（画成一条进度条还是写个数字）。
 *
 * 分开的好处很实际：显示形态猜错了，最坏结果是看着别扭，用户改一下就好；
 * 而类型或上限猜错，会直接把本来正常的改动挡掉，游戏玩不下去。
 * 所以这一组可以大胆按命名习惯去猜，那一组必须保守。
 */
enum class FieldDisplay {
    /** 一行文字。 */
    Text,

    /** 一个数字。 */
    Number,

    /** 开关形态的是/否。 */
    Toggle,

    /** 一串标签，适合装物品、线索这类列表。 */
    Tags,

    /** 进度条。需要知道「满值是多少」，见 [FieldPresentation.maxPath]。 */
    Gauge,
}

/**
 * 一项的显示方式。
 *
 * @property maxPath 画进度条时，满值取哪一项的当前值。
 *   例如某项的满值存在同组的另一项里，这里就填那一项的路径。
 *   🔴 **它只用来画进度条，绝不参与「改不改得了」的判断** ——
 *   拿它当上限去夹数值，就等于把一个靠猜得出的结论变成了硬规则。
 */
data class FieldPresentation(
    val display: FieldDisplay,
    val maxPath: String? = null,
)

/**
 * 整个状态栏的显示方案。
 *
 * 和字段清单分开存放，是因为两者的来源和可信度完全不同：
 * 字段清单是从卡里数出来的（不会错），显示方案里有一部分是按命名习惯猜的（可能错）。
 * 混在一起会让人分不清哪些是事实、哪些是猜测。
 */
data class StateDisplayProfile(
    val fields: Map<String, FieldPresentation> = emptyMap(),
) {
    fun of(path: String): FieldPresentation? = fields[path]
}

/**
 * ============================================================================
 * StateDisplayInference —— 猜每一项该怎么显示
 * ============================================================================
 *
 * 规则很简单：
 * - 是/否 → 开关；列表 → 标签串；文字 → 一行文字；
 * - 数字 → 默认写数字；但如果同一组里还有一项像是它的「满值」，就画成进度条。
 *
 * 「像是满值」只认几种明确的命名写法，认不出来就老老实实写数字。
 * 宁可少画一条进度条，也不要把两个毫无关系的数字配成一对 ——
 * 那会在状态栏上显示出一个完全错误的比例。
 */
object StateDisplayInference {

    /** 满值常见的几种叫法。放在被修饰的名字后面，例如「某项」+「上限」。 */
    private val MAX_SUFFIXES = listOf("上限", "最大", "最大值", "总数", "总量", "满值")

    /** 另一种常见写法：一组里用「当前」和「上限」这类成对的名字。 */
    private val CURRENT_NAMES = listOf("当前", "现有", "剩余")

    fun infer(values: Map<String, StateValue>, schema: StateSchema): StateDisplayProfile {
        val result = mutableMapOf<String, FieldPresentation>()

        for ((path, value) in values) {
            val type = schema.specOf(path)?.type
            val presentation = when {
                value is StateValue.BoolValue || type == FieldType.Bool ->
                    FieldPresentation(FieldDisplay.Toggle)

                value is StateValue.ArrayValue || type == FieldType.Array ->
                    FieldPresentation(FieldDisplay.Tags)

                value.asNumberOrNull() != null -> {
                    val maxPath = findMaxPartner(path, values)
                    if (maxPath != null) {
                        FieldPresentation(FieldDisplay.Gauge, maxPath)
                    } else {
                        FieldPresentation(FieldDisplay.Number)
                    }
                }

                else -> FieldPresentation(FieldDisplay.Text)
            }
            result[path] = presentation
        }
        return StateDisplayProfile(result)
    }

    /**
     * 找这一项的「满值」在哪。
     *
     * 两种写法：
     * 一是同组里有个同名加后缀的项（「某项」与「某项上限」）；
     * 二是同组里用「当前 / 上限」这类成对的名字。
     *
     * 自己就是满值的那一项不配对 —— 否则会出现「上限」自己画一条永远满格的进度条。
     */
    private fun findMaxPartner(path: String, values: Map<String, StateValue>): String? {
        val name = path.substringAfterLast('/')
        val group = path.substringBeforeLast('/', missingDelimiterValue = "")
        val prefix = if (group.isEmpty()) "" else "$group/"

        // 名字本身就带满值后缀的，说明它是满值而不是当前值
        if (MAX_SUFFIXES.any { name.endsWith(it) }) return null

        MAX_SUFFIXES.forEach { suffix ->
            val candidate = "$prefix$name$suffix"
            if (isNumber(values[candidate])) return candidate
        }

        if (CURRENT_NAMES.any { name == it }) {
            MAX_SUFFIXES.forEach { suffix ->
                val candidate = "$prefix$suffix"
                if (isNumber(values[candidate])) return candidate
            }
        }
        return null
    }

    /** 这一项存在、而且是个数字。 */
    private fun isNumber(value: StateValue?): Boolean = value?.asNumberOrNull() != null
}
