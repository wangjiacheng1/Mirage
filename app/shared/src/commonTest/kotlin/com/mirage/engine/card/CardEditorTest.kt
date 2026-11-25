package com.mirage.engine.card

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 改角色卡的单测。
 *
 * 最要紧的一条是**不认识的字段必须原样留着** —— 丢了它，用户从别处拿来的卡
 * 在我们这里改一次就再也回不到原样，而且他当时根本察觉不到。
 */
class CardEditorTest {

    private fun parse(text: String): CharacterCard {
        val result = CharacterCardParser.parse(text)
        assertTrue(result is CardParseResult.Success)
        return result.card
    }

    @Test
    fun 改掉一个字段() {
        val card = parse("""{"name": "某角色", "description": "旧设定"}""")

        val edited = CardEditor.applyEdits(card.raw, mapOf(CardField.Description to "新设定"))
        val reread = parse(CardEditor.encode(edited))

        assertEquals("新设定", reread.description)
        assertEquals("某角色", reread.name)
    }

    @Test
    fun 不认识的字段原样留着() {
        val card = parse(
            """{"name": "某角色", "某个自定义字段": "不认识但要留着",
               "extensions": {"别家的扩展": [1, 2, 3]}}""",
        )

        val edited = CardEditor.applyEdits(card.raw, mapOf(CardField.Name to "改过的名字"))

        assertNotNull(edited["某个自定义字段"])
        assertNotNull(edited["extensions"])
        assertEquals("改过的名字", parse(CardEditor.encode(edited)).name)
    }

    @Test
    fun 带data包装的新格式改在里层() {
        val card = parse(
            """{"spec": "chara_card_v3", "spec_version": "3.0",
               "data": {"name": "某角色", "description": "旧设定", "自定义": "留着"}}""",
        )

        val edited = CardEditor.applyEdits(card.raw, mapOf(CardField.Description to "新设定"))
        val reread = parse(CardEditor.encode(edited))

        assertEquals("新设定", reread.description)
        // 外层的格式声明和里层的自定义字段都要还在
        assertNotNull(edited["spec"])
        assertNotNull((edited["data"] as kotlinx.serialization.json.JsonObject)["自定义"])
    }

    @Test
    fun 没有data包装的老格式改在外层() {
        val card = parse("""{"name": "某角色", "description": "旧设定"}""")

        val edited = CardEditor.applyEdits(card.raw, mapOf(CardField.Description to "新设定"))

        // 写错层的话卡不会报错，但改动一点效果都没有
        assertEquals("新设定", parse(CardEditor.encode(edited)).description)
    }

    @Test
    fun 一次改多个字段() {
        val card = parse("""{"name": "某角色"}""")

        val edited = CardEditor.applyEdits(
            card.raw,
            mapOf(
                CardField.Personality to "新性格",
                CardField.SystemPrompt to "新要求",
            ),
        )
        val reread = parse(CardEditor.encode(edited))

        assertEquals("新性格", reread.personality)
        assertEquals("新要求", reread.systemPrompt)
    }

    @Test
    fun 没有改动时原样返回() {
        val card = parse("""{"name": "某角色"}""")

        assertEquals(card.raw, CardEditor.applyEdits(card.raw, emptyMap()))
    }

    @Test
    fun 改成空字符串也要生效() {
        val card = parse("""{"name": "某角色", "system_prompt": "原来有内容"}""")

        val edited = CardEditor.applyEdits(card.raw, mapOf(CardField.SystemPrompt to ""))

        // 清空是正当操作：用户可能就是想去掉卡作者写的某条要求
        assertEquals("", parse(CardEditor.encode(edited)).systemPrompt)
    }

    @Test
    fun 世界观条目不受影响() {
        val card = parse(
            """{"name": "某角色",
               "character_book": {"entries": [{"comment": "条目", "content": "内容"}]}}""",
        )

        val edited = CardEditor.applyEdits(card.raw, mapOf(CardField.Name to "改名"))
        val reread = parse(CardEditor.encode(edited))

        assertEquals(1, reread.worldBook?.entries?.size)
        assertEquals("内容", reread.worldBook?.entries?.first()?.content)
    }

    @Test
    fun 读出来的内容和卡上的一致() {
        val card = parse("""{"name": "某角色", "personality": "某种性格"}""")

        assertEquals("某角色", CardEditor.read(card, CardField.Name))
        assertEquals("某种性格", CardEditor.read(card, CardField.Personality))
        assertEquals("", CardEditor.read(card, CardField.Scenario))
    }
}
