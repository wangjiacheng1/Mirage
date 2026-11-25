package com.mirage.engine.summary

/**
 * 在一条对话记录里，按轮次编号找出对应的下标范围。
 *
 * ## 为什么需要这么一个东西
 *
 * 一「轮」是「用户说一句 + AI 回一段」，但**只有 AI 那条带轮次编号**，
 * 用户那条是空的。所以「第 21 到 30 轮在记录里是哪几条」没法逐条判断，
 * 只能靠「最后一条属于第 30 轮的回复」这种边界来划。
 *
 * ## 为什么抽成纯函数
 *
 * 这里全是下标加减一，而且一错就表现得很隐蔽 ——
 * 多切一条就少发一句话，少切一条就把已经压过的内容又发了一遍，
 * 两种都不会报错，只会让人觉得「这 AI 怎么怪怪的」。
 * 放在这里才能逐条断言。
 */
object TurnSlices {

    /**
     * 编号不超过 [turn] 的最后一条在第几个位置。返回 -1 表示一条都没有。
     *
     * 用它来切掉「已经压成摘要」的那一段：这个位置和它之前的全部切掉。
     * 这样同一轮里用户说的那句也会跟着走，不会剩下一句孤零零的提问。
     */
    fun lastIndexUpTo(turns: List<Int?>, turn: Int): Int =
        turns.indexOfLast { it != null && it <= turn }

    /**
     * 第 [fromTurn] 到 [toTurn] 轮在记录里对应的下标区间。找不到时返回 null。
     *
     * 起点取「编号比 [fromTurn] 小的最后一条，再往后一条」。
     * 这样写而不是去找「第 fromTurn - 1 轮」，是因为**编号可能是断的** ——
     * 重新生成过的话，被换掉的那一轮带着编号留在了别的分支上。
     * 「比它小的最后一条」不要求那个编号真的存在，断不断都算得对。
     *
     * 前面没有任何编号更小的（这一段就是这条线的开头）时，起点是 0，
     * 开场消息那些不带编号的也会跟着一起压 —— 它们属于第一轮。
     */
    fun indicesOf(turns: List<Int?>, fromTurn: Int, toTurn: Int): IntRange? {
        val end = lastIndexUpTo(turns, toTurn)
        if (end < 0) return null

        val start = lastIndexUpTo(turns, fromTurn - 1) + 1
        if (start > end) return null
        return start..end
    }
}
