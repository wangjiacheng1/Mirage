package com.mirage.engine.net

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow

/**
 * 按预设脚本回放事件的传输替身。
 *
 * 用它替代真实网络的好处是能精确构造真实环境里很难复现的时序 ——
 * 比如「最后一帧没有空行收尾」「刚连上就返回错误」「读到一半超时」。
 * 这些恰恰是最容易出错、而真机调试几乎碰不到的分支。
 *
 * 放在共用位置而不是各测试里各写一份：依赖传输层的测试会越来越多，
 * 各写一份的结果是脚本构造方式逐渐分叉，同一种时序在不同测试里
 * 表达得不一样，失败时难以判断是被测对象的问题还是替身的问题。
 */
class ScriptedHttpStreamTransport(
    private val script: List<HttpStreamEvent>,
) : HttpStreamTransport {

    /** 最近一次收到的请求，便于断言「发出去的内容对不对」。 */
    var received: HttpStreamRequest? = null
        private set

    override fun openLineStream(request: HttpStreamRequest): Flow<HttpStreamEvent> {
        received = request
        return script.asFlow()
    }

    companion object {
        /**
         * 用若干行内容构造脚本。
         *
         * 记得按协议要求在每帧负载后补一个空行 —— 空行才是一帧的结束标志，
         * 漏掉它内容就永远攒不成一帧。
         */
        fun lines(vararg text: String): List<HttpStreamEvent> =
            text.map { HttpStreamEvent.Line(it) }

        /** 构造一段完整的、以结束标记收尾的数据流。 */
        fun completeStream(vararg payloads: String): List<HttpStreamEvent> = buildList {
            payloads.forEach {
                add(HttpStreamEvent.Line("data: $it"))
                add(HttpStreamEvent.Line(""))
            }
            add(HttpStreamEvent.Line("data: [DONE]"))
            add(HttpStreamEvent.Line(""))
        }
    }
}
