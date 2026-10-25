package com.mirage.engine.net

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 一次流式 POST 的全部输入。
 *
 * 这一层刻意对「要发什么业务内容」一无所知：请求体已经是拼好的字符串，
 * 请求头也已经是拼好的键值对。好处是传输层可以被完全替换或伪造，
 * 上层逻辑的测试不需要牵扯任何网络实现。
 */
data class HttpStreamRequest(
    /** 完整地址。 */
    val url: String,

    /**
     * 请求体，已经序列化好的文本。
     *
     * 传字符串而不是对象，是为了让「发出去的到底是什么」完全确定且可打印。
     * 交给传输层再序列化的话，序列化配置的任何变化都会悄悄改变实际请求内容。
     */
    val body: String,

    /**
     * 附加请求头。
     *
     * 注意凭据是通过这里传入的，因此**这个对象整体都不应被打印到日志里**。
     */
    val headers: Map<String, String> = emptyMap(),

    /** 请求体的内容类型。 */
    val contentType: String = "application/json",

    /** 期望的响应类型。 */
    val accept: String = "text/event-stream",

    /**
     * 允许多久收不到任何新内容。
     *
     * 判定的是**距上一次收到内容的间隔**，不是整次调用的总时长。
     * 这个区分很关键：一次生成本身就可能持续很久，开了思考的模型
     * 停顿一两分钟是正常的，按总时长判超时会把正常的长回复直接误杀。
     *
     * 同时这个间隔也覆盖「第一次收到内容之前」的等待 ——
     * 实测首次响应可能十几秒内毫无动静，连心跳都没有，所以不能设得太短。
     */
    val idleTimeout: Duration = 60.seconds,
)
