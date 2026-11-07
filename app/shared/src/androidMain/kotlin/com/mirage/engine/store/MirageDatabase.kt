package com.mirage.engine.store

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 存档表：一行就是一局游戏。
 *
 * 角色卡整段原文存在 cardJson 这一列里，不拆成关联表。
 * 拆了之后每读一张卡要 join 好几张表，而我们从不做「某个字段在哪些卡里出现过」
 * 这种跨卡查询 —— 用户只会整卡导入、整卡导出、打开某一局继续玩。
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val cardJson: String?,
    val headMessageId: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * 消息表：一行就是一条消息。
 *
 * parentId 指向上一条。同一个 parentId 下面有几行，就是同一句话的几个并列版本。
 * 这一列上建了索引，因为「这条下面挂了哪些」是翻分支时最频繁的查询。
 */
@Entity(
    tableName = "messages",
    indices = [Index("sessionId"), Index("parentId")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val parentId: Long?,
    val fromUser: Boolean,
    val text: String,
    val turn: Int?,
    val createdAt: Long,
)

/**
 * 状态变化表：一行就是状态栏里某一项变了一次。
 *
 * 只追加，从不修改、从不删除。状态栏当前显示什么 = 开局值 + 这条线上的变化加一遍。
 * before/after 存的是「改之前/改之后是多少」，不是增减量 —— 存增减量的话
 * 多算一遍数值就翻倍，而且算错了无法从结果反推。
 */
@Entity(
    tableName = "state_events",
    indices = [Index("sessionId"), Index("messageId")],
)
data class StateEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    /** 这次变化挂在哪条消息上。切分支时靠它判断这次变化算不算发生过。 */
    val messageId: Long,
    val sequence: Long,
    val revision: Long,
    val path: String,
    val operation: String,
    val beforeJson: String,
    val afterJson: String,
    val reason: String?,
    val adjustedBy: String?,
)

/**
 * 存档列表的一行查询结果。
 *
 * 不复用 SessionEntity：那个带着整段卡原文，而列表用不上，
 * 读出来只是白白占内存。列名要和下面查询里的别名一字不差，否则 Room 会在编译时报错。
 */
data class SessionSummaryRow(
    val id: Long,
    val title: String,
    val updatedAt: Long,
    val messageCount: Int,
)

/**
 * 所有数据库操作。
 *
 * 刻意只建一个 Dao 而不是按表拆三个：现在的查询都很简单，拆开之后
 * 调用方要持有三个对象，而它们永远一起用。真到查询复杂了再拆。
 */
@Dao
interface MirageDao {

    @Insert
    suspend fun insertSession(session: SessionEntity): Long

    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latestSession(): SessionEntity?

    @Query("SELECT * FROM sessions WHERE id = :sessionId")
    suspend fun sessionById(sessionId: Long): SessionEntity?

    /**
     * 存档列表。
     *
     * 条数用子查询现算，不在 sessions 表里存一个计数列：
     * 存计数列意味着每加一条消息都要同步更新它，漏一处就会长期显示错误的数字，
     * 而且那种错一眼看不出来。现算慢一点，但永远是对的。
     */
    @Query(
        """
        SELECT s.id AS id, s.title AS title, s.updatedAt AS updatedAt,
               (SELECT COUNT(*) FROM messages m WHERE m.sessionId = s.id) AS messageCount
        FROM sessions s
        ORDER BY s.updatedAt DESC
        """,
    )
    suspend fun listSessions(): List<SessionSummaryRow>

    @Query("UPDATE sessions SET headMessageId = :headMessageId, updatedAt = :updatedAt WHERE id = :sessionId")
    suspend fun updateHead(sessionId: Long, headMessageId: Long, updatedAt: Long)

    @Insert
    suspend fun insertMessage(message: MessageEntity): Long

    @Query("UPDATE messages SET text = :text WHERE id = :messageId")
    suspend fun updateMessageText(messageId: Long, text: String)

    @Query("DELETE FROM messages WHERE id IN (:messageIds)")
    suspend fun deleteMessagesByIds(messageIds: List<Long>)

    @Query("DELETE FROM state_events WHERE messageId IN (:messageIds)")
    suspend fun deleteStateEventsByMessageIds(messageIds: List<Long>)

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY id ASC")
    suspend fun messagesOf(sessionId: Long): List<MessageEntity>

    @Insert
    suspend fun insertStateEvents(events: List<StateEventEntity>)

    @Query("SELECT * FROM state_events WHERE sessionId = :sessionId ORDER BY sequence ASC")
    suspend fun stateEventsOf(sessionId: Long): List<StateEventEntity>

    /**
     * 当前最大的顺序号。
     *
     * 返回 null 表示这一局还没有任何变化记录。顺序号必须由数据库来算 ——
     * 放在内存里记会在重启后从头开始，新记录就排到了旧记录前面，回放结果全乱。
     */
    @Query("SELECT MAX(sequence) FROM state_events WHERE sessionId = :sessionId")
    suspend fun maxEventSequence(sessionId: Long): Long?

    @Query("DELETE FROM sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: Long)

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteMessages(sessionId: Long)

    @Query("DELETE FROM state_events WHERE sessionId = :sessionId")
    suspend fun deleteStateEvents(sessionId: Long)
}

/**
 * Mirage 的本地数据库。
 *
 * 改表结构时必须同时做三件事：版本号加一、写一个迁移、把导出的表结构文件入库。
 * 漏掉迁移的话，用户升级后打开 App 会直接崩 —— 因为代码认的表和手机上的表不一样。
 */
@Database(
    entities = [SessionEntity::class, MessageEntity::class, StateEventEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class MirageDatabase : RoomDatabase() {
    abstract fun dao(): MirageDao
}

/** 打开（或第一次创建）本地数据库。 */
fun createMirageDatabase(context: Context): MirageDatabase = Room
    .databaseBuilder(context, MirageDatabase::class.java, "mirage.db")
    .build()
