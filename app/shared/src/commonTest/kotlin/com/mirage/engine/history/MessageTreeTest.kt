package com.mirage.engine.history

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 消息树的单测。
 *
 * 两个重点都是「列表存法做不到、而我们必须做到」的事：
 * 同一句话有多个并列版本、从更早一句重新往下长。
 */
class MessageTreeTest {

    //  1 用户
    //  └2 回复甲 ─4 用户追问 ─5 回复丙
    //  └3 回复乙（和甲并列，是同一句话的另一个版本）
    private val tree = MessageTree(
        listOf(
            StoredMessage(id = 1, parentId = null, fromUser = true, text = "第一句"),
            StoredMessage(id = 2, parentId = 1, fromUser = false, text = "回复甲", turn = 1, createdAt = 10),
            StoredMessage(id = 3, parentId = 1, fromUser = false, text = "回复乙", turn = 1, createdAt = 20),
            StoredMessage(id = 4, parentId = 2, fromUser = true, text = "追问"),
            StoredMessage(id = 5, parentId = 4, fromUser = false, text = "回复丙", turn = 2),
        ),
    )

    @Test
    fun 同一句话的多个版本是并列关系() {
        // 列表存法在这里只能二选一：要么覆盖掉旧的，要么排成「模型连说两遍」
        assertEquals(listOf(2L, 3L), tree.childrenOf(1).map { it.id })
        assertEquals(listOf(2L, 3L), tree.alternativesOf(3).map { it.id })
    }

    @Test
    fun 并列版本按先后排序() {
        assertEquals(listOf("回复甲", "回复乙"), tree.childrenOf(1).map { it.text })
    }

    @Test
    fun 取到某条的完整这条线且从旧到新() {
        assertEquals(listOf(1L, 2L, 4L, 5L), tree.lineageOf(5).map { it.id })
    }

    @Test
    fun 另一个版本所在的线不包含对面那条() {
        val ids = tree.lineageIdsOf(3)

        assertEquals(setOf(1L, 3L), ids)
    }

    @Test
    fun 沿着最新的候选一路走到尽头() {
        // 「打开一局继续玩」要靠它找到上次玩到哪
        assertEquals(5L, tree.latestDescendantOf(2)?.id)
    }

    @Test
    fun 没有下一条时就是它自己() {
        assertEquals(3L, tree.latestDescendantOf(3)?.id)
    }

    @Test
    fun 取某条连同它下面长出来的全部内容() {
        // 删除要用：删掉第 2 条，接在它后面写的 4、5 也必须跟着删，
        // 留着就成了没有上文的碎片
        assertEquals(setOf(2L, 4L, 5L), tree.descendantsOf(2))
    }

    @Test
    fun 末端那条的后代只有它自己() {
        assertEquals(setOf(5L), tree.descendantsOf(5))
    }

    @Test
    fun 找不到的编号返回空而不是炸掉() {
        assertEquals(emptyList(), tree.lineageOf(999))
        assertNull(tree.find(999))
    }

    @Test
    fun 数据被写坏成环时不会一直转下去() {
        val broken = MessageTree(
            listOf(
                StoredMessage(id = 1, parentId = 2, fromUser = true, text = "甲"),
                StoredMessage(id = 2, parentId = 1, fromUser = false, text = "乙"),
            ),
        )

        // 抛异常会让整局记录打不开；返回走通的那一段，至少内容还能显示出来
        assertEquals(setOf(1L, 2L), broken.lineageOf(1).map { it.id }.toSet())
    }
}
