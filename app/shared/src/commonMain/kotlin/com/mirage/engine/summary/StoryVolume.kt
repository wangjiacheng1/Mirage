package com.mirage.engine.summary

/**
 * 一卷剧情摘要：把第 [fromTurn] 到 [toTurn] 轮的原文压成一段话。
 *
 * ## 为什么要分卷，而不是只留一份「全部历史的摘要」
 *
 * 只留一份的话，每次压缩都得把「上一份摘要 + 新发生的事」重新总结一遍。
 * 早期剧情于是被反复有损压缩，聊得越久越糊 —— 表现出来就是
 * 「它忘了我的衣服是什么颜色」「它记不住这个人是谁」。
 *
 * 分卷的做法是：**每一卷只被总结一次，之后原样保留、再也不动它**。
 * 新压的内容写成新的一卷，和旧卷并排放着。
 *
 * @property index 第几卷，从 1 开始。只用来显示和排序。
 * @property merged 是不是多卷合并来的「前情总览」。卷攒多了会再合并一次，
 *   那一次是唯一允许二次总结的场合，所以要能分辨出来。
 * @property basedOnMessageId 压这一卷的时候，对话停在哪一条消息上。
 *   为 null 表示这卷是旧版本存的、不知道当时停在哪 —— 那就一律当它仍然算数，
 *   不然老用户升级后已经压好的卷会突然全部失效。
 */
data class StoryVolume(
    val index: Int,
    val fromTurn: Int,
    val toTurn: Int,
    val text: String,
    val merged: Boolean = false,
    val basedOnMessageId: Long? = null,
) {
    /** 这一卷盖住了几轮。 */
    val turnCount: Int get() = toTurn - fromTurn + 1
}
