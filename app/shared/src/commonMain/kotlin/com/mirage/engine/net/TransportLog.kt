package com.mirage.engine.net

/**
 * 传输层的诊断输出口子。
 *
 * 做成接口而不是直接调用某个平台的日志设施，是因为这段代码要在多个平台上跑，
 * 而且测试里需要把输出收集起来做断言。实现由各平台在装配时注入。
 *
 * 默认是关闭的：诊断输出会把完整的请求和响应内容写出去，内容量大，
 * 且在正式使用时没有价值。只有在排查问题时才应该打开。
 */
fun interface TransportLog {

    /**
     * 写一行诊断输出。
     *
     * 一次一行是刻意的。许多平台的日志设施对单条消息有长度上限，
     * 超长的内容会被悄悄截断甚至整条丢弃 —— 而被丢掉的往往正是要看的那部分。
     */
    fun write(line: String)

    companion object {
        /** 什么都不做。默认值。 */
        val Disabled: TransportLog = TransportLog { }
    }
}
