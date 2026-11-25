package com.mirage.engine.summary

/**
 * 什么时候该压缩历史。
 *
 * 三种模式互斥：要么只在用户点按钮时压，要么按轮数自动压，要么按上下文占比自动压。
 * 不做「两个条件同时生效」—— 两个自动条件一起开，用户根本说不清这次是哪个触发的。
 */
sealed interface CompactionTrigger {

    /** 只在用户点按钮时压。默认就是这个：压缩是有损的，不该背着人做。 */
    data object Manual : CompactionTrigger

    /** 每聊够 [turns] 轮就自动压一次。 */
    data class EveryNTurns(val turns: Int) : CompactionTrigger

    /**
     * 这次要发出去的内容占到模型窗口的 [percent]% 就自动压一次。
     *
     * 比按轮数更贴合实际 —— 有人一轮写三行，有人一轮写三千字。
     * 但它要求我们知道模型窗口有多大，不知道的时候这个模式用不了。
     */
    data class ContextPercent(val percent: Int) : CompactionTrigger
}

/** 这一次该做什么。 */
sealed interface CompactionPlan {

    /** 什么都不用做。 */
    data object Nothing : CompactionPlan

    /**
     * 把第 [fromTurn] 到 [toTurn] 轮压成新的一卷。
     *
     * @property volumeIndex 新卷是第几卷。
     */
    data class NewVolume(
        val fromTurn: Int,
        val toTurn: Int,
        val volumeIndex: Int,
    ) : CompactionPlan

    /**
     * 把已有的这些卷合并成一卷「前情总览」。
     *
     * 卷攒多了之后，光是摘要本身就要占掉一大块上下文，这时才合并一次。
     * 这是唯一允许二次总结的场合。
     */
    data class MergeVolumes(val volumes: List<StoryVolume>) : CompactionPlan
}

/**
 * 算出这一次该不该压缩、压哪几轮。
 *
 * ## 这一步在整条链路里的位置
 *
 * 它只做判断，不发请求、不写库。调用方拿到计划之后才去请求模型总结，
 * 总结回来再存成一卷。拆开的好处是**判断逻辑可以完全离线验证** ——
 * 「聊了 40 轮、已压到第 20 轮、要保留最近 10 轮，那么该压 21 到 30 轮」
 * 这种事不该需要网络才能测。
 *
 * ## 一次只给一个动作
 *
 * 既要出新卷又要合并旧卷的情况是存在的，但这里只返回一个。
 * 调用方做完会再问一次，于是自然收敛。这样每一步都看得清，
 * 也不会出现「一次压缩里两个动作一个成了一个败了」的半成品状态。
 */
object CompactionPlanner {

    /**
     * 保留多少轮原文不压。
     *
     * 最近发生的事必须是原话。摘要再好也会丢掉语气、称呼、具体用词，
     * 而这些恰恰是接下来几轮要接住的东西。
     */
    const val DEFAULT_KEEP_RECENT_TURNS: Int = 10

    /**
     * 所有卷加起来占到窗口多少比例时，合并成一卷前情总览。
     *
     * 这个数字先照抄行业里已有的实现，等我们自己有实测数据再调。
     */
    const val MERGE_THRESHOLD_PERCENT: Int = 15

    /**
     * 判断该压缩多少轮
     * @param completedTurns 已经聊完的轮次，从小到大。只有聊完的才能压 ——
     *   正在生成的那一轮内容还不全。
     * @param volumes 已经压好的卷。
     * @param trigger 压缩模式。
     * @param keepRecentTurns 保留几轮原文不压。
     * @param estimatedPromptTokens 这次要发出去的内容估算有多少 token。
     *   只有按占比触发的模式用得上。
     * @param contextTokens 模型窗口。为 null 表示不知道，这时按占比触发的模式失效。
     * @param volumeTokens 所有卷加起来估算有多少 token。
     */
    fun plan(
        completedTurns: List<Int>,
        volumes: List<StoryVolume>,
        trigger: CompactionTrigger,
        keepRecentTurns: Int = DEFAULT_KEEP_RECENT_TURNS,
        estimatedPromptTokens: Int = 0,
        contextTokens: Int? = null,
        volumeTokens: Int = 0,
    ): CompactionPlan {
        val compactable = compactableTurns(completedTurns, volumes, keepRecentTurns)

        if (compactable.isNotEmpty() &&
            shouldCompact(trigger, compactable.size, estimatedPromptTokens, contextTokens)
        ) {
            return CompactionPlan.NewVolume(
                fromTurn = compactable.first(),
                toTurn = compactable.last(),
                volumeIndex = (volumes.maxOfOrNull { it.index } ?: 0) + 1,
            )
        }

        // 没有新内容可压，再看看旧卷是不是攒得太多了。
        // 只有两卷以上才有合并的意义 —— 一卷「合并」成一卷只是白花一次钱
        if (volumes.size >= 2 && contextTokens != null &&
            volumeTokens * 100 >= contextTokens * MERGE_THRESHOLD_PERCENT
        ) {
            return CompactionPlan.MergeVolumes(volumes)
        }

        return CompactionPlan.Nothing
    }

    /**
     * 哪些轮次可以压：已经聊完、还没被压过、又不在「保留最近几轮」里面。
     *
     * 🔴 **不要求轮次编号连着**。编号在整棵对话树里是唯一的，
     * 所以只要重新生成过一次，当前这条线上的编号就一定有空洞：
     * 重新生成会拿一个新编号，被换掉的那条带着旧编号留在另一个分支上。
     *
     * 传进来的本来就是「当前这条线上已经聊完的那些轮」，按先后排好。
     * 挨着的两项在这条线上就是挨着的，中间空掉的编号属于别的分支，
     * 本来就不该算进来。
     */
    fun compactableTurns(
        completedTurns: List<Int>,
        volumes: List<StoryVolume>,
        keepRecentTurns: Int = DEFAULT_KEEP_RECENT_TURNS,
    ): List<Int> {
        val compactedUpTo = volumes.maxOfOrNull { it.toTurn } ?: 0
        val sorted = completedTurns.sorted()

        // 最近这几轮留着原文，不参与压缩
        val candidates = sorted
            .dropLast(keepRecentTurns.coerceAtLeast(0))
            .filter { it > compactedUpTo }

        return candidates
    }

    /** 够不够触发压缩条件。 */
    private fun shouldCompact(
        trigger: CompactionTrigger,
        compactableCount: Int,
        estimatedPromptTokens: Int,
        contextTokens: Int?,
    ): Boolean = when (trigger) {
        // 手动模式下，用户既然点了按钮，有东西可压就压
        CompactionTrigger.Manual -> true

        is CompactionTrigger.EveryNTurns -> compactableCount >= trigger.turns

        // 不知道窗口多大就不触发。这个模式的全部依据就是那个比例，
        // 窗口拿不到的话算出来的比例没有意义
        is CompactionTrigger.ContextPercent ->
            contextTokens != null && contextTokens > 0 &&
                estimatedPromptTokens * 100 >= contextTokens * trigger.percent
    }
}
