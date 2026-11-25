package com.mirage.engine.card

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** 卡上可以直接改的那些字段。 */
enum class CardField(val jsonKey: String) {
    Name("name"),
    Description("description"),
    Personality("personality"),
    Scenario("scenario"),
    FirstMessage("first_mes"),
    MessageExample("mes_example"),
    SystemPrompt("system_prompt"),
    PostHistoryInstructions("post_history_instructions"),
    CreatorNotes("creator_notes"),
}

/**
 * 改角色卡的内容。
 *
 * ## 为什么要在原文上改，而不是用我们的对象重新生成一份
 *
 * 一张卡里有很多我们不认识的字段（别家框架的扩展、作者自定义的东西）。
 * 用我们认识的那些字段重新拼一份，等于**把不认识的全删掉** ——
 * 用户从别处拿来的卡，在我们这里改一次就再也回不到原样了。
 *
 * 所以这里的做法是：**拿原文当底，只覆盖改动的那几个键**，其余一个字节不动。
 * 这条是导入导出能互通的前提，不是洁癖。
 */
object CardEditor {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /**
     * 把改动写回卡的原文，返回新的原文。
     *
     * @param edits 要改的字段。没列出来的字段保持不变。
     *
     * 新格式的卡字段在 `data` 里面，老格式直接摊在最外层。改哪一层要跟着原文走 ——
     * 写错层的话，卡看起来没报错，但改动一点效果都没有。
     */
    fun applyEdits(raw: JsonObject, edits: Map<CardField, String>): JsonObject {
        if (edits.isEmpty()) return raw

        val hasDataWrapper = raw["data"] is JsonObject
        val body = (raw["data"] as? JsonObject) ?: raw
        val newBody = buildJsonObject {
            // 先把原来的全部放进去，再覆盖改动的那几个。
            // 顺序不能反 —— 反了就变成「用旧值盖掉新值」
            body.forEach { (key, value) -> put(key, value) }
            edits.forEach { (field, text) -> put(field.jsonKey, JsonPrimitive(text)) }
        }

        if (!hasDataWrapper) return newBody

        return buildJsonObject {
            raw.forEach { (key, value) -> put(key, value) }
            put("data", newBody)
        }
    }

    /** 改完之后转成要存起来的文本。 */
    fun encode(raw: JsonObject): String = json.encodeToString(JsonObject.serializer(), raw)

    /**
     * 在卡的原文文本上改，直接返回新的原文文本。原文读不出来时返回 null。
     *
     * 界面层只有文本，没有 JSON 类型（那是引擎内部的事），所以给它留这个入口。
     */
    fun applyEdits(rawText: String, edits: Map<CardField, String>): String? {
        val root = runCatching { Json.parseToJsonElement(rawText) as? JsonObject }.getOrNull()
            ?: return null
        return encode(applyEdits(root, edits))
    }

    /** 读卡上某个字段现在的内容。 */
    fun read(card: CharacterCard, field: CardField): String = when (field) {
        CardField.Name -> card.name
        CardField.Description -> card.description
        CardField.Personality -> card.personality
        CardField.Scenario -> card.scenario
        CardField.FirstMessage -> card.firstMessage
        CardField.MessageExample -> card.messageExample
        CardField.SystemPrompt -> card.systemPrompt
        CardField.PostHistoryInstructions -> card.postHistoryInstructions
        CardField.CreatorNotes -> card.creatorNotes
    }
}
