package com.mirage.engine.store

import com.mirage.engine.state.ChangeOperation
import com.mirage.engine.state.StateEvent
import com.mirage.engine.state.StateValue
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 备份导出与导入的单测。
 *
 * 重点在两件事：**原样带得回来**，以及**导入不会毁掉现有存档**。
 * 备份出错的代价很特殊 —— 发现时文件已经写出去了，救不回来。
 */
class BackupServiceTest {

    /** 建一局带两条消息、一次数值变化的存档。 */
    private suspend fun seed(store: ChatStore, title: String): Long {
        val sessionId = store.createSession(title, """{"name":"某角色"}""", 100)
        val first = store.appendMessage(sessionId, null, true, "你好", null, 101)
        val second = store.appendMessage(sessionId, first, false, "你也好", 1, 102)
        store.appendStateEvents(
            sessionId,
            listOf(
                StateEvent(
                    sequence = 1,
                    messageId = second,
                    revision = 1,
                    path = "某组/甲",
                    operation = ChangeOperation.Set,
                    before = StateValue.IntValue(60),
                    after = StateValue.IntValue(49),
                ),
            ),
        )
        store.updateHead(sessionId, second, 103)
        return sessionId
    }

    @Test
    fun 导出再导入内容一致() = runTest {
        val source = InMemoryChatStore()
        seed(source, "第一局")

        val text = BackupService.encode(BackupService.export(source, exportedAt = 999))
        val target = InMemoryChatStore()
        val document = (BackupService.decode(text) as BackupReadResult.Success).document
        val summary = BackupService.import(target, document)

        assertEquals(1, summary.sessionCount)
        assertEquals(2, summary.messageCount)
        assertEquals(1, summary.stateEventCount)
        assertEquals(0, summary.skippedMessageCount)

        val restored = target.listSessions().single()
        assertEquals("第一局", restored.title)
        assertEquals(2, restored.messageCount)
    }

    @Test
    fun 导入后父子关系仍然接得上() = runTest {
        val source = InMemoryChatStore()
        seed(source, "第一局")
        val document = BackupService.export(source, 1)

        val target = InMemoryChatStore()
        BackupService.import(target, document)

        val sessionId = target.listSessions().single().id
        val messages = target.messagesOf(sessionId)
        val root = messages.first { it.parentId == null }
        val child = messages.first { it.parentId != null }
        // 编号全部重新分配过，但父子关系必须还对得上
        assertEquals(root.id, child.parentId)
    }

    @Test
    fun 导入会重新分配编号而不是沿用文件里的() = runTest {
        val target = InMemoryChatStore()
        // 目标库里先有一局，占掉前面的编号
        seed(target, "已有的一局")

        val source = InMemoryChatStore()
        seed(source, "要导入的一局")
        val document = BackupService.export(source, 1)
        BackupService.import(target, document)

        val imported = target.listSessions().first { it.title == "要导入的一局" }
        val ids = target.messagesOf(imported.id).map { it.id }
        // 沿用文件里的编号会和现有消息撞号，两局的消息会被接到一起
        assertTrue(ids.all { it > 2 }, "导入的消息编号应避开已有编号，实际是 $ids")
    }

    @Test
    fun 导入是加进来不是盖掉() = runTest {
        val target = InMemoryChatStore()
        seed(target, "原有的一局")

        val source = InMemoryChatStore()
        seed(source, "导入的一局")
        BackupService.import(target, BackupService.export(source, 1))

        // 点错文件就毁掉正在玩的进度，这种代价不能接受
        assertEquals(2, target.listSessions().size)
        assertTrue(target.listSessions().any { it.title == "原有的一局" })
    }

    @Test
    fun 数值变化跟着挂到新的消息编号上() = runTest {
        val source = InMemoryChatStore()
        seed(source, "第一局")

        val target = InMemoryChatStore()
        BackupService.import(target, BackupService.export(source, 1))

        val sessionId = target.listSessions().single().id
        val event = target.stateEventsOf(sessionId).single()
        val messageIds = target.messagesOf(sessionId).map { it.id }
        assertTrue(event.messageId in messageIds, "变化记录必须挂在导入后的真实消息上")
        assertEquals(StateValue.IntValue(49), event.after)
    }

    @Test
    fun 聊到哪一条也跟着换编号() = runTest {
        val source = InMemoryChatStore()
        seed(source, "第一局")

        val target = InMemoryChatStore()
        BackupService.import(target, BackupService.export(source, 1))

        val restored = target.loadSession(target.listSessions().single().id)!!
        val messageIds = target.messagesOf(restored.id).map { it.id }
        assertTrue(restored.headMessageId in messageIds)
    }

    @Test
    fun 消息顺序被打乱也能接上树() = runTest {
        // 备份文件是可以被手工改过的，不能假设它一定是先父后子
        val document = BackupDocument(
            sessions = listOf(
                BackupSession(
                    title = "乱序的一局",
                    messages = listOf(
                        BackupMessage(id = 2, parentId = 1, fromUser = false, text = "后一条"),
                        BackupMessage(id = 1, parentId = null, fromUser = true, text = "前一条"),
                    ),
                ),
            ),
        )

        val target = InMemoryChatStore()
        val summary = BackupService.import(target, document)

        assertEquals(2, summary.messageCount)
        assertEquals(0, summary.skippedMessageCount)
    }

    @Test
    fun 上一条找不到的消息被跳过而不是整次导入失败() = runTest {
        val document = BackupDocument(
            sessions = listOf(
                BackupSession(
                    title = "有坏数据的一局",
                    messages = listOf(
                        BackupMessage(id = 1, parentId = null, fromUser = true, text = "正常的"),
                        BackupMessage(id = 2, parentId = 99, fromUser = false, text = "上一条不存在"),
                    ),
                ),
            ),
        )

        val target = InMemoryChatStore()
        val summary = BackupService.import(target, document)

        assertEquals(1, summary.messageCount)
        assertEquals(1, summary.skippedMessageCount)
    }

    @Test
    fun 不是备份文件时给出明确分类() {
        assertEquals(
            BackupReadResult.Failure(BackupReadFailure.Malformed),
            BackupService.decode("这不是 JSON"),
        )
    }

    @Test
    fun 版本比我们新的文件拒绝读取() {
        val text = BackupService.encode(
            BackupDocument(formatVersion = BackupDocument.CURRENT_FORMAT_VERSION + 1),
        )

        // 硬读会缺字段，而缺了什么用户根本看不出来，不如直接说读不了
        assertEquals(
            BackupReadResult.Failure(BackupReadFailure.UnsupportedVersion),
            BackupService.decode(text),
        )
    }

    @Test
    fun 备份文件里绝不出现访问密钥相关字段() = runTest {
        val source = InMemoryChatStore()
        seed(source, "第一局")

        val text = BackupService.encode(BackupService.export(source, 1))

        // 备份会被发到电脑、传进网盘，密钥一旦跟着走就是直接的金钱损失
        listOf("apiKey", "api_key", "credential", "secret", "Authorization").forEach { word ->
            assertEquals(false, text.contains(word, ignoreCase = true), "备份里不该出现 $word")
        }
    }

    @Test
    fun 空库导出得到一份空备份() = runTest {
        val document = BackupService.export(InMemoryChatStore(), 1)

        assertEquals(emptyList(), document.sessions)
        assertNotEquals(0, BackupService.encode(document).length)
    }
}
