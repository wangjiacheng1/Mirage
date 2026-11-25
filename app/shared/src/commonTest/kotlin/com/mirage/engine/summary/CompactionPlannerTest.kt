package com.mirage.engine.summary

import kotlin.test.Test
import kotlin.test.assertEquals

/** 决定「该不该压缩历史、压哪几轮」。 */
class CompactionPlannerTest {

    private fun volume(index: Int, from: Int, to: Int, text: String = "摘要") =
        StoryVolume(index = index, fromTurn = from, toTurn = to, text = text)

    // ------------------------------------------------------------------
    // 挑哪几轮
    // ------------------------------------------------------------------

    @Test
    fun 聊得不够多时没有可压的轮次() {
        // 一共 8 轮，要保留最近 10 轮 —— 一轮都轮不到压
        val turns = (1..8).toList()

        assertEquals(emptyList(), CompactionPlanner.compactableTurns(turns, emptyList(), keepRecentTurns = 10))
    }

    @Test
    fun 保留最近几轮之外的才压() {
        val turns = (1..25).toList()

        val compactable = CompactionPlanner.compactableTurns(turns, emptyList(), keepRecentTurns = 10)

        // 25 轮里留最近 10 轮（16–25），剩下 1–15 可压
        assertEquals((1..15).toList(), compactable)
    }

    @Test
    fun 已经压过的轮次不再压() {
        val turns = (1..40).toList()
        val volumes = listOf(volume(1, 1, 20))

        val compactable = CompactionPlanner.compactableTurns(turns, volumes, keepRecentTurns = 10)

        // 1–20 已压，31–40 要保留，剩下 21–30
        assertEquals((21..30).toList(), compactable)
    }

    @Test
    fun 编号断开也照样一起压() {
        // 🔴 这一条是真机上的实际形态，不是边角情况。
        //
        // 轮次编号在整棵对话树里唯一，所以**只要重新生成过一次，当前这条线上
        // 就一定有空洞**：重新生成拿一个新编号，被换掉的那条带着旧编号
        // 留在另一个分支上。
        //
        // 这里缺的 24、25 就是在别的分支上。它们本来就不在这条线上，
        // 不该因为它们缺席就把这条线上的内容切成两段。
        val turns = (1..23).toList() + (26..40).toList()
        val volumes = listOf(volume(1, 1, 20))

        val compactable = CompactionPlanner.compactableTurns(turns, volumes, keepRecentTurns = 10)

        // 一共 38 轮，留最近 10 轮（31–40 这 10 项）后剩 28 项，
        // 其中编号大于 20 的就是下面这 8 个
        assertEquals(listOf(21, 22, 23, 26, 27, 28, 29, 30), compactable)
    }

    @Test
    fun 重新生成过几次之后仍然压掉该压的那些轮() {
        // 复现用户遇到的那个现象：界面上 14 轮对话，但因为重新生成过两次，
        // 当前这条线上的编号是 1、3、4、5…16 这样跳着的。
        // 修之前这里只会压出「第 1 到 1 轮」
        val turns = listOf(1) + (3..16).toList()

        val plan = CompactionPlanner.plan(
            completedTurns = turns,
            volumes = emptyList(),
            trigger = CompactionTrigger.Manual,
            keepRecentTurns = 10,
        )

        // 15 轮里留最近 10 轮，前 5 个（1、3、4、5、6）该压
        assertEquals(CompactionPlan.NewVolume(fromTurn = 1, toTurn = 6, volumeIndex = 1), plan)
    }

    // ------------------------------------------------------------------
    // 三种触发模式
    // ------------------------------------------------------------------

    @Test
    fun 手动模式下有东西可压就压() {
        val plan = CompactionPlanner.plan(
            completedTurns = (1..25).toList(),
            volumes = emptyList(),
            trigger = CompactionTrigger.Manual,
            keepRecentTurns = 10,
        )

        assertEquals(CompactionPlan.NewVolume(fromTurn = 1, toTurn = 15, volumeIndex = 1), plan)
    }

    @Test
    fun 手动模式下没东西可压也不压() {
        val plan = CompactionPlanner.plan(
            completedTurns = (1..8).toList(),
            volumes = emptyList(),
            trigger = CompactionTrigger.Manual,
            keepRecentTurns = 10,
        )

        assertEquals(CompactionPlan.Nothing, plan)
    }

    @Test
    fun 按轮数模式要攒够轮数才压() {
        val turns = (1..13).toList()

        // 留最近 10 轮，可压的只有 1–3 轮，没到 5 轮
        assertEquals(
            CompactionPlan.Nothing,
            CompactionPlanner.plan(
                completedTurns = turns,
                volumes = emptyList(),
                trigger = CompactionTrigger.EveryNTurns(5),
                keepRecentTurns = 10,
            ),
        )

        // 再聊两轮就够了
        assertEquals(
            CompactionPlan.NewVolume(fromTurn = 1, toTurn = 5, volumeIndex = 1),
            CompactionPlanner.plan(
                completedTurns = (1..15).toList(),
                volumes = emptyList(),
                trigger = CompactionTrigger.EveryNTurns(5),
                keepRecentTurns = 10,
            ),
        )
    }

    @Test
    fun 按占比模式达到比例才压() {
        val turns = (1..25).toList()

        // 窗口 32000，到 50% 才压，现在只用了 8000
        assertEquals(
            CompactionPlan.Nothing,
            CompactionPlanner.plan(
                completedTurns = turns,
                volumes = emptyList(),
                trigger = CompactionTrigger.ContextPercent(50),
                keepRecentTurns = 10,
                estimatedPromptTokens = 8_000,
                contextTokens = 32_000,
            ),
        )

        assertEquals(
            CompactionPlan.NewVolume(fromTurn = 1, toTurn = 15, volumeIndex = 1),
            CompactionPlanner.plan(
                completedTurns = turns,
                volumes = emptyList(),
                trigger = CompactionTrigger.ContextPercent(50),
                keepRecentTurns = 10,
                estimatedPromptTokens = 16_000,
                contextTokens = 32_000,
            ),
        )
    }

    @Test
    fun 不知道窗口多大时按占比模式不触发() {
        // 这个模式的全部依据就是那个比例，窗口拿不到的话算出来的比例没有意义
        val plan = CompactionPlanner.plan(
            completedTurns = (1..25).toList(),
            volumes = emptyList(),
            trigger = CompactionTrigger.ContextPercent(50),
            keepRecentTurns = 10,
            estimatedPromptTokens = 30_000,
            contextTokens = null,
        )

        assertEquals(CompactionPlan.Nothing, plan)
    }

    // ------------------------------------------------------------------
    // 新卷编号
    // ------------------------------------------------------------------

    @Test
    fun 新卷接着已有的编号往后排() {
        val plan = CompactionPlanner.plan(
            completedTurns = (1..40).toList(),
            volumes = listOf(volume(1, 1, 10), volume(2, 11, 20)),
            trigger = CompactionTrigger.Manual,
            keepRecentTurns = 10,
        )

        assertEquals(CompactionPlan.NewVolume(fromTurn = 21, toTurn = 30, volumeIndex = 3), plan)
    }

    // ------------------------------------------------------------------
    // 二次合并
    // ------------------------------------------------------------------

    @Test
    fun 卷占得太多时合并成前情总览() {
        // 没有新内容可压（全部已压 + 最近 10 轮要留），但卷本身占了窗口的 20%
        val volumes = listOf(volume(1, 1, 10), volume(2, 11, 20))

        val plan = CompactionPlanner.plan(
            completedTurns = (1..30).toList(),
            volumes = volumes,
            trigger = CompactionTrigger.Manual,
            keepRecentTurns = 10,
            contextTokens = 32_000,
            volumeTokens = 6_400,
        )

        assertEquals(CompactionPlan.MergeVolumes(volumes), plan)
    }

    @Test
    fun 卷还没占到阈值就不合并() {
        val plan = CompactionPlanner.plan(
            completedTurns = (1..30).toList(),
            volumes = listOf(volume(1, 1, 10), volume(2, 11, 20)),
            trigger = CompactionTrigger.Manual,
            keepRecentTurns = 10,
            contextTokens = 32_000,
            volumeTokens = 2_000,
        )

        assertEquals(CompactionPlan.Nothing, plan)
    }

    @Test
    fun 只有一卷时不做合并() {
        // 一卷「合并」成一卷只是白花一次钱
        val plan = CompactionPlanner.plan(
            completedTurns = (1..20).toList(),
            volumes = listOf(volume(1, 1, 10)),
            trigger = CompactionTrigger.Manual,
            keepRecentTurns = 10,
            contextTokens = 32_000,
            volumeTokens = 30_000,
        )

        assertEquals(CompactionPlan.Nothing, plan)
    }

    @Test
    fun 有新内容可压时先出新卷再说合并() {
        // 两件事同时满足。一次只做一个动作，调用方做完会再问一次，于是自然收敛
        val volumes = listOf(volume(1, 1, 10), volume(2, 11, 20))

        val plan = CompactionPlanner.plan(
            completedTurns = (1..40).toList(),
            volumes = volumes,
            trigger = CompactionTrigger.Manual,
            keepRecentTurns = 10,
            contextTokens = 32_000,
            volumeTokens = 30_000,
        )

        assertEquals(CompactionPlan.NewVolume(fromTurn = 21, toTurn = 30, volumeIndex = 3), plan)
    }
}
