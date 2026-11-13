package com.mirage.engine.store

import com.mirage.engine.state.ChangeOperation
import com.mirage.engine.state.GuardFailureCode
import com.mirage.engine.state.StateEvent
import com.mirage.engine.state.StateValueJson
import kotlinx.serialization.json.Json

/** 读备份文件的结果。 */
sealed interface BackupReadResult {
    data class Success(val document: BackupDocument) : BackupReadResult

    data class Failure(val code: BackupReadFailure) : BackupReadResult
}

enum class BackupReadFailure {
    /** 不是 JSON，或者结构对不上。 */
    Malformed,

    /** 是备份文件，但版本比这个 App 认识的还新。 */
    UnsupportedVersion,
}

/**
 * 导入完成后的统计。
 *
 * 跳过的数量要如实报出来。导入这种一次性操作，**用户只有当场能发现问题**，
 * 事后翻几百条消息根本找不出少了哪条。
 */
data class BackupImportSummary(
    val sessionCount: Int = 0,
    val messageCount: Int = 0,
    val stateEventCount: Int = 0,
    /** 上一条找不到、接不上树的消息。它们连同后面的内容一起被跳过。 */
    val skippedMessageCount: Int = 0,
    /** 挂在被跳过消息上的数值变化。 */
    val skippedEventCount: Int = 0,
)

/**
 * 全量备份的导出与导入。
 *
 * 没有云同步，换手机、重装、清数据之后要能把东西带回来，只有这一条路。
 *
 * ## 导入是「加进来」，不是「盖掉」
 *
 * 导入的每一局都会**新建**，现有的存档一局都不动。覆盖式导入看起来更"干净"，
 * 但只要用户点错一次文件，正在玩的进度就没了，而且不可撤销。
 * 多出几局重复的，用户自己删掉就行 —— 两种错误的代价差太远。
 */
object BackupService {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 把库里所有存档打包成一份备份。 */
    suspend fun export(store: ChatStore, exportedAt: Long): BackupDocument {
        val sessions = store.listSessions().mapNotNull { summary ->
            val record = store.loadSession(summary.id) ?: return@mapNotNull null
            BackupSession(
                title = record.title,
                cardJson = record.cardJson,
                updatedAt = record.updatedAt,
                headMessageId = record.headMessageId,
                messages = store.messagesOf(summary.id).map { message ->
                    BackupMessage(
                        id = message.id,
                        parentId = message.parentId,
                        fromUser = message.fromUser,
                        text = message.text,
                        turn = message.turn,
                        createdAt = message.createdAt,
                    )
                },
                stateEvents = store.stateEventsOf(summary.id).map { event ->
                    BackupStateEvent(
                        messageId = event.messageId,
                        sequence = event.sequence,
                        revision = event.revision,
                        path = event.path,
                        operation = event.operation.name,
                        beforeJson = StateValueJson.encodeToText(event.before),
                        afterJson = StateValueJson.encodeToText(event.after),
                        reason = event.reason,
                        adjustedBy = event.adjustedBy?.name,
                    )
                },
            )
        }
        return BackupDocument(exportedAt = exportedAt, sessions = sessions)
    }

    /** 备份转成要写进文件的文本。 */
    fun encode(document: BackupDocument): String =
        json.encodeToString(BackupDocument.serializer(), document)

    /** 读一份备份文件。 */
    fun decode(text: String): BackupReadResult {
        val document = try {
            json.decodeFromString(BackupDocument.serializer(), text)
        } catch (failure: Exception) {
            // 异常类型跨平台不完全一致，只认某一种会在某个平台上漏掉，
            // 表现为导入时直接崩掉
            return BackupReadResult.Failure(BackupReadFailure.Malformed)
        }
        if (document.formatVersion > BackupDocument.CURRENT_FORMAT_VERSION) {
            // 版本更新的文件可能带着我们不认识的字段。硬读进来会缺东西，
            // 而缺了什么用户根本看不出来，不如直接说读不了
            return BackupReadResult.Failure(BackupReadFailure.UnsupportedVersion)
        }
        return BackupReadResult.Success(document)
    }

    /**
     * 把备份里的内容加进库。
     *
     * 每一局都新建，编号全部重新分配 —— 文件里的编号和库里现有的编号
     * 几乎必然冲突，直接用会把两局的消息接到一起。
     */
    suspend fun import(store: ChatStore, document: BackupDocument): BackupImportSummary {
        var messageCount = 0
        var eventCount = 0
        var skippedMessages = 0
        var skippedEvents = 0

        document.sessions.forEach { session ->
            val sessionId = store.createSession(
                title = session.title,
                cardJson = session.cardJson,
                createdAt = session.updatedAt,
            )

            // 文件里的编号 → 库里新分配的编号
            val idMap = mutableMapOf<Long, Long>()
            val inserted = insertMessages(store, sessionId, session.messages, idMap)
            messageCount += inserted
            skippedMessages += session.messages.size - inserted

            val events = session.stateEvents.mapNotNull { event ->
                val messageId = idMap[event.messageId]
                if (messageId == null) {
                    // 它挂的那条消息没能导进来，这条变化就没有归属了
                    skippedEvents += 1
                    return@mapNotNull null
                }
                StateEvent(
                    sequence = event.sequence,
                    messageId = messageId,
                    revision = event.revision,
                    path = event.path,
                    operation = operationOf(event.operation),
                    before = StateValueJson.decodeFromText(event.beforeJson),
                    after = StateValueJson.decodeFromText(event.afterJson),
                    reason = event.reason,
                    adjustedBy = event.adjustedBy?.let { name ->
                        GuardFailureCode.entries.firstOrNull { it.name == name }
                    },
                )
            }
            store.appendStateEvents(sessionId, events)
            eventCount += events.size

            // 聊到哪一条也要跟着换编号。换不出来就退回最后插入的那条，
            // 总比让这一局打开后空白要好
            val head = session.headMessageId?.let { idMap[it] } ?: idMap.values.lastOrNull()
            if (head != null) store.updateHead(sessionId, head, session.updatedAt)
        }

        return BackupImportSummary(
            sessionCount = document.sessions.size,
            messageCount = messageCount,
            stateEventCount = eventCount,
            skippedMessageCount = skippedMessages,
            skippedEventCount = skippedEvents,
        )
    }

    /**
     * 按「先父后子」的顺序插入消息。
     *
     * 不能照文件里的顺序直接插：一条消息的上一条必须已经插好、拿到新编号，
     * 否则接不上树。文件是我们自己导出的，顺序本来就是对的，但**备份文件
     * 是可以被手工改过的**，不能假设它一定有序。
     *
     * 做法是反复扫描，每轮只插「上一条已经就位」的那些，直到一轮下来
     * 一条都插不动为止。剩下的就是接不上的，跳过。
     */
    private suspend fun insertMessages(
        store: ChatStore,
        sessionId: Long,
        messages: List<BackupMessage>,
        idMap: MutableMap<Long, Long>,
    ): Int {
        val pending = messages.toMutableList()
        var inserted = 0

        while (pending.isNotEmpty()) {
            val ready = pending.filter { it.parentId == null || idMap.containsKey(it.parentId) }
            // 一轮下来一条都插不动，说明剩下的上一条要么不存在、要么互相成环
            if (ready.isEmpty()) break

            ready.forEach { message ->
                val newId = store.appendMessage(
                    sessionId = sessionId,
                    parentId = message.parentId?.let { idMap[it] },
                    fromUser = message.fromUser,
                    text = message.text,
                    turn = message.turn,
                    createdAt = message.createdAt,
                )
                idMap[message.id] = newId
                inserted += 1
            }
            pending.removeAll(ready)
        }
        return inserted
    }

    /** 认不出来的操作类型退回最常见的那个，不让一条坏记录毁掉整次导入。 */
    private fun operationOf(name: String): ChangeOperation =
        ChangeOperation.entries.firstOrNull { it.name == name } ?: ChangeOperation.Set
}
