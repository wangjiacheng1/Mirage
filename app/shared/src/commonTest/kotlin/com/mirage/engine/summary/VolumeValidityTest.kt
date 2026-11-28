package com.mirage.engine.summary

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 压在别的分支上的卷不该发出去。
 *
 * 场景：卷盖住第 1–20 轮，之后切回一个在第 15 轮分叉的旧版本。
 * 那一卷讲的是另一条线上的事，照发就是让模型读到没发生过的剧情。
 */
class VolumeValidityTest {

    private fun volume(index: Int, from: Int, to: Int, basedOn: Long?) =
        StoryVolume(index = index, fromTurn = from, toTurn = to, text = "第 $index 卷", basedOnMessageId = basedOn)

    @Test
    fun 压这卷时停的那条还在线上就算数() {
        val v = volume(1, 1, 20, basedOn = 50)

        assertEquals(true, VolumeValidity.isActive(v, setOf(10, 30, 50, 70)))
    }

    @Test
    fun 那条不在当前线上就作废() {
        // 50 号留在了另一个分支上
        val v = volume(1, 1, 20, basedOn = 50)

        assertEquals(false, VolumeValidity.isActive(v, setOf(10, 30, 60, 70)))
    }

    @Test
    fun 没记位置的旧卷一律算数() {
        // 升级前压的卷不知道当时停在哪。一律作废的话，
        // 老用户一升级上下文会突然涨回去
        val legacy = volume(1, 1, 20, basedOn = null)

        assertEquals(true, VolumeValidity.isActive(legacy, emptySet()))
    }

    @Test
    fun 只挑当前线上算数的卷() {
        val volumes = listOf(
            volume(1, 1, 10, basedOn = 20),
            volume(2, 11, 20, basedOn = 40),
            volume(3, 21, 30, basedOn = 99),
        )
        // 99 号在别的分支上
        val line = setOf(5L, 20L, 30L, 40L, 60L)

        assertEquals(listOf(volumes[0], volumes[1]), VolumeValidity.activeVolumes(volumes, line))
    }

    @Test
    fun 已压到第几轮只数算数的卷() {
        val volumes = listOf(
            volume(1, 1, 10, basedOn = 20),
            volume(2, 11, 20, basedOn = 99), // 作废
        )
        val line = setOf(5L, 20L, 30L)

        // 第 2 卷作废，只压到第 10 轮 —— 11–20 轮要重新按原文发
        assertEquals(10, VolumeValidity.compactedUpToTurn(volumes, line))
    }

    @Test
    fun 一卷都没有时算作没压过() {
        assertEquals(0, VolumeValidity.compactedUpToTurn(emptyList(), setOf(1L, 2L)))
    }

    @Test
    fun 全部作废时算作没压过() {
        val volumes = listOf(volume(1, 1, 20, basedOn = 99))

        assertEquals(0, VolumeValidity.compactedUpToTurn(volumes, setOf(1L, 2L)))
    }
}

/**
 * 「压缩 → 切版本 → 这一次该发什么」走完整条。
 *
 * 只测两头不够：卷的筛选对了、但发送时拿的是「全部卷」而不是「算数的卷」，
 * 两边各自的用例都会是绿的，接起来才是错的。
 * 这组用例从「压好的卷 + 当前这条线」直接走到「这次发出去的摘要和要排除的轮次」。
 */
class VolumeValidityPipelineTest {

    private fun volume(from: Int, to: Int, basedOn: Long?, text: String) =
        StoryVolume(index = 1, fromTurn = from, toTurn = to, text = text, basedOnMessageId = basedOn)

    @Test
    fun 没切版本时摘要照发() {
        val volumes = listOf(volume(1, 20, basedOn = 40, text = "前二十轮发生的事"))
        val line = setOf(10L, 20L, 40L, 50L)

        val active = VolumeValidity.activeVolumes(volumes, line)
        val section = SummaryPromptBuilder.volumesSection(active)

        assertEquals(true, section!!.contains("前二十轮发生的事"))
        assertEquals(20, VolumeValidity.compactedUpToTurn(volumes, line))
    }

    @Test
    fun 切到更早分叉的版本后摘要一个字都不发() {
        // 卷是停在 40 号时压的，现在这条线上没有 40 号 —— 岔到别的分支了
        val volumes = listOf(volume(1, 20, basedOn = 40, text = "另一条线上发生的事"))
        val line = setOf(10L, 20L, 35L, 60L)

        val active = VolumeValidity.activeVolumes(volumes, line)

        // 摘要段落整个为空，模型读不到那段不属于这条线的剧情
        assertEquals(null, SummaryPromptBuilder.volumesSection(active))
        // 已压到第 0 轮 → 那 20 轮要重新按原文发，不会凭空消失
        assertEquals(0, VolumeValidity.compactedUpToTurn(volumes, line))
    }

    @Test
    fun 多卷时只发还算数的那几卷() {
        val volumes = listOf(
            volume(1, 10, basedOn = 20, text = "共同祖先上的事"),
            volume(11, 20, basedOn = 99, text = "另一条线上的事"),
        )
        val line = setOf(5L, 20L, 30L)

        val section = SummaryPromptBuilder.volumesSection(VolumeValidity.activeVolumes(volumes, line))!!

        assertEquals(true, section.contains("共同祖先上的事"))
        assertEquals(false, section.contains("另一条线上的事"))
        // 第 11–20 轮改回按原文发
        assertEquals(10, VolumeValidity.compactedUpToTurn(volumes, line))
    }

    @Test
    fun 切回原来那条线卷又算数了() {
        // 作废只是这一次不发，数据没动。这是「不删卷」的全部意义
        val volumes = listOf(volume(1, 20, basedOn = 40, text = "前二十轮"))

        val onOtherBranch = setOf(10L, 35L)
        val backOnOriginal = setOf(10L, 40L, 50L)

        assertEquals(0, VolumeValidity.compactedUpToTurn(volumes, onOtherBranch))
        assertEquals(20, VolumeValidity.compactedUpToTurn(volumes, backOnOriginal))
    }

    @Test
    fun 旧版本存的卷在任何线上都照发() {
        // 升级前压的卷没记位置。当它作废的话，老用户一升级上下文会突然涨回去
        val legacy = listOf(volume(1, 20, basedOn = null, text = "升级前压的"))

        assertEquals(20, VolumeValidity.compactedUpToTurn(legacy, setOf(1L)))
        assertEquals(
            true,
            SummaryPromptBuilder.volumesSection(
                VolumeValidity.activeVolumes(legacy, setOf(1L)),
            )!!.contains("升级前压的"),
        )
    }
}
