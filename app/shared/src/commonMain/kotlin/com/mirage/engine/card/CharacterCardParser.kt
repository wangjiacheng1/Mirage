package com.mirage.engine.card

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** 读卡的结果。 */
sealed interface CardParseResult {

    data class Success(val card: CharacterCard) : CardParseResult

    /**
     * 读失败。
     *
     * @property code 失败类别。界面按它取提示文案，所以这里不放现成的句子。
     */
    data class Failure(val code: CardParseFailure) : CardParseResult
}

/** 读卡失败的类别。 */
enum class CardParseFailure {
    /** 根本不是 JSON，或者 JSON 本身有语法错。 */
    MalformedJson,

    /** 是 JSON，但最外层不是一个对象（例如是个数组）。 */
    NotAnObject,

    /** 是对象，但里面找不到角色卡该有的东西，连名字都没有。 */
    NotACharacterCard,
}

/**
 * ============================================================================
 * CharacterCardParser —— 把卡片文件读成我们能用的对象
 * ============================================================================
 *
 * ## 为什么是手工逐字段取，而不是定义一个结构体直接反序列化
 *
 * 实际流通的卡片格式很乱：同一个意思的字段，不同版本叫法不同
 * （触发词有时叫 `keys` 有时叫 `key`）；同一个字段，有的卡写数字、
 * 有的卡写字符串；还有一堆我们不认识的自定义字段。
 *
 * 直接反序列化成结构体，碰到任何一处对不上就整张卡读不进来，
 * 而用户手上那张卡在别的软件里明明是能用的 —— 这种失败他完全无法理解。
 * 所以这里的原则是：**认识的就取，不认识的跳过，能读多少算多少**，
 * 只有连名字都找不到才判定这不是一张卡。
 */
object CharacterCardParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(text: String): CardParseResult {
        val root = try {
            json.parseToJsonElement(text)
        } catch (failure: Exception) {
            // 这里只能宽口径接：解析库在不同平台抛的异常类型不完全一样，
            // 只认某一种会在某个平台上漏掉，表现为导入时直接崩掉
            return CardParseResult.Failure(CardParseFailure.MalformedJson)
        }

        if (root !is JsonObject) return CardParseResult.Failure(CardParseFailure.NotAnObject)

        // 新格式把字段都放在 data 里面，老格式直接摊在最外层。
        // 两种都要能读，所以先看有没有 data 这一层
        val body = (root["data"] as? JsonObject) ?: root
        val specRaw = root.string("spec")
        val specVersion = root.string("spec_version")

        val name = body.string("name").ifBlank { body.string("char_name") }
        if (name.isBlank()) return CardParseResult.Failure(CardParseFailure.NotACharacterCard)

        return CardParseResult.Success(
            CharacterCard(
                name = name,
                description = body.string("description"),
                personality = body.string("personality"),
                scenario = body.string("scenario"),
                firstMessage = body.string("first_mes"),
                messageExample = body.string("mes_example"),
                systemPrompt = body.string("system_prompt"),
                postHistoryInstructions = body.string("post_history_instructions"),
                alternateGreetings = body.stringList("alternate_greetings"),
                tags = body.stringList("tags"),
                // 这两个字段是同一个意思的两种写法，哪个有值用哪个
                creatorNotes = body.string("creator_notes").ifBlank { body.string("creatorcomment") },
                creator = body.string("creator"),
                characterVersion = body.string("character_version"),
                worldBook = parseWorldBook(body["character_book"]),
                spec = resolveSpec(specRaw, specVersion, hasDataWrapper = root["data"] is JsonObject),
                specVersion = specVersion,
                raw = root,
            ),
        )
    }

    /**
     * 判定格式版本。
     *
     * 先看卡里自己写的版本号；没写的话，就看字段是不是包在 data 里 ——
     * 有 data 的至少是第二代格式，没有的就是最早那种摊平写法。
     */
    private fun resolveSpec(spec: String, version: String, hasDataWrapper: Boolean): CardSpec = when {
        version.startsWith("3") || spec.contains("v3") -> CardSpec.V3
        version.startsWith("2") || spec.contains("v2") -> CardSpec.V2
        hasDataWrapper -> CardSpec.Unknown
        else -> CardSpec.V1
    }

    private fun parseWorldBook(element: JsonElement?): WorldBook? {
        val obj = element as? JsonObject ?: return null
        val entries = (obj["entries"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.map { parseEntry(it) }
            ?: emptyList()
        return WorldBook(name = obj.string("name"), entries = entries)
    }

    private fun parseEntry(obj: JsonObject): WorldBookEntry {
        val positionRaw = obj.scalarText("position")
        return WorldBookEntry(
            id = obj.int("id"),
            // 触发词有两种写法，而且有的卡两个都写了，所以合起来去重
            keys = (obj.stringList("keys") + obj.stringList("key")).distinct(),
            secondaryKeys = (obj.stringList("secondary_keys") + obj.stringList("keysecondary")).distinct(),
            content = obj.string("content"),
            comment = obj.string("comment").ifBlank { obj.string("name") },
            constant = obj.bool("constant") ?: false,
            // 没写 enabled 的卡要当成开启。默认关掉会让整本设定静默失效，
            // 表现是「卡导进来了但一点设定都不起作用」，极难想到是这里
            enabled = obj.bool("enabled") ?: !(obj.bool("disable") ?: false),
            selective = obj.bool("selective") ?: false,
            insertionOrder = obj.int("insertion_order") ?: obj.int("order") ?: 0,
            positionRaw = positionRaw,
            position = resolvePosition(positionRaw),
        )
    }

    /**
     * 归一插入位置。
     *
     * 数字和名字两种写法都见过，所以两种都认。认不出来的落到「未知」，
     * 不猜 —— 猜错位置会让设定被塞到错误的地方，而这种错很难从结果反推。
     */
    private fun resolvePosition(raw: String): WorldBookPosition = when (raw.lowercase()) {
        "0", "before_char", "beforechar" -> WorldBookPosition.BeforeCharacter
        "1", "after_char", "afterchar" -> WorldBookPosition.AfterCharacter
        "2", "before_an", "beforesystem" -> WorldBookPosition.BeforeSystem
        "3", "after_an", "aftersystem" -> WorldBookPosition.AfterSystem
        "4", "at_depth", "atdepth" -> WorldBookPosition.AtDepth
        else -> WorldBookPosition.Unknown
    }
}

// ---------------------------------------------------------------------------
// 下面几个小工具都在做同一件事：按容错的方式取值。
// 卡的字段类型并不严格（数字可能写成字符串、字符串可能写成数字），
// 严格按类型取会在真实卡片上频繁失败，所以统一走这几个口子。
// ---------------------------------------------------------------------------

private fun JsonObject.string(key: String): String =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""

/** 取标量的文字形态。数字写法和字符串写法都能取到同一个结果。 */
private fun JsonObject.scalarText(key: String): String =
    (this[key] as? JsonPrimitive)?.content ?: ""

private fun JsonObject.int(key: String): Int? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    return primitive.intOrNull ?: primitive.content.toIntOrNull()
}

private fun JsonObject.bool(key: String): Boolean? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    return primitive.booleanOrNull ?: primitive.content.toBooleanStrictOrNull()
}

/**
 * 取字符串数组。
 *
 * 有的卡把本该是数组的字段写成了一个逗号分隔的字符串，这里一并兼容：
 * 不兼容的话那张卡的触发词会全部丢失，而设定条目就再也不会被命中了。
 */
private fun JsonObject.stringList(key: String): List<String> = when (val element = this[key]) {
    is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.content }
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    is JsonPrimitive -> element.content.split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    else -> emptyList()
}

/** 给外部留一个取 data 层的口子，导出时要往同一个位置写回去。 */
internal fun JsonObject.cardBody(): JsonObject = (this["data"] as? JsonObject) ?: this.jsonObject
