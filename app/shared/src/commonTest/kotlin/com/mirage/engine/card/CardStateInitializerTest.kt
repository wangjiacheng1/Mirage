package com.mirage.engine.card

import com.mirage.engine.state.FieldType
import com.mirage.engine.state.SchemaOrigin
import com.mirage.engine.state.StateValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 读状态栏初值的单测。
 *
 * 用例形状照着真卡：分组嵌套、顶层直接放标量、空数组、带元素的数组、
 * 布尔、元信息字段，以及那个「上限类字段初始值是 0」的占位写法。
 */
class CardStateInitializerTest {

    private fun cardWith(vararg entries: Pair<String, String>): CharacterCard {
        val body = entries.joinToString(",") { (comment, content) ->
            """{"comment": ${quote(comment)}, "content": ${quote(content)}, "constant": true}"""
        }
        val text = """
            {"name": "某角色", "character_book": {"entries": [$body]}}
        """.trimIndent()
        val result = CharacterCardParser.parse(text)
        assertTrue(result is CardParseResult.Success)
        return result.card
    }

    private fun quote(text: String) = buildString {
        append('"')
        text.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                else -> append(ch)
            }
        }
        append('"')
    }

    private val initContent = """
        {
          "${'$'}meta": {"strictSet": true},
          "某组": {"甲": 60, "乙": 60, "丙": "门厅", "丁": false},
          "顶层项": 41,
          "列表组": {"空列表": [], "有内容的列表": ["甲", "乙"]},
          "占位组": {"数值": 0, "上限": 0}
        }
    """.trimIndent()

    @Test
    fun 嵌套分组被压成斜杠路径() {
        val init = assertNotNull(CardStateInitializer.extract(cardWith("[initvar]状态初值" to initContent)))

        assertEquals(StateValue.IntValue(60), init.values["某组/甲"])
        assertEquals(StateValue.StringValue("门厅"), init.values["某组/丙"])
        assertEquals(StateValue.BoolValue(false), init.values["某组/丁"])
        // 顶层直接放的标量同样是一项，路径就是它自己的名字
        assertEquals(StateValue.IntValue(41), init.values["顶层项"])
    }

    @Test
    fun 元信息字段不进状态栏() {
        val init = assertNotNull(CardStateInitializer.extract(cardWith("[initvar]状态初值" to initContent)))

        assertEquals(
            emptyList(),
            init.values.keys.filter { it.startsWith("$") },
            "带前缀的元信息不是状态项，不该出现在状态栏里",
        )
        // 但要原样留着：它是别人框架的开关，我们只保管，不按自己的理解去用
        assertNotNull(init.metadata)
    }

    @Test
    fun 类型按初始值判定() {
        val init = assertNotNull(CardStateInitializer.extract(cardWith("[initvar]状态初值" to initContent)))

        assertEquals(FieldType.Int, init.schema.specOf("某组/甲")!!.type)
        assertEquals(FieldType.String, init.schema.specOf("某组/丙")!!.type)
        assertEquals(FieldType.Bool, init.schema.specOf("某组/丁")!!.type)
        assertEquals(FieldType.Array, init.schema.specOf("列表组/有内容的列表")!!.type)
        assertEquals(FieldType.String, init.schema.specOf("列表组/有内容的列表")!!.elementType)
    }

    @Test
    fun 空数组不猜元素类型并记一条说明() {
        val init = assertNotNull(CardStateInitializer.extract(cardWith("[initvar]状态初值" to initContent)))

        assertNull(init.schema.specOf("列表组/空列表")!!.elementType)
        assertEquals(
            listOf(StateInitNote(StateInitNoteCode.ArrayElementTypeUnknown, "列表组/空列表")),
            init.notes.filter { it.code == StateInitNoteCode.ArrayElementTypeUnknown },
        )
    }

    @Test
    fun 只产出路径和类型不产出任何推测出来的限制() {
        val init = assertNotNull(CardStateInitializer.extract(cardWith("[initvar]状态初值" to initContent)))

        // 这是兼容的底线。卡里那个「上限」初始值写的是 0（开局的占位值），
        // 一旦照着它猜出上限，相关数值就会被按 0 夹死，这张卡直接玩不了
        val fields = listOf("某组/甲", "占位组/数值", "占位组/上限", "顶层项")
        fields.forEach { path ->
            val spec = assertNotNull(init.schema.specOf(path), path)
            assertNull(spec.min, "$path 不该带下限")
            assertNull(spec.max, "$path 不该带上限")
            assertNull(spec.maxFromPath, "$path 不该带动态上限")
            assertNull(spec.maxDeltaPerTurn, "$path 不该带单轮幅度限制")
            assertNull(spec.monotonic, "$path 不该带方向限制")
            assertEquals(false, spec.readOnly, "$path 不该被判成只读")
        }
        assertEquals(SchemaOrigin.DerivedFromInitVar, init.schema.origin)
    }

    @Test
    fun 显示名取路径最后一段() {
        val init = assertNotNull(CardStateInitializer.extract(cardWith("[initvar]状态初值" to initContent)))

        assertEquals("甲", init.schema.specOf("某组/甲")!!.displayName)
    }

    @Test
    fun 没有记号时退而认整条就是JSON对象的条目() {
        val init = assertNotNull(
            CardStateInitializer.extract(
                cardWith(
                    "一条普通设定" to "这是一段说明文字",
                    "另一条" to """{"某组": {"甲": 1}}""",
                ),
            ),
        )

        assertEquals(StateValue.IntValue(1), init.values["某组/甲"])
        assertEquals(1, init.sourceEntryIndex)
    }

    @Test
    fun 前后混着说明文字的条目不认() {
        val init = CardStateInitializer.extract(
            cardWith("说明条目" to """请按下面的格式提交：{"某组": {"甲": 1}}"""),
        )

        // 认了会把说明文字当成状态项塞进状态栏
        assertNull(init)
    }

    @Test
    fun 没有设定本的纯聊天卡返回空而不是失败() {
        val result = CharacterCardParser.parse("""{"name": "某角色"}""")
        assertTrue(result is CardParseResult.Success)

        // 纯聊天的卡本来就没有状态栏，这不是错误，只是不启用结算
        assertNull(CardStateInitializer.extract(result.card))
    }

    @Test
    fun 记号条目内容坏掉时退回去找别的条目() {
        val init = assertNotNull(
            CardStateInitializer.extract(
                cardWith(
                    "[initvar]状态初值" to "{这里少了引号}",
                    "备用" to """{"某组": {"甲": 2}}""",
                ),
            ),
        )

        assertEquals(StateValue.IntValue(2), init.values["某组/甲"])
    }
}
