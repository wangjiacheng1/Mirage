package com.mirage.engine.card

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 读卡的单测。
 *
 * 用例的形状照着真实卡片来：带 data 包装的新格式、摊平写的老格式、
 * 触发词两种写法、位置写数字和写名字、以及把该是数组的字段写成逗号串。
 * 这些都是真卡里出现过的写法，不是假想的边界。
 */
class CharacterCardParserTest {

    private fun ok(text: String): CharacterCard {
        val result = CharacterCardParser.parse(text)
        assertTrue(result is CardParseResult.Success, "应该能读出来，实际是 $result")
        return result.card
    }

    @Test
    fun 带data包装的新格式能读出来() {
        val card = ok(
            """
            {
              "spec": "chara_card_v3",
              "spec_version": "3.0",
              "data": {
                "name": "某角色",
                "description": "一段设定",
                "first_mes": "开场的话",
                "tags": ["标签甲", "标签乙"],
                "alternate_greetings": ["备选开场"]
              }
            }
            """.trimIndent(),
        )

        assertEquals("某角色", card.name)
        assertEquals("一段设定", card.description)
        assertEquals("开场的话", card.firstMessage)
        assertEquals(listOf("标签甲", "标签乙"), card.tags)
        assertEquals(listOf("备选开场"), card.alternateGreetings)
        assertEquals(CardSpec.V3, card.spec)
    }

    @Test
    fun 没有data包装的老格式也能读出来() {
        val card = ok("""{"name": "某角色", "description": "一段设定"}""")

        assertEquals("某角色", card.name)
        assertEquals(CardSpec.V1, card.spec)
    }

    @Test
    fun 原始内容整份保留() {
        val card = ok("""{"name": "某角色", "自定义字段": "不认识但要留着"}""")

        // 不认识的字段必须还在原文里。丢了它，用户导出的卡就和拿进来的不一样了
        assertNotNull(card.raw["自定义字段"])
    }

    @Test
    fun 不是JSON时按格式错返回() {
        assertEquals(
            CardParseResult.Failure(CardParseFailure.MalformedJson),
            CharacterCardParser.parse("这不是 JSON"),
        )
    }

    @Test
    fun 最外层是数组时单独归一类() {
        assertEquals(
            CardParseResult.Failure(CardParseFailure.NotAnObject),
            CharacterCardParser.parse("""[{"name": "某角色"}]"""),
        )
    }

    @Test
    fun 连名字都没有就不算一张卡() {
        assertEquals(
            CardParseResult.Failure(CardParseFailure.NotACharacterCard),
            CharacterCardParser.parse("""{"description": "只有设定没有名字"}"""),
        )
    }

    @Test
    fun 设定本条目的两种触发词写法都能读到() {
        val card = ok(
            """
            {
              "name": "某角色",
              "character_book": {
                "name": "某设定本",
                "entries": [
                  {"keys": ["甲", "乙"], "content": "条目一", "insertion_order": 10},
                  {"key": "丙, 丁", "content": "条目二", "order": 20}
                ]
              }
            }
            """.trimIndent(),
        )

        val book = assertNotNull(card.worldBook)
        assertEquals("某设定本", book.name)
        assertEquals(listOf("甲", "乙"), book.entries[0].keys)
        // 写成逗号串的也要能读。读不到的话这条设定永远不会被命中
        assertEquals(listOf("丙", "丁"), book.entries[1].keys)
        assertEquals(10, book.entries[0].insertionOrder)
        assertEquals(20, book.entries[1].insertionOrder)
    }

    @Test
    fun 没写开关的条目要当成开启() {
        val card = ok(
            """
            {
              "name": "某角色",
              "character_book": {"entries": [{"content": "条目"}]}
            }
            """.trimIndent(),
        )

        // 默认关掉会让整本设定静默失效，表现是「卡导进来了但设定一点不起作用」
        assertTrue(card.worldBook!!.entries[0].enabled)
    }

    @Test
    fun 位置写数字和写名字都能归一() {
        val card = ok(
            """
            {
              "name": "某角色",
              "character_book": {
                "entries": [
                  {"content": "甲", "position": "after_char"},
                  {"content": "乙", "position": 0},
                  {"content": "丙", "position": "某种没见过的写法"}
                ]
              }
            }
            """.trimIndent(),
        )

        val entries = card.worldBook!!.entries
        assertEquals(WorldBookPosition.AfterCharacter, entries[0].position)
        assertEquals(WorldBookPosition.BeforeCharacter, entries[1].position)
        // 认不出来就是未知，不猜。猜错会把设定塞到错的地方，很难从结果反推
        assertEquals(WorldBookPosition.Unknown, entries[2].position)
        assertEquals("某种没见过的写法", entries[2].positionRaw)
    }
}
