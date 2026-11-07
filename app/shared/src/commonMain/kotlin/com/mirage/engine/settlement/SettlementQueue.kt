package com.mirage.engine.settlement

import com.mirage.engine.state.SettlementReceipt
import com.mirage.engine.state.SettlementStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 一条结算记录在流程里走到哪一步了。 */
enum class SettlementPhase {
    /** 已提交，前面还有别的任务在跑。 */
    Waiting,

    /** 正在跑。 */
    Running,

    /** 已出结果（成功、失败、过期、被中断都算）。 */
    Settled,
}

/**
 * 界面渲染一张结算卡片所需的全部信息。
 * 进度档案：工作材料、执行阶段、结果、第几次尝试
 * 带着原始任务一起保存，是为了重试时不必让界面自己把上下文再拼一遍 ——
 * 重试要用的叙事内容就在任务里。
 *
 * @property attempt 第几次尝试，从 1 开始。重试会让它加一，
 *   既用于界面提示，也用于识别过期的队列消息。
 */
data class SettlementRecord(
    //给AI的工作材料
    val task: SettlementTask,
    //执行阶段
    val phase: SettlementPhase,
    //结果
    val outcome: SettlementOutcome,
    //尝试次数
    val attempt: Int,
) {
    val turn: Int get() = task.turn
    val status: SettlementStatus get() = outcome.receipt.status

    /** 是否可以重新来一次。 */
    val retryable: Boolean
        get() = phase == SettlementPhase.Settled && status in RETRYABLE

    private companion object {
        val RETRYABLE = setOf(
            SettlementStatus.Error,
            SettlementStatus.Stale,
            SettlementStatus.Interrupted,
        )
    }
}

/**
 * ============================================================================
 * SettlementQueue —— 结算队列
 * ============================================================================
 * 让结算一次只跑一个
 *
 * ## 为什么必须排队
 *
 * 结算是后台进行的，而使用者完全可以在它还没回来的时候就继续往下走。
 * 于是同一时刻会出现多个结算请求，它们都想改同一份状态。
 *
 * 实测中见过的处理方式是：发现已有任务在跑，就**直接丢弃新来的那一轮**。
 * 这样做数值不会乱，但那一轮的变化就永远不会发生了，而使用者看不出来 ——
 * 剧情里明明发生了事情，面板上却什么都没动。
 *
 * 这里改成排队：一次只跑一个，后来的等着。代价是延迟变长（前面那个跑完才轮到），
 * 换来的是**没有任何一轮会被静默跳过**。结算本来就不在使用者的等待路径上，
 * 多等几秒不影响继续往下玩，而丢一轮数值是实打实的损失。
 *
 * ## 顺序之外，还要防「旧结果盖新状态」
 *
 * 排队保证了不会同时写，但不保证写的时候前提还成立：
 * 前一个任务落盘后版本号变了，后一个任务是基于旧版本算的，它的结论已经失效。
 * 所以每个结果在落盘前都要再比一次版本号，不一致就整体作废。
 * 这两道机制缺一不可 —— 排队管的是「不要同时写」，版本号管的是「不要写错依据」。
 *
 * ## 为什么用四个回调注入，而不直接持有各个组件
 *
 * 队列只负责调度，不该知道状态存在哪、凭据怎么取、模型怎么调。
 * 保持这个边界之后，它可以在没有任何网络和存储的环境里被完整测试：
 * 用一个可控的挂起函数当作「模型调用」，就能精确构造出
 * 并发、过期、被中断这些真实环境里很难复现的时序。
 *
 * @param scope 队列的生命周期跟着它。作用域结束，工作协程一并结束。
 * @param revisionOf 读取当前状态版本号。
 * @param commit 落盘。只有通过了版本号比对的结果才会走到这里。
 * @param runner 真正去跑一次结算。约定：取消时必须抛出取消异常，不要吞掉。
 */
class SettlementQueue(
    private val scope: CoroutineScope,
    private val revisionOf: () -> Long,
    private val commit: (SettlementReceipt) -> Unit,
    private val runner: suspend (SettlementTask) -> SettlementOutcome,
) {

    private val _records = MutableStateFlow<List<SettlementRecord>>(emptyList())

    /** 全部结算记录，按提交顺序。界面直接订阅渲染。 */
    val records: StateFlow<List<SettlementRecord>> = _records.asStateFlow()

    /**
     * 待办队列。
     *
     * 容量不设限：提交结算绝不能因为队列满了而阻塞到对话流程上，
     * 那会让使用者的输入卡住，而他根本不知道是在等一件后台的事。
     */
    private val inbox = Channel<QueueItem>(Channel.UNLIMITED)

    /** 正在跑的那一个。单独留着句柄，才能只停这一个而不是整条队列。 */
    private var activeJob: Job? = null

    init {
        scope.launch {
            for (item in inbox) {
                // 这条消息可能已经被更晚的重试取代了。认 attempt 而不认轮次：
                // 同一轮可以被重试多次，只有最后一次的那条消息该被执行
                if (recordOf(item.task.turn)?.attempt != item.attempt) continue
                runOne(item)
            }
        }
    }

    /** 查某一轮的记录。 */
    fun recordOf(turn: Int): SettlementRecord? = _records.value.firstOrNull { it.turn == turn }

    /**
     * 提交一次结算。
     *
     * 同一轮重复提交等于重试：记录被原地替换成新的一次尝试，位置不变。
     * 位置不变很重要 —— 卡片在界面上跳走会让人以为是另一轮的结果。
     *
     * 重试需要调用方**重新构造任务**（带上当时的状态快照和版本号），
     * 而不是复用旧任务：旧任务里的快照已经过时，拿它去算必然又得到一个过期结果。
     */
    fun submit(task: SettlementTask) {
        val attempt = (recordOf(task.turn)?.attempt ?: 0) + 1
        val record = SettlementRecord(
            task = task,
            phase = SettlementPhase.Waiting,
            outcome = pending(task),
            attempt = attempt,
        )
        upsert(record)
        inbox.trySend(QueueItem(task, attempt))
    }

    /**
     * 停掉正在跑的那一个，队列继续往下走。
     *
     * 被停掉的那一轮会落到「被中断」而不是消失：花掉的开销是真实发生的，
     * 让它从界面上无声消失等于隐瞒了一次计费。
     */
    fun cancelActive() {
        activeJob?.cancel()
    }

    /**
     * 清空全部结算记录。
     *
     * 切换存档时用：上一局的回执不该挂在下一局的消息下面。
     * 同时把正在跑的那个停掉 —— 它算的是上一局的数值，结果回来也没有用，
     * 而且版本号比对会判它过期，白等一场。
     */
    fun clear() {
        cancelActive()
        _records.value = emptyList()
    }

    /** 不再接受新任务。已在队列里的不会被执行。 */
    fun close() {
        inbox.close()
    }

    private suspend fun runOne(item: QueueItem) {
        updatePhase(item, SettlementPhase.Running)
        // 放到子协程里跑，才能在不杀掉整条队列的前提下单独停掉这一个。
        // 直接在工作协程里调用的话，一取消整个队列就停了
        val job = scope.launch { execute(item) }
        activeJob = job
        job.join()
        activeJob = null
    }

    private suspend fun execute(item: QueueItem) {
        val task = item.task
        val outcome = try {
            runner(task)
        } catch (cancellation: CancellationException) {
            // 状态更新不挂起，所以在已取消的协程里依然能完成。
            // 取消异常继续往上抛，否则调用方无法区分「跑完了」和「被打断了」
            settle(item, pending(task).markInterrupted())
            throw cancellation
        } catch (failure: Throwable) {
            // 兜底。runner 约定内部已经把可预期的失败转成了回执，
            // 走到这里说明是实现本身出了问题 —— 不能让它把整条队列带走，
            // 那会导致后面每一轮都悄悄不结算了
            settle(item, failed(task, failure))
            return
        }

        // 落盘前最后一道检查：这份结果所基于的版本还是当前版本吗
        val resolved = if (outcome.basedOnRevision != revisionOf()) {
            outcome.markStale()
        } else {
            commit(outcome.receipt)
            outcome
        }
        settle(item, resolved)
    }

    private fun settle(item: QueueItem, outcome: SettlementOutcome) {
        replaceIfCurrent(item) { it.copy(phase = SettlementPhase.Settled, outcome = outcome) }
    }

    private fun updatePhase(item: QueueItem, phase: SettlementPhase) {
        replaceIfCurrent(item) { it.copy(phase = phase) }
    }

    /**
     * 只在这条记录仍属于当前尝试时才更新。
     *
     * 防的是这种时序：一个任务跑得很慢，期间使用者点了重试，
     * 然后慢任务终于回来了 —— 它的结果必须被丢掉，不能覆盖掉新尝试的状态，
     * 否则界面会从「正在重算」倒退回一个更早的失败结果。
     */
    private fun replaceIfCurrent(
        item: QueueItem,
        transform: (SettlementRecord) -> SettlementRecord,
    ) {
        _records.value = _records.value.map { record ->
            if (record.turn == item.task.turn && record.attempt == item.attempt) {
                transform(record)
            } else {
                record
            }
        }
    }

    private fun upsert(record: SettlementRecord) {
        val existing = _records.value.indexOfFirst { it.turn == record.turn }
        _records.value = if (existing >= 0) {
            _records.value.toMutableList().apply { set(existing, record) }
        } else {
            _records.value + record
        }
    }

    private fun pending(task: SettlementTask) = SettlementOutcome(
        turn = task.turn,
        basedOnRevision = task.basedOnRevision,
        receipt = SettlementReceipt(turn = task.turn, status = SettlementStatus.Pending),
    )

    private fun failed(task: SettlementTask, cause: Throwable) = SettlementOutcome(
        turn = task.turn,
        basedOnRevision = task.basedOnRevision,
        receipt = SettlementReceipt(turn = task.turn, status = SettlementStatus.Error),
        diagnostics = SettlementDiagnostics(
            // 异常信息只进排查字段，不进回执 —— 它是实现细节，
            // 对使用者没有意义，界面按状态分类取文案就够了
            rawOutput = cause.message?.take(MAX_CAUSE_PREVIEW),
        ),
    )

    /**
     * 队列里排队的任务。
     * 可以理解为信封：工作材料 + 尝试次数
     */
    private data class QueueItem(val task: SettlementTask, val attempt: Int)

    private companion object {
        const val MAX_CAUSE_PREVIEW = 300
    }
}
