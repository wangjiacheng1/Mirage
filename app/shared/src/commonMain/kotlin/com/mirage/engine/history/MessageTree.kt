package com.mirage.engine.history

/**
 * 存下来的一条消息。
 *
 * @property parentId 上一条是谁。为 null 表示这是这一局的第一条。
 *   **整套设计的关键就在这一个字段上** —— 见 [MessageTree] 的说明。
 * @property turn 这条回复算第几轮。只有模型那一侧有值，用来和结算记录对上。
 * @property createdAt 存下来的时间点。同一个父下面有多条候选时，用它排先后。
 */
data class StoredMessage(
    val id: Long,
    val parentId: Long?,
    val fromUser: Boolean,
    val text: String,
    val turn: Int? = null,
    val createdAt: Long = 0L,
)

/**
 * ============================================================================
 * MessageTree —— 对话记录为什么存成树，而不是一条列表
 * ============================================================================
 *
 * 一眼看上去，聊天记录就是一串消息，用列表存最省事。但有两个常用操作，
 * 列表结构根本做不出来：
 *
 * **一、重新生成（同一句话要几个不同版本）。**
 * 对同一条提问，模型可以给出好几个回复，用户挑一个用。
 * 这几个回复是**并列**的，它们共用同一个「上一条」。
 * 列表里一个位置只能放一个，所以要么覆盖掉旧的（挑完就回不去了），
 * 要么把它们前后排成一串（那就变成「模型连说了三遍」，意思全变了）。
 *
 * **二、改掉更早的一句话，后面重新来。**
 * 用户把第 3 轮自己说的话改一下，第 4 轮往后就应该从那里重新长出来，
 * 而原来的第 4 轮往后要能留着。这在列表里只能靠删除，删了就没了。
 *
 * 换成树就很自然：每条消息记住自己的上一条是谁。同一个上一条下面挂了几条，
 * 它们就是几个并列的候选；从某条往下重新长，就是在那条下面挂一个新分支。
 *
 * 「现在正在聊的是哪一条线」由一个单独记下来的末端决定：
 * 从末端那条一路往上找上一条，找到头，就是当前这条线的全部内容。
 *
 * 🔴 这是整个项目唯一一处「现在不做对，以后改不动」的地方。
 * 列表换树意味着存储、界面、状态回放三处同时重写，所以从第一版就按树存。
 */
class MessageTree(messages: List<StoredMessage>) {

    private val byId: Map<Long, StoredMessage> = messages.associateBy { it.id }

    /**
     * 每条消息下面挂着哪些。
     *
     * 预先建好这张表，而不是每次去全量过滤：界面上翻分支、数状态值都要频繁问
     * 「这条下面有几个候选」，每次扫一遍整局记录在长会话里会明显卡顿。
     */
    private val childrenByParent: Map<Long?, List<StoredMessage>> = messages
        .groupBy { it.parentId }
        .mapValues { (_, list) -> list.sortedWith(compareBy({ it.createdAt }, { it.id })) }

    fun find(id: Long): StoredMessage? = byId[id]

    /** 这一局的第一条（可能有多条，比如换过开场消息）。 */
    fun roots(): List<StoredMessage> = childrenByParent[null] ?: emptyList()

    /** 某条下面挂着的候选。 */
    fun childrenOf(id: Long?): List<StoredMessage> = childrenByParent[id] ?: emptyList()

    /**
     * 和某条并列的那些候选（含它自己）。
     *
     * 界面上的「上一个 / 下一个版本」就是在这个列表里左右移动。
     */
    fun alternativesOf(id: Long): List<StoredMessage> {
        val self = byId[id] ?: return emptyList()
        return childrenOf(self.parentId)
    }

    /**
     * 从开头到某条的完整这条线。
     *
     * 顺序是「从旧到新」，可以直接拿去拼给模型的历史。
     *
     * 遇到环就停下来，不抛异常：数据万一被写坏（比如某条的上一条指向了它自己的
     * 后代），这里抛异常会让整局记录打不开，而返回已经走通的那一段
     * 至少还能把内容显示出来
     */
    fun lineageOf(id: Long?): List<StoredMessage> {
        if (id == null) return emptyList()
        val reversed = mutableListOf<StoredMessage>()
        val seen = mutableSetOf<Long>()
        var cursor = byId[id]
        while (cursor != null && seen.add(cursor.id)) {
            reversed += cursor
            cursor = cursor.parentId?.let { byId[it] }
        }
        return reversed.reversed()
    }

    /**
     * 这条线上每一条的编号集合。
     *
     * 状态值回放要用它：只累加挂在这条线上的那些变化，别的分支上的变化
     * 就当没发生过。这正是「切回更早一轮，数值也跟着回到当时」的实现方式。
     */
    fun lineageIdsOf(id: Long?): Set<Long> = lineageOf(id).map { it.id }.toSet()

    /**
     * 某条以及挂在它下面的所有消息。
     *
     * 删除时用：删掉一条，它后面长出来的所有内容都必须跟着删 ——
     * 那些内容是接着这条往下写的，留着就成了没有上文的碎片。
     *
     * 返回的集合包含它自己，这样调用方不用再单独加一次（最容易漏的就是这个）。
     */
    fun descendantsOf(id: Long): Set<Long> {
        val result = mutableSetOf<Long>()
        val pending = ArrayDeque<Long>()
        pending.addLast(id)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            // add 返回 false 说明已经走过了，直接跳过 —— 数据万一成环也不会死循环
            if (!result.add(current)) continue
            childrenOf(current).forEach { pending.addLast(it.id) }
        }
        return result
    }

    /**
     * 从某条一路往下，每一步都走最新的那个候选，直到没有下一条。
     *
     * 用在「打开一局继续玩」：只记着开头的话，要知道上次玩到哪，
     * 就沿着每一步最新的那个候选走到尽头。
     */
    fun latestDescendantOf(id: Long): StoredMessage? {
        var cursor = byId[id] ?: return null
        val seen = mutableSetOf(cursor.id)
        while (true) {
            val next = childrenOf(cursor.id).lastOrNull { seen.add(it.id) } ?: return cursor
            cursor = next
        }
    }
}
