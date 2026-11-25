package com.mirage.engine.summary

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 压缩那次调用的输入，和摘要段落怎么拼进提示词。 */
class SummaryPromptBuilderTest {

    private fun volume(index: Int, from: Int, to: Int, text: String) =
        StoryVolume(index = index, fromTurn = from, toTurn = to, text = text)

    @Test
    fun 压缩请求必须走流式并关掉思考() {
        val request = SummaryPromptBuilder.buildVolumeRequest(
            modelId = "some-model",
            transcript = "第一轮发生了什么",
        )

        // 🔴 收响应那一侧只有流式通道一条路，它按 data: 行解析。
        // 这里说不流式，服务端就返回普通 JSON，一行都认不出来，
        // 表现成「摘要一个字都没拿到」→ 每次压缩都失败
        assertTrue(request.stream)
        // 设了长度上限就必须关思考 —— 上限是两者共享的，
        // 不关就可能出现「思考吃光额度、摘要一个字都没有」
        assertFalse(request.reasoningEnabled)
        assertEquals(SummaryPromptBuilder.DEFAULT_SUMMARY_MAX_TOKENS, request.maxOutputTokens)
    }

    @Test
    fun 合并请求也要走流式() {
        // 两条路都走同一个收响应的通道，漏掉一条就是「平时好用、
        // 攒够卷要合并时突然失败」这种极难复现的毛病
        val request = SummaryPromptBuilder.buildMergeRequest(
            modelId = "some-model",
            volumes = listOf(volume(1, 1, 10, "甲"), volume(2, 11, 20, "乙")),
        )

        assertTrue(request.stream)
        assertFalse(request.reasoningEnabled)
    }

    @Test
    fun 压缩请求把原文放在用户消息里() {
        val request = SummaryPromptBuilder.buildVolumeRequest(
            modelId = "some-model",
            transcript = "原文内容",
        )

        assertEquals(2, request.messages.size)
        assertEquals(SummaryPromptBuilder.VOLUME_INSTRUCTION, request.messages[0].content)
        assertEquals("原文内容", request.messages[1].content)
    }

    @Test
    fun 合并请求用的是不同的指令() {
        val request = SummaryPromptBuilder.buildMergeRequest(
            modelId = "some-model",
            volumes = listOf(volume(1, 1, 10, "甲"), volume(2, 11, 20, "乙")),
        )

        assertEquals(SummaryPromptBuilder.MERGE_INSTRUCTION, request.messages[0].content)
        assertEquals(SummaryPromptBuilder.DEFAULT_MERGE_MAX_TOKENS, request.maxOutputTokens)
        // 两卷的内容都要在，合并不能丢掉其中一卷
        assertTrue(request.messages[1].content.contains("甲"))
        assertTrue(request.messages[1].content.contains("乙"))
    }

    @Test
    fun 没有卷时摘要段落为空() {
        assertNull(SummaryPromptBuilder.volumesSection(emptyList()))
    }

    @Test
    fun 摘要段落按轮次先后排列并标出范围() {
        // 故意把顺序打乱传进去
        val section = SummaryPromptBuilder.volumesSection(
            listOf(volume(2, 11, 20, "后发生的"), volume(1, 1, 10, "先发生的")),
        )!!

        assertTrue(section.indexOf("先发生的") < section.indexOf("后发生的"))
        assertTrue(section.contains("第 1–10 轮"))
        assertTrue(section.contains("第 11–20 轮"))
    }

    @Test
    fun 摘要段落要说明这是已经发生过的事() {
        // 不说的话模型会把摘要当成待办清单，接着去「完成」里面写的事
        val section = SummaryPromptBuilder.volumesSection(listOf(volume(1, 1, 10, "内容")))!!

        assertTrue(section.contains("已经发生"))
    }

    @Test
    fun 一卷盖了几轮() {
        assertEquals(10, volume(1, 21, 30, "内容").turnCount)
        assertEquals(1, volume(1, 5, 5, "内容").turnCount)
    }
}
