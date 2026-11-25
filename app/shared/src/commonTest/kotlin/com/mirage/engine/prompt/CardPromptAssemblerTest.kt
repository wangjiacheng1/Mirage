package com.mirage.engine.prompt

import com.mirage.engine.card.CardParseResult
import com.mirage.engine.card.CharacterCard
import com.mirage.engine.card.CharacterCardParser
import com.mirage.engine.llm.ChatRole
import com.mirage.engine.state.StateValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 拼请求的单测。
 *
 * 重点有两处：
 * 一是三类设定条目不能走错门（算数值的规则绝不能进正文）；
 * 二是带触发词的条目只在最近聊到时才带上。
 */
class CardPromptAssemblerTest {

    private val card: CharacterCard = run {
        val text = """
        {
          "name": "某角色",
          "description": "角色设定",
          "personality": "性格",
          "scenario": "处境",
          "system_prompt": "系统要求",
          "post_history_instructions": "放在最后的要求",
          "character_book": {
            "entries": [
              {"comment": "常驻设定", "content": "常驻内容", "constant": true, "insertion_order": 20},
              {"comment": "触发设定", "content": "触发内容", "keys": ["某个词"], "insertion_order": 10},
              {"comment": "[initvar]状态初值", "content": "{\"某组\": {\"甲\": 1}}", "constant": true},
              {"comment": "[mvu_update]状态更新规则", "content": "算数值的规则", "constant": true},
              {"comment": "关掉的设定", "content": "不该出现", "constant": true, "enabled": false}
            ]
          }
        }
        """.trimIndent()
        val result = CharacterCardParser.parse(text)
        assertTrue(result is CardParseResult.Success)
        result.card
    }

    private fun promptText(recentText: String = "", values: Map<String, StateValue> = emptyMap()): String =
        CardPromptAssembler.buildNarrativePrompt(card, recentText, values)
            .systemMessages.joinToString("\n") { it.content }

    @Test
    fun 角色设定各段都进了正文请求() {
        val text = promptText()

        listOf("系统要求", "角色设定", "性格", "处境", "常驻内容").forEach {
            assertTrue(text.contains(it), "缺少：$it")
        }
    }

    @Test
    fun 算数值的规则不进正文() {
        val text = promptText()

        // 进了正文，模型就会一边讲故事一边报数字 —— 这正是两次调用要分开的原因
        assertTrue(!text.contains("算数值的规则"), "算数值的规则不该出现在正文请求里")
    }

    @Test
    fun 初始数值不进正文() {
        val text = promptText()

        // 它已经变成状态栏本身了。再塞进正文等于每轮都提醒模型「开局是多少」
        assertTrue(!text.contains("某组"), "初始数值不该出现在正文请求里")
    }

    @Test
    fun 关掉的条目不带上() {
        assertTrue(!promptText().contains("不该出现"))
    }

    @Test
    fun 带触发词的条目只在最近聊到时才带上() {
        assertTrue(!promptText(recentText = "随便说点什么").contains("触发内容"))
        assertTrue(promptText(recentText = "我们聊到了某个词").contains("触发内容"))
    }

    @Test
    fun 条目按作者给的次序排列() {
        val prompt = CardPromptAssembler.buildNarrativePrompt(card, recentText = "某个词")

        // 触发设定的次序是 10，常驻设定是 20，所以触发的那条要排在前面
        assertEquals(listOf("触发设定", "常驻设定"), prompt.usedEntryComments)
    }

    @Test
    fun 状态数值作为单独一段带上且附带只读要求() {
        val text = promptText(values = mapOf("某组/甲" to StateValue.IntValue(60)))

        assertTrue(text.contains("\"某组/甲\":60"), "状态数值应以结构化形式带上")
        assertTrue(text.contains("不要在正文里写出任何数值"), "必须明确要求正文不写数值")
    }

    @Test
    fun 没有状态玩法时不带状态段() {
        assertTrue(!promptText().contains("当前的状态数值"))
    }

    @Test
    fun 放在最后的要求单独拿出来且用对话角色发() {
        val prompt = CardPromptAssembler.buildNarrativePrompt(card)

        assertEquals("放在最后的要求", prompt.trailingSystemMessage?.content)
        // 🔴 用 user 而不是 system：不少模型只认真对待第一条 system 消息，
        // 夹在对话中间的会被轻描淡写带过。而这条偏偏是控制语气和结构的那一句，
        // 失效了最难察觉 —— 正文「差不多但不太对」，却说不出哪里不对
        assertEquals(ChatRole.User, prompt.trailingSystemMessage?.role)
    }

    @Test
    fun 通用叙事要求排在所有卡设定之前() {
        val prompt = CardPromptAssembler.buildNarrativePrompt(card, globalPreset = "通用要求")
        val text = prompt.systemMessages.first().content

        // 顺序就是优先级：后面的更具体、更贴近这张卡，冲突时以卡为准
        assertTrue(text.indexOf("通用要求") < text.indexOf("系统要求"))
        assertTrue(text.indexOf("系统要求") < text.indexOf("角色设定"))
    }

    @Test
    fun 不给通用要求时一个字都不加() {
        val text = promptText()

        assertTrue(!text.contains("通用要求"))
    }

    @Test
    fun 前情提要排在设定之后状态栏之前() {
        val prompt = CardPromptAssembler.buildNarrativePrompt(
            card = card,
            stateValues = mapOf("某组/甲" to StateValue.IntValue(60)),
            storySummary = "前情提要内容",
        )
        val text = prompt.systemMessages.first().content

        // 先知道「这是谁」，再知道「发生过什么」，最后才是「现在的数值是多少」
        assertTrue(text.indexOf("角色设定") < text.indexOf("前情提要内容"))
        assertTrue(text.indexOf("前情提要内容") < text.indexOf("某组/甲"))
    }

    @Test
    fun 没压过时不带前情提要段() {
        val text = promptText()

        assertTrue(!text.contains("前情提要"))
    }

    @Test
    fun 通用要求里的占位也会被替换() {
        val result = CharacterCardParser.parse("""{"name": "某角色", "description": "设定"}""")
        assertTrue(result is CardParseResult.Success)

        val text = CardPromptAssembler
            .buildNarrativePrompt(result.card, globalPreset = "{{user}}是玩家")
            .systemMessages.first().content

        assertTrue(text.contains("你是玩家"), "通用要求和卡设定走同一套替换，不能漏掉")
    }

    @Test
    fun 设定合成一条系统消息() {
        val prompt = CardPromptAssembler.buildNarrativePrompt(card)

        // 分成多条发的话，有的服务商只认第一条，后面的设定等于白送
        assertEquals(1, prompt.systemMessages.size)
    }

    @Test
    fun 卡里带了算数值规则就用卡里的() {
        assertEquals("算数值的规则", CardPromptAssembler.settlementRules(card))
    }

    @Test
    fun 卡里没写算数值规则时返回空由调用方退回通用说法() {
        val result = CharacterCardParser.parse("""{"name": "某角色"}""")
        assertTrue(result is CardParseResult.Success)

        assertNull(CardPromptAssembler.settlementRules(result.card))
    }
}

/**
 * 占位替换的用例。
 *
 * 这一块是看真卡发现的：一张卡里 `{{user}}` 出现了 19 次，
 * 而我们一直把这两个花括号原样发给了模型。
 */
class CardMacrosTest {

    @Test
    fun 玩家与角色占位都被换掉() {
        val text = CardMacros.expand("{{user}}看着{{char}}。", userName = "你", charName = "某角色")

        assertEquals("你看着某角色。", text)
    }

    @Test
    fun 大小写不同的写法都认() {
        val text = CardMacros.expand("{{User}} {{CHAR}}", userName = "你", charName = "某角色")

        assertEquals("你 某角色", text)
    }

    @Test
    fun 不认识的占位原样留着() {
        val text = CardMacros.expand("{{time}} {{user}}", userName = "你", charName = "某角色")

        // 删掉或瞎填会悄悄改变卡作者的本意；留着最多是多几个符号，而且一眼能看出没支持
        assertEquals("{{time}} 你", text)
    }

    @Test
    fun 装配出来的系统消息里不再残留占位() {
        val result = CharacterCardParser.parse(
            """{"name": "某角色", "description": "{{user}}面对{{char}}。"}""",
        )
        assertTrue(result is CardParseResult.Success)

        val text = CardPromptAssembler.buildNarrativePrompt(result.card)
            .systemMessages.first().content

        assertEquals("你面对某角色。", text)
    }
}

/**
 * 状态值里的占位替换。
 *
 * 这一组是真机上发现的：状态栏里偶尔冒出 `{{user}}` 字样。
 * 根因是卡自带的结算规则里写着这个占位，而我们只给开场消息做了替换，
 * 模型把这几个字当成真内容写进了状态栏。
 */
class CardMacrosStateValueTest {

    @Test
    fun 文字项里的占位被替换() {
        val values = mapOf("某组/甲" to StateValue.StringValue("{{user}}的同伴"))

        val expanded = CardMacros.expand(values, userName = "你", charName = "某角色")

        assertEquals(StateValue.StringValue("你的同伴"), expanded["某组/甲"])
    }

    @Test
    fun 列表里的每一项都替换() {
        val values = mapOf(
            "某组/列表" to StateValue.ArrayValue(
                listOf(StateValue.StringValue("{{char}}的剑"), StateValue.StringValue("普通石头")),
            ),
        )

        val expanded = CardMacros.expand(values, userName = "你", charName = "某角色")

        assertEquals(
            StateValue.ArrayValue(
                listOf(StateValue.StringValue("某角色的剑"), StateValue.StringValue("普通石头")),
            ),
            expanded["某组/列表"],
        )
    }

    @Test
    fun 数字和是否原样不动() {
        val values = mapOf(
            "某组/数" to StateValue.IntValue(60),
            "某组/否" to StateValue.BoolValue(false),
        )

        assertEquals(values, CardMacros.expand(values, "你", "某角色"))
    }
}
