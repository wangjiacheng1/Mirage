package com.mirage.engine.state

/**
 * 状态栏里的一次变化，存下来的形态。
 *
 * 一条变化就是一行记录，只往后追加，**从不修改、从不删除**。
 *
 * @property sequence 落盘顺序。回放时按它从小到大加，顺序必须可复现。
 * @property messageId 这次变化挂在哪条消息上。切分支时靠它判断
 *   「这次变化算不算发生过」。
 * @property revision 这次变化之后状态栏是第几版。
 * @property before 改之前是多少。保留它才能回退一步，也才能在界面上写出
 *   「60 → 49」这种对照。
 * @property after 改之后实际落下的值（可能被代码修正过，不等于模型想改的值）。
 * @property adjustedBy 被代码修正过的话，记下是哪条规则动的手。
 */
data class StateEvent(
    val sequence: Long,
    val messageId: Long,
    val revision: Long,
    val path: String,
    val operation: ChangeOperation,
    val before: StateValue,
    val after: StateValue,
    val reason: String? = null,
    val adjustedBy: GuardFailureCode? = null,
)

/**
 * ============================================================================
 * StateEventLog —— 状态栏为什么存「变化记录」，而不是只存当前值
 * ============================================================================
 *
 * 最直觉的做法是建一张表，每项一行，改了就把那一行覆盖掉。
 * 这样状态栏当前显示什么最省事，但有三件事会立刻做不到：
 *
 * **一、切回更早一轮，数值回不去。**
 * 用户把第 5 轮重新生成，状态栏应该回到第 4 轮结束时的样子。
 * 只存当前值的话，第 5 轮改掉的那些数字已经把旧值覆盖了，没地方找回来。
 *
 * **二、分支之间会互相污染。**
 * 同一句话生成了两个版本，一个版本里挨了一刀、另一个没有。
 * 这两条线的数值必须各自独立，而覆盖式的一张表只有一份数值。
 *
 * **三、说不清数字是怎么变成这样的。**
 * 用户问「我的血怎么只剩一点了」，覆盖式存法只能回答「现在是 12」。
 *
 * 换成「只追加变化记录」之后，当前值 = 开局值 + 把这条线上的变化按顺序加一遍。
 * 上面三件事都成了同一个动作的自然结果：
 * 换一条线，就换一个「哪些变化算发生过」的集合，重新加一遍。
 *
 * ## 代价与应对
 *
 * 代价是每次要加一遍。长会话里变化记录会攒到几千条，每次翻页都重算不现实。
 * 应对是**另外存快照**：每隔若干轮把当时的完整数值记一份，
 * 回放时从最近的那份快照往后加。快照纯粹是为了快，丢了随时能重建，
 * 所以它可以被安全地删掉或重算 —— 这是它和变化记录的根本区别。
 */
object StateEventLog {

    /**
     * 把一次结算的结果转成要存下来的变化记录。
     *
     * @param startSequence 这一批记录的起始顺序号。调用方负责保证它在整局里递增。
     * @param revision 这次落盘之后状态栏是第几版。
     */
    fun eventsOf(
        receipt: SettlementReceipt,
        messageId: Long,
        startSequence: Long,
        revision: Long,
    ): List<StateEvent> = receipt.changes.mapIndexed { index, change ->
        StateEvent(
            sequence = startSequence + index,
            messageId = messageId,
            revision = revision,
            path = change.path,
            operation = change.operation,
            before = change.before,
            after = change.after,
            reason = change.reason,
            adjustedBy = change.adjustedBy,
        )
    }

    /**
     * 把变化记录加一遍，算出当前每项是多少。
     *
     * @param initial 开局值。来自角色卡，整局不变。
     * @param events 要算进来的变化记录。顺序不用调用方保证，这里会自己排。
     *
     * 这个函数是**纯算**的：同样的输入永远得到同样的结果，和当前时间、
     * 数据库里有什么都无关。所以它可以在没有数据库的环境里完整测试，
     * 而「状态值算得对不对」正是最需要被测透的一件事。
     */
    fun replay(
        initial: Map<String, StateValue>,
        events: List<StateEvent>,
    ): Map<String, StateValue> {
        val values = initial.toMutableMap()
        events.sortedBy { it.sequence }.forEach { event ->
            when (event.after) {
                // 「这一轮没提到这一项」不该被当成「把它清空」。
                // 真把这一项从表里去掉，状态栏上这一行会凭空不见，像数据丢了
                is StateValue.Absent -> Unit
                else -> values[event.path] = event.after
            }
        }
        return values
    }

    /**
     * 只算这条线上的变化。
     *
     * 这是「切分支」和「回到更早一轮」的实现：别的分支上的变化不在集合里，
     * 就等于没发生过 —— 不需要删任何记录，也不需要反向撤销。
     *
     * 反向撤销（把 after 换回 before 倒着减）是个很容易想到但错的做法：
     * 中间任何一步被代码修正过，倒着减就回不到原值了。
     * 从开局值重新加一遍则永远准确。
     */
    fun replayOnLine(
        initial: Map<String, StateValue>,
        events: List<StateEvent>,
        lineMessageIds: Set<Long>,
    ): Map<String, StateValue> = replay(
        initial = initial,
        events = events.filter { it.messageId in lineMessageIds },
    )

    /**
     * 从一份快照往后接着算。
     *
     * @param snapshot 某一版的完整数值。
     * @param snapshotRevision 那份快照是第几版。
     *
     * 只接比快照更晚的那些变化。快照本身已经包含了更早的全部变化，
     * 再加一遍虽然结果一样（存的是改完之后的值，不是增减量），
     * 但会白做很多功夫，长会话里就是实打实的卡顿。
     */
    fun replayFromSnapshot(
        snapshot: Map<String, StateValue>,
        snapshotRevision: Long,
        events: List<StateEvent>,
        lineMessageIds: Set<Long>,
    ): Map<String, StateValue> = replay(
        initial = snapshot,
        events = events.filter { it.revision > snapshotRevision && it.messageId in lineMessageIds },
    )

    /**
     * 这条线走到当前为止是第几版。
     *
     * 新的结算要拿它当「我是基于第几版算的」，结果回来时再比一次。
     * 没有任何变化时是 0，和刚开局一致。
     */
    fun revisionOf(events: List<StateEvent>, lineMessageIds: Set<Long>): Long =
        events.filter { it.messageId in lineMessageIds }.maxOfOrNull { it.revision } ?: 0L

    /**
     * 某一项是怎么一步步变成现在这样的。
     *
     * 给「这个数字怎么来的」那个界面用。按时间从早到晚。
     */
    fun historyOf(
        events: List<StateEvent>,
        path: String,
        lineMessageIds: Set<Long>,
    ): List<StateEvent> = events
        .filter { it.path == path && it.messageId in lineMessageIds }
        .sortedBy { it.sequence }
}
