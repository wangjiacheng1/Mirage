package com.mirage.engine.store

import com.mirage.engine.history.StoredMessage
import com.mirage.engine.state.StateEvent

/**
 * 存在内存里的存档，专供测试用。
 *
 * 有了它，导出导入的逻辑可以完全离开数据库来测 —— 而「备份对不对」
 * 恰恰是最该测透的：它出错时用户手上那份文件已经写出去了，救不回来。
 */
class InMemoryChatStore : ChatStore {

    private data class Row(
        var title: String,
        var cardJson: String?,
        var headMessageId: Long?,
        var updatedAt: Long,
        val messages: MutableList<StoredMessage> = mutableListOf(),
        val events: MutableList<StateEvent> = mutableListOf(),
        val receipts: MutableMap<Long, StoredReceipt> = mutableMapOf(),
    )

    private val sessions = linkedMapOf<Long, Row>()
    private var nextSessionId = 1L
    private var nextMessageId = 1L

    override suspend fun createSession(title: String, cardJson: String?, createdAt: Long): Long {
        val id = nextSessionId++
        sessions[id] = Row(title = title, cardJson = cardJson, headMessageId = null, updatedAt = createdAt)
        return id
    }

    override suspend fun loadLatestSession(): SessionRecord? =
        sessions.entries.maxByOrNull { it.value.updatedAt }?.let { recordOf(it.key, it.value) }

    override suspend fun loadSession(sessionId: Long): SessionRecord? =
        sessions[sessionId]?.let { recordOf(sessionId, it) }

    override suspend fun listSessions(): List<SessionSummary> = sessions.entries
        .sortedByDescending { it.value.updatedAt }
        .map { SessionSummary(it.key, it.value.title, it.value.updatedAt, it.value.messages.size) }

    override suspend fun updateHead(sessionId: Long, headMessageId: Long, updatedAt: Long) {
        sessions[sessionId]?.apply {
            this.headMessageId = headMessageId
            this.updatedAt = updatedAt
        }
    }

    override suspend fun appendMessage(
        sessionId: Long,
        parentId: Long?,
        fromUser: Boolean,
        text: String,
        turn: Int?,
        createdAt: Long,
    ): Long {
        val id = nextMessageId++
        sessions[sessionId]?.messages?.add(
            StoredMessage(id, parentId, fromUser, text, turn, createdAt),
        )
        return id
    }

    override suspend fun updateMessageText(messageId: Long, text: String) {
        sessions.values.forEach { row ->
            val index = row.messages.indexOfFirst { it.id == messageId }
            if (index >= 0) row.messages[index] = row.messages[index].copy(text = text)
        }
    }

    override suspend fun deleteMessages(messageIds: List<Long>) {
        sessions.values.forEach { row ->
            row.messages.removeAll { it.id in messageIds }
            row.events.removeAll { it.messageId in messageIds }
            messageIds.forEach { row.receipts.remove(it) }
        }
    }

    override suspend fun messagesOf(sessionId: Long): List<StoredMessage> =
        sessions[sessionId]?.messages?.toList() ?: emptyList()

    override suspend fun appendStateEvents(sessionId: Long, events: List<StateEvent>) {
        sessions[sessionId]?.events?.addAll(events)
    }

    override suspend fun stateEventsOf(sessionId: Long): List<StateEvent> =
        sessions[sessionId]?.events?.sortedBy { it.sequence } ?: emptyList()

    override suspend fun saveReceipt(sessionId: Long, receipt: StoredReceipt) {
        sessions[sessionId]?.receipts?.put(receipt.messageId, receipt)
    }

    override suspend fun receiptsOf(sessionId: Long): List<StoredReceipt> =
        sessions[sessionId]?.receipts?.values?.toList() ?: emptyList()

    override suspend fun nextEventSequence(sessionId: Long): Long =
        (sessions[sessionId]?.events?.maxOfOrNull { it.sequence } ?: 0L) + 1

    override suspend fun deleteSession(sessionId: Long) {
        sessions.remove(sessionId)
    }

    private fun recordOf(id: Long, row: Row) = SessionRecord(
        id = id,
        title = row.title,
        cardJson = row.cardJson,
        headMessageId = row.headMessageId,
        updatedAt = row.updatedAt,
    )
}
