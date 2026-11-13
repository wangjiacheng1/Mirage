package com.mirage.engine.store

import android.content.Context
import com.mirage.engine.history.StoredMessage
import com.mirage.engine.state.ChangeOperation
import com.mirage.engine.state.GuardFailureCode
import com.mirage.engine.state.StateEvent
import com.mirage.engine.state.StateValueJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 用 Room 实现的存档读写。
 *
 * 这一层只做两件事：调 Dao，以及在「数据库里的样子」和「引擎里的样子」之间来回转。
 * 一行业务判断都不放在这里 —— 判断放进来之后，就只能连着数据库一起测了。
 *
 * 所有方法都切到 IO 线程。Room 本身会拦住主线程调用并抛异常，
 * 但那是运行时才发现；在这里统一切一次，调用方就不用每处都操心。
 */
class RoomChatStore(private val dao: MirageDao) : ChatStore {

    /**
     * 新建一局
     * 返回ID
     */
    override suspend fun createSession(
        title: String,
        cardJson: String?,
        createdAt: Long,
    ): Long = withContext(Dispatchers.IO) {
        dao.insertSession(
            SessionEntity(
                title = title,
                cardJson = cardJson,
                headMessageId = null,
                createdAt = createdAt,
                updatedAt = createdAt,
            ),
        )
    }

    /**
     * 读最近玩过的那一局。
     * 没有任何存档时返回 null。
     */
    override suspend fun loadLatestSession(): SessionRecord? = withContext(Dispatchers.IO) {
        dao.latestSession()?.let { entity ->
            SessionRecord(
                id = entity.id,
                title = entity.title,
                cardJson = entity.cardJson,
                headMessageId = entity.headMessageId,
                updatedAt = entity.updatedAt,
            )
        }
    }

    /**
     * 更新最新条的messageId，
     * 记下这一局聊到哪一条了。
     */
    /** 读某一局。 */
    override suspend fun loadSession(sessionId: Long): SessionRecord? = withContext(Dispatchers.IO) {
        dao.sessionById(sessionId)?.let { entity ->
            SessionRecord(
                id = entity.id,
                title = entity.title,
                cardJson = entity.cardJson,
                headMessageId = entity.headMessageId,
                updatedAt = entity.updatedAt,
            )
        }
    }

    /** 列出全部存档。 */
    override suspend fun listSessions(): List<SessionSummary> = withContext(Dispatchers.IO) {
        dao.listSessions().map { row ->
            SessionSummary(
                id = row.id,
                title = row.title,
                updatedAt = row.updatedAt,
                messageCount = row.messageCount,
            )
        }
    }

    override suspend fun updateHead(
        sessionId: Long,
        headMessageId: Long,
        updatedAt: Long,
    ) = withContext(Dispatchers.IO) {
        dao.updateHead(sessionId, headMessageId, updatedAt)
    }

    /**
     * 追加一条消息，返回它的messageId。
     */
    override suspend fun appendMessage(
        sessionId: Long,
        parentId: Long?,
        fromUser: Boolean,
        text: String,
        turn: Int?,
        createdAt: Long,
    ): Long = withContext(Dispatchers.IO) {
        dao.insertMessage(
            MessageEntity(
                sessionId = sessionId,
                parentId = parentId,
                fromUser = fromUser,
                text = text,
                turn = turn,
                createdAt = createdAt,
            ),
        )
    }

    /**
     * 读这一局的所有消息
     */
    /** 改掉一条消息的文字。 */
    override suspend fun updateMessageText(messageId: Long, text: String) =
        withContext(Dispatchers.IO) {
            dao.updateMessageText(messageId, text)
        }

    /** 删消息，连同挂在它们身上的状态变化记录。 */
    override suspend fun deleteMessages(messageIds: List<Long>) = withContext(Dispatchers.IO) {
        if (messageIds.isEmpty()) return@withContext
        // 先删变化记录再删消息。反过来的话，万一中间出错，
        // 留下的是一堆找不到主人的变化记录，比留下几条消息更难排查
        dao.deleteStateEventsByMessageIds(messageIds)
        dao.deleteReceiptsByMessageIds(messageIds)
        dao.deleteMessagesByIds(messageIds)
    }

    override suspend fun messagesOf(sessionId: Long): List<StoredMessage> =
        withContext(Dispatchers.IO) {
            dao.messagesOf(sessionId).map { entity ->
                StoredMessage(
                    id = entity.id,
                    parentId = entity.parentId,
                    fromUser = entity.fromUser,
                    text = entity.text,
                    turn = entity.turn,
                    createdAt = entity.createdAt,
                )
            }
        }

    /**
     * 追加状态栏的变化记录
     */
    override suspend fun appendStateEvents(
        sessionId: Long,
        events: List<StateEvent>,
    ) = withContext(Dispatchers.IO) {
        if (events.isEmpty()) return@withContext
        dao.insertStateEvents(
            events.map { event ->
                StateEventEntity(
                    sessionId = sessionId,
                    messageId = event.messageId,
                    sequence = event.sequence,
                    revision = event.revision,
                    path = event.path,
                    // 枚举存名字而不是序号：序号会随着枚举增删项而改变，
                    // 那样旧数据的含义就被悄悄改掉了，而且完全看不出来
                    operation = event.operation.name,
                    beforeJson = StateValueJson.encodeToText(event.before),
                    afterJson = StateValueJson.encodeToText(event.after),
                    reason = event.reason,
                    adjustedBy = event.adjustedBy?.name,
                )
            },
        )
    }

    /** 读这一局的所有变化记录。 */
    override suspend fun stateEventsOf(sessionId: Long): List<StateEvent> =
        withContext(Dispatchers.IO) {
            dao.stateEventsOf(sessionId).map { entity ->
                StateEvent(
                    sequence = entity.sequence,
                    messageId = entity.messageId,
                    revision = entity.revision,
                    path = entity.path,
                    operation = operationOf(entity.operation),
                    before = StateValueJson.decodeFromText(entity.beforeJson),
                    after = StateValueJson.decodeFromText(entity.afterJson),
                    reason = entity.reason,
                    adjustedBy = entity.adjustedBy?.let { failureCodeOf(it) },
                )
            }
        }

    /**
     * 下一条变化记录该用的顺序号。
     */
    /** 存一份结算回执。同一条消息重算就覆盖。 */
    override suspend fun saveReceipt(sessionId: Long, receipt: StoredReceipt) =
        withContext(Dispatchers.IO) {
            dao.upsertReceipt(
                ReceiptEntity(
                    messageId = receipt.messageId,
                    sessionId = sessionId,
                    turn = receipt.turn,
                    status = receipt.status,
                    summary = receipt.summary,
                    changesJson = receipt.changesJson,
                    failuresJson = receipt.failuresJson,
                    usageTotalTokens = receipt.usageTotalTokens,
                    usageCost = receipt.usageCost,
                    attempt = receipt.attempt,
                    createdAt = receipt.createdAt,
                ),
            )
        }

    /** 读这一局的全部结算回执。 */
    override suspend fun receiptsOf(sessionId: Long): List<StoredReceipt> =
        withContext(Dispatchers.IO) {
            dao.receiptsOf(sessionId).map { entity ->
                StoredReceipt(
                    messageId = entity.messageId,
                    turn = entity.turn,
                    status = entity.status,
                    summary = entity.summary,
                    changesJson = entity.changesJson,
                    failuresJson = entity.failuresJson,
                    usageTotalTokens = entity.usageTotalTokens,
                    usageCost = entity.usageCost,
                    attempt = entity.attempt,
                    createdAt = entity.createdAt,
                )
            }
        }

    override suspend fun nextEventSequence(sessionId: Long): Long = withContext(Dispatchers.IO) {
        (dao.maxEventSequence(sessionId) ?: 0L) + 1
    }

    /** 删掉这一局的全部数据。 */
    override suspend fun deleteSession(sessionId: Long) = withContext(Dispatchers.IO) {
        // 顺序无所谓，三张表之间没有外键约束。
        // 不加外键是刻意的：加了之后删一局会触发级联，而级联出错时
        // 报的是 SQL 层的约束错误，很难看出是哪条业务操作引起的
        dao.deleteStateEvents(sessionId)
        dao.deleteReceipts(sessionId)
        dao.deleteMessages(sessionId)
        dao.deleteSession(sessionId)
    }

    /**
     * 枚举名转回枚举。
     *
     * 认不出来就退回最常见的那个，不抛异常：一条记录的枚举名对不上
     * （比如装了旧版本 App 读到新版本写的数据），不该让整局存档打不开。
     */
    private fun operationOf(name: String): ChangeOperation =
        ChangeOperation.entries.firstOrNull { it.name == name } ?: ChangeOperation.Set

    private fun failureCodeOf(name: String): GuardFailureCode? =
        GuardFailureCode.entries.firstOrNull { it.name == name }
}

/** 建好数据库并包成存档读写入口。 */
fun createRoomChatStore(context: Context): ChatStore =
    RoomChatStore(createMirageDatabase(context).dao())
