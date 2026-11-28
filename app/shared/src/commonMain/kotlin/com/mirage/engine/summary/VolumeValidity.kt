package com.mirage.engine.summary

/**
 * 判断已经压好的卷还算不算数。
 *
 * ## 为什么需要这件事
 *
 * 对话是一棵树：同一句话下面可以挂几个不同版本（重新生成就会多一个）。
 * 压缩时我们按「当时那条线」把旧轮次总结成一卷，之后这些轮次**不再发原文、只发摘要**。
 *
 * 问题出在切版本：假如卷盖住了前 20 轮，而你切回一个在第 15 轮分叉的旧版本 ——
 * 那一卷讲的是另一条线上发生的事，和现在这条线从第 15 轮起就不一样了。
 * 照发的话模型会读到一段根本没发生过的剧情，而真实的那几轮又被当成「已压缩」排除掉了。
 *
 * ## 判据：压这卷时停的那条消息，还在现在这条线上吗
 *
 * 在 → 现在这条线是当时那条线的延续，这卷仍然算数。
 * 不在 → 已经岔到别的分支上了，这卷作废。
 *
 * 用「在不在这条线上」而不是「轮次编号对不对得上」：编号会跳号
 * （重新生成会拿新编号），按编号比对会漏判。而「在不在线上」问的正是
 * 「还是那条线吗」这件事本身。
 *
 * ## 作废不等于删除
 *
 * 作废只是**这一次不发给模型**，数据一个字都不动。切回原来那条线，它又算数了。
 * 删掉就回不去了，而摘要是花钱压出来的。
 */
object VolumeValidity {

    /**
     * 挑出当前这条线上仍然算数的卷。
     *
     * @param volumes 已经压好的全部卷。
     * @param lineMessageIds 当前这条线上的消息编号。顺序无所谓，只看在不在里面。
     */
    fun activeVolumes(volumes: List<StoryVolume>, lineMessageIds: Set<Long>): List<StoryVolume> =
        volumes.filter { isActive(it, lineMessageIds) }

    /**
     * 这一卷在当前这条线上还算不算数。
     *
     * 没记位置的卷（旧版本存的）一律算数 —— 否则老用户升级后，
     * 之前压好的卷会突然全部失效，上下文一下子涨回去。
     */
    fun isActive(volume: StoryVolume, lineMessageIds: Set<Long>): Boolean {
        val anchor = volume.basedOnMessageId ?: return true
        return anchor in lineMessageIds
    }

    /**
     * 当前这条线上，已经压到第几轮。0 表示没有算数的卷。
     *
     * 只数算数的那些卷 —— 作废的卷盖住的轮次要重新发原文，
     * 不然那几轮就凭空消失了。
     */
    fun compactedUpToTurn(volumes: List<StoryVolume>, lineMessageIds: Set<Long>): Int =
        activeVolumes(volumes, lineMessageIds).maxOfOrNull { it.toTurn } ?: 0
}
