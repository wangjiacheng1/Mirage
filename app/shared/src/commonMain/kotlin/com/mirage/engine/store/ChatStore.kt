package com.mirage.engine.store

import com.mirage.engine.history.StoredMessage
import com.mirage.engine.state.StateEvent

/**
 * 一局游戏的存档。
 *
 * 只存三样东西：用的哪张卡（整段原文）、聊到哪一条了、什么时候玩的。
 *
 * **刻意不存状态栏的字段清单和开局数值。**
 * 这两样都是从卡原文里机械推导出来的，推导过程是纯算、结果固定，
 * 加载时重新算一遍就行。存一份反而多一个会和卡不一致的地方 ——
 * 比如以后改进了推导逻辑，存下来的旧清单就成了错的。
 *
 * @property cardJson 角色卡原文。为 null 表示这一局没导卡（用内置占位数值跑）。
 * @property headMessageId 这一局聊到哪一条了。从它往上回溯就是当前这条线。
 */
data class SessionRecord(
    val id: Long,
    val title: String,
    val cardJson: String?,
    val headMessageId: Long?,
    val updatedAt: Long,
)

/**
 * 存档列表里的一条。
 *
 * 刻意不带卡原文：一张卡动辄几十 KB，而列表只需要显示名字、时间和条数。
 * 列表里把每张卡都读出来，存档一多就是几百 KB 的无谓读取。
 */
data class SessionSummary(
    val id: Long,
    val title: String,
    val updatedAt: Long,
    val messageCount: Int,
)

/**
 * 存档的读写入口。
 *
 * 所有碰数据库的操作都从这里走。界面和引擎都只认这个接口，
 * 不知道底下是 Room 还是别的东西 —— 将来换实现（比如给 iOS 写一份）
 * 上面两层一行都不用改。
 *
 * 方法都是挂起函数：数据库读写不能在主线程做，挂起函数让调用方
 * 没法「忘记切线程」—— 它必须在协程里调用。
 */
interface ChatStore {

    /** 新建一局，返回它的编号。 */
    suspend fun createSession(title: String, cardJson: String?, createdAt: Long): Long

    /** 读最近玩过的那一局。没有任何存档时返回 null。 */
    suspend fun loadLatestSession(): SessionRecord?

    /** 读某一局。切换存档时用。 */
    suspend fun loadSession(sessionId: Long): SessionRecord?

    /** 列出全部存档，最近玩过的排在前面。 */
    suspend fun listSessions(): List<SessionSummary>

    /** 更新最新条的messageId，记下这一局聊到哪一条了。 */
    suspend fun updateHead(sessionId: Long, headMessageId: Long, updatedAt: Long)

    /**
     * 追加一条消息，返回它的messageId。
     *
     * @param parentId 上一条是谁。为 null 表示这是这一局的第一条。
     */
    suspend fun appendMessage(
        sessionId: Long,
        parentId: Long?,
        fromUser: Boolean,
        text: String,
        turn: Int?,
        createdAt: Long,
    ): Long

    /**
     * 改掉一条消息的文字。
     *
     * 原地改，不另存一条。编辑的用意是「这句话本来就该这么说」，
     * 不是「换个说法试试」—— 后者是重新生成要做的事，那才需要留下两个版本。
     */
    suspend fun updateMessageText(messageId: Long, text: String)

    /**
     * 删掉若干条消息，连同挂在它们身上的状态变化记录。
     *
     * 变化记录必须一起删。留着的话它们会成为没有主人的记录 ——
     * 现在不影响回放（回放只认当前这条线上的消息），但以后任何
     * 「统计一共改过多少次数值」之类的功能都会把它们算进去。
     */
    suspend fun deleteMessages(messageIds: List<Long>)

    /** 读这一局的所有消息（含所有分支，不只是当前这条线）。 */
    suspend fun messagesOf(sessionId: Long): List<StoredMessage>

    /** 追加状态栏的变化记录。只追加，不修改也不删除。 */
    suspend fun appendStateEvents(sessionId: Long, events: List<StateEvent>)

    /** 读这一局的所有变化记录。 */
    suspend fun stateEventsOf(sessionId: Long): List<StateEvent>

    /**
     * 下一条变化记录该用的顺序号。
     *
     * 顺序号要在一局内连续递增，而「当前最大值 + 1」只能由数据库来算 ——
     * 放在内存里记会在重启后从头开始，于是新记录排到了旧记录前面，回放结果就乱了。
     */
    suspend fun nextEventSequence(sessionId: Long): Long

    /** 删掉这一局的全部数据。 */
    suspend fun deleteSession(sessionId: Long)
}
