package com.mirage.engine.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 状态栏的一次「拍照」：当时每个数值是多少，加上当时是第几版。
 *
 * 为什么要把这两样打包在一起拍下来，而不是用的时候各读一次：
 * 结算是在后台跑的，发起时必须同时记住「我是按哪些数值算的」和「那是第几版」。
 * 分两次读就会出现错位 —— 读到的数值是第 7 版的，版本号却已经变成第 8 版，
 * 等结算结果回来做「这结果还算不算数」的判断时，就会得出完全相反的答案。
 */
data class StateSnapshot(
    val revision: Long,
    val values: Map<String, StateValue>,
)

/**
 * ============================================================================
 * StateStore —— 状态栏数值的唯一改动入口
 * ============================================================================
 *
 * 界面上状态栏显示的那些数值（生命、金币、位置……）就存在这里，
 * 而且**只能从这里改**。模型只能说「我想把生命改成 80」，
 * 改不改、改成多少由代码决定，决定完了写进这里，状态栏才会跟着变。
 *
 * ## 版本号是干什么的
 *
 * 数值每真正变一次，版本号加一。它用来判断「一个慢回来的结算结果还算不算数」：
 * 结算开始时记下当时是第几版，结果回来时再看一眼现在是第几版，
 * 对不上就说明中间数值已经被改过了，这份结果只能整份扔掉。
 *
 * 没有版本号会出什么事：一个慢半拍的结果把更晚发生的变化悄悄盖掉，
 * 而且事后光看数值完全看不出来发生过这件事。
 *
 * **这一轮没有数值变化时，版本号不动。** 「什么都没变」本身不是一次变化，
 * 给它涨版本会把正在后台排队的其它结算结果全部无谓地判成作废。
 *
 * ## 谁来调用
 *
 * 改数值的方法**没有加锁**，要求调用方一个一个来。结算队列本来就是排着队
 * 一次只跑一个，这个前提已经成立；再加一层锁，只会让「到底是谁在改数值」
 * 这件本该一眼看清的事变模糊。读数值（[snapshot]、[values]）没有这个要求。
 */
class StateStore(initial: Map<String, StateValue> = emptyMap()) {

    private val _values = MutableStateFlow(initial)

    /** 当前每个数值是多少。界面直接订阅它来刷状态栏。 */
    val values: StateFlow<Map<String, StateValue>> = _values.asStateFlow()

    private val _revision = MutableStateFlow(0L)

    /** 当前版本号。 */
    val revision: Long get() = _revision.value

    /** 给状态栏拍一张照：数值和版本号取的是同一时刻的。 */
    fun snapshot(): StateSnapshot = StateSnapshot(
        revision = _revision.value,
        values = _values.value,
    )

    /**
     * 把这一轮确定要改的数值写进状态栏。
     *
     * 参数只收 [StateChange]（代码检查过、已经确定要改成多少的那种），
     * 不收模型原始的那份「我想改成什么」。这是故意用类型卡住的：
     * 想跳过检查直接改数值，在这里连参数都传不进来。
     *
     * @return 写完之后是第几版。这一轮没有任何改动时，原样返回当前版本号。
     */
    fun commit(changes: List<StateChange>): Long {
        if (changes.isEmpty()) return _revision.value
        _values.value = _values.value.toMutableMap().apply {
            changes.forEach { change ->
                when (change.after) {
                    // 「这一轮没提到这个字段」不该被当成「把它清空」。
                    // 真把这一条从表里删掉，状态栏上这一行会凭空不见，
                    // 看起来就像数据丢了
                    is StateValue.Absent -> Unit
                    else -> put(change.path, change.after)
                }
            }
        }
        _revision.value = _revision.value + 1
        return _revision.value
    }

    /**
     * 整张状态栏换一套数值。开新局、换角色卡时用。
     *
     * 版本号同样会加一：整套数值都换了之后，后台还在跑的那些结算结果
     * 全都不再成立，让它们一起作废正是这里想要的效果。
     */
    fun reset(values: Map<String, StateValue>) {
        _values.value = values
        _revision.value = _revision.value + 1
    }
}
