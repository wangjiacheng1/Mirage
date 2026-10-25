package com.mirage.engine.net

import kotlinx.coroutines.flow.Flow

/**
 * 流式 POST 的能力抽象。
 *
 * 之所以要有这个接口而不是直接用具体实现：上层编排逻辑（拼请求、拆帧、解码、
 * 错误归类）的正确性与网络完全无关，但如果它直接依赖网络实现，
 * 测它就必须搭一套 HTTP 模拟环境。有了这个接口，上层可以用一个几行的
 * 假实现来测，测试既快又能精确构造各种罕见时序。
 *
 * 实现方需要遵守两条约定：
 * - 事件流正常结束代表读完；失败通过事件汇报，不要抛异常。
 * - 协程被取消时要让取消正常向上传播，不要转成失败事件 ——
 *   主动取消和出错是两件事，混淆会导致界面把用户自己的操作显示成报错。
 */
interface HttpStreamTransport {
    fun openLineStream(request: HttpStreamRequest): Flow<HttpStreamEvent>
}
