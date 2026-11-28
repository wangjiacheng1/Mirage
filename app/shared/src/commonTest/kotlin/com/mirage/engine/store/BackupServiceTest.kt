package com.mirage.engine.store

import com.mirage.engine.state.ChangeOperation
import com.mirage.engine.state.StateEvent
import com.mirage.engine.state.StateValue
import com.mirage.engine.summary.StoryVolume
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

/**
 * 结算回执和剧情摘要的备份。
 *
 * 这两样是后来才加进备份的，而它们的性质不一样：
 * 回执**挂在某条消息上**，导入时要跟着换编号；摘要**按轮次范围记**，不挂消息。
 *
 * 其中摘要那一段最要紧 —— 它是花钱压出来的，而且**无法从原文恢复**
 * （重压一次会得到另一段文字）。丢了就得再花一次钱压一遍。
 */
class BackupExtrasTest {

    /** 建一局带回执和两卷摘要的存档。 */
    private suspend fun seed(store: ChatStore): Long {
        val sessionId = store.createSession("某一局", null, 100)
        val first = store.appendMessage(sessionId, null, true, "你好", null, 101)
        val reply = store.appendMessage(sessionId, first, false, "你也好", 1, 102)

        store.saveReceipt(
            sessionId = sessionId,
            receipt = StoredReceipt(
                messageId = reply,
                turn = 1,
                status = "Updated",
                summary = "发生了一些事",
                changesJson = """[{"path":"某组/甲"}]""",
                failuresJson = "[]",
                usageTotalTokens = 234,
                usageCost = 0.00042,
                attempt = 2,
                createdAt = 103,
            ),
        )
        store.saveVolume(
            sessionId,
            StoryVolume(index = 1, fromTurn = 1, toTurn = 10, text = "前十轮发生的事"),
            createdAt = 104,
        )
        store.saveVolume(
            sessionId,
            StoryVolume(index = 2, fromTurn = 11, toTurn = 20, text = "后十轮发生的事", merged = true),
            createdAt = 105,
        )
        store.updateHead(sessionId, reply, 106)
        return sessionId
    }

    private suspend fun roundTrip(source: ChatStore): Pair<ChatStore, BackupImportSummary> {
        val text = BackupService.encode(BackupService.export(source, exportedAt = 999))
        val target = InMemoryChatStore()
        val document = (BackupService.decode(text) as BackupReadResult.Success).document
        return target to BackupService.import(target, document)
    }

    // ------------------------------------------------------------------
    // 往返
    // ------------------------------------------------------------------

    @Test
    fun 摘要卷原样带得回来() = runTest {
        val source = InMemoryChatStore()
        seed(source)

        val (target, summary) = roundTrip(source)

        assertEquals(2, summary.volumeCount)
        val sessionId = target.listSessions().single().id
        val volumes = target.volumesOf(sessionId)
        assertEquals(2, volumes.size)

        val first = volumes.first().volume
        assertEquals(1, first.index)
        assertEquals(1, first.fromTurn)
        assertEquals(10, first.toTurn)
        assertEquals("前十轮发生的事", first.text)
        assertFalse(first.merged)

        // 合并标记也要带回来：它决定这一卷还能不能再参与合并
        assertTrue(volumes.last().volume.merged)
    }

    @Test
    fun 回执原样带得回来() = runTest {
        val source = InMemoryChatStore()
        seed(source)

        val (target, summary) = roundTrip(source)

        assertEquals(1, summary.receiptCount)
        val sessionId = target.listSessions().single().id
        val receipt = target.receiptsOf(sessionId).single()

        assertEquals(1, receipt.turn)
        assertEquals("Updated", receipt.status)
        assertEquals("发生了一些事", receipt.summary)
        assertEquals("""[{"path":"某组/甲"}]""", receipt.changesJson)
        assertEquals(234, receipt.usageTotalTokens)
        assertEquals(0.00042, receipt.usageCost)
        // 第几次尝试也要留着，不然重算过的那一轮看起来像一次就成了
        assertEquals(2, receipt.attempt)
    }

    @Test
    fun 回执要挂到新分配的消息编号上() = runTest {
        // 导入时所有编号都会重新分配。回执跟不上的话，
        // 它就挂在一个不存在的编号上，界面上永远不显示
        val source = InMemoryChatStore()
        seed(source)

        val (target, _) = roundTrip(source)
        val sessionId = target.listSessions().single().id

        val replyId = target.messagesOf(sessionId).single { !it.fromUser }.id
        assertEquals(replyId, target.receiptsOf(sessionId).single().messageId)
    }

    // ------------------------------------------------------------------
    // 旧文件
    // ------------------------------------------------------------------

    @Test
    fun 第一版的旧文件照样读得进来() = runTest {
        // 旧文件里压根没有这两段。解析出来应该是「没有回执、没有摘要」，
        // 而不是报错读不了 —— 那正是它当时的真实情况
        val oldFile = """
        {
          "formatVersion": 1,
          "exportedAt": 1,
          "sessions": [
            {
              "title": "旧存档",
              "messages": [
                {"id": 1, "fromUser": true, "text": "你好"}
              ]
            }
          ]
        }
        """.trimIndent()

        val document = (BackupService.decode(oldFile) as BackupReadResult.Success).document
        val target = InMemoryChatStore()
        val summary = BackupService.import(target, document)

        assertEquals(1, summary.sessionCount)
        assertEquals(1, summary.messageCount)
        assertEquals(0, summary.receiptCount)
        assertEquals(0, summary.volumeCount)
    }

    @Test
    fun 没有回执和摘要的存档导出来也不报错() = runTest {
        val source = InMemoryChatStore()
        val sessionId = source.createSession("干净的一局", null, 100)
        source.appendMessage(sessionId, null, true, "你好", null, 101)

        val (target, summary) = roundTrip(source)

        assertEquals(0, summary.receiptCount)
        assertEquals(0, summary.volumeCount)
        assertEquals(emptyList(), target.volumesOf(target.listSessions().single().id))
    }

    // ------------------------------------------------------------------
    // 接不上的内容
    // ------------------------------------------------------------------

    @Test
    fun 挂在接不上的消息上的回执要被跳过并报出来() = runTest {
        // 这张回执挂的是 99 号，而文件里没有 99 号消息。
        // 它没有归属，只能跳过 —— 但必须当场说出来，不能悄悄少一张
        val document = BackupDocument(
            sessions = listOf(
                BackupSession(
                    title = "某一局",
                    messages = listOf(BackupMessage(id = 1, fromUser = true, text = "你好")),
                    receipts = listOf(
                        BackupReceipt(
                            messageId = 99,
                            turn = 1,
                            status = "Updated",
                            changesJson = "[]",
                            failuresJson = "[]",
                        ),
                    ),
                ),
            ),
        )

        val target = InMemoryChatStore()
        val summary = BackupService.import(target, document)

        assertEquals(0, summary.receiptCount)
        assertEquals(1, summary.skippedReceiptCount)
    }

    @Test
    fun 摘要卷不受消息能不能导入影响() = runTest {
        // 摘要按轮次范围记，不挂在任何一条消息上。
        // 所以即使消息全都接不上，摘要也该照样存进来 ——
        // 它是花钱压出来的，没有理由因为别的东西出问题就一起丢掉
        val document = BackupDocument(
            sessions = listOf(
                BackupSession(
                    title = "某一局",
                    // 上一条是 99 号，文件里没有，这条接不上树
                    messages = listOf(BackupMessage(id = 1, parentId = 99, fromUser = true, text = "你好")),
                    volumes = listOf(
                        BackupVolume(index = 1, fromTurn = 1, toTurn = 10, text = "前十轮"),
                    ),
                ),
            ),
        )

        val target = InMemoryChatStore()
        val summary = BackupService.import(target, document)

        assertEquals(0, summary.messageCount)
        assertEquals(1, summary.skippedMessageCount)
        assertEquals(1, summary.volumeCount)
    }

    // ------------------------------------------------------------------
    // 格式版本
    // ------------------------------------------------------------------

    @Test
    fun 导出的文件写的是第二版() = runTest {
        val source = InMemoryChatStore()
        seed(source)

        val document = BackupService.export(source, exportedAt = 1)

        assertEquals(2, document.formatVersion)
    }

    @Test
    fun 备份里仍然不含访问密钥() = runTest {
        // 加了两段新内容，这条底线要重新确认一次：
        // 备份会被发到电脑、传进网盘，密钥跟着走就是直接的金钱损失
        val source = InMemoryChatStore()
        seed(source)

        val text = BackupService.encode(BackupService.export(source, exportedAt = 1))

        listOf("Authorization", "Bearer", "apiKey", "credential", "sk-").forEach {
            assertFalse(text.contains(it, ignoreCase = true), "备份里出现了：$it")
        }
    }
}
