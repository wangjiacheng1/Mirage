package com.mirage.engine.llm

import com.mirage.engine.llm.sse.SseFrameReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 帧组装的测试。
 *
 * 之所以值得测到这个密度：它处理的全部是外部输入，而错误表现极其隐蔽 ——
 * 比如把心跳当成负载时，功能看起来完全正常，只有在服务端恰好发心跳时才偶发失败，
 * 线上几乎无法复现。
 *
 * 行的切分与字符解码不在这里测，那部分由网络层直接使用成熟实现完成，
 * 并在网络层以端到端的方式验证（包括多字节字符被切断的情形）。
 */
class SseParsingTest {

    // ------------------------------------------------------------------
    // 帧组装
    // ------------------------------------------------------------------

    @Test
    fun 空行之前的负载应组成一帧() {
        val reader = SseFrameReader()
        assertNull(reader.accept("data: hello"), "还没遇到空行，不该成帧")
        val frame = reader.accept("")
        assertNotNull(frame)
        assertEquals("hello", frame.data)
    }

    @Test
    fun 冒号开头的注释行应被完全忽略() {
        // 有的服务端用注释行做心跳，把它当负载会直接导致解析失败
        val reader = SseFrameReader()
        assertNull(reader.accept(": OPENROUTER PROCESSING"))
        assertNull(
            reader.accept(""),
            "注释行不算有效字段，所以后面的空行不该凭空产生一个空帧",
        )
    }

    @Test
    fun 心跳夹在真实负载之间不应影响成帧() {
        val reader = SseFrameReader()
        reader.accept(": keep-alive")
        reader.accept("data: real")
        reader.accept(": keep-alive")
        val frame = reader.accept("")
        assertNotNull(frame)
        assertEquals("real", frame.data)
    }

    @Test
    fun 一帧里的多行负载应用换行连接() {
        val reader = SseFrameReader()
        reader.accept("data: first")
        reader.accept("data: second")
        val frame = reader.accept("")
        assertNotNull(frame)
        assertEquals("first\nsecond", frame.data)
    }

    @Test
    fun 只去掉紧跟冒号的那一个空格() {
        val reader = SseFrameReader()
        reader.accept("data:  两个空格开头")
        val frame = reader.accept("")
        assertNotNull(frame)
        assertEquals(" 两个空格开头", frame.data, "第二个空格属于值本身")
    }

    @Test
    fun 没有冒号的行整行当字段名处理() {
        val reader = SseFrameReader()
        // 「data」单独成行是合法的，表示一行空负载
        reader.accept("data")
        val frame = reader.accept("")
        assertNotNull(frame)
        assertEquals("", frame.data)
    }

    @Test
    fun 事件名与序号应被取出() {
        val reader = SseFrameReader()
        reader.accept("event: ping")
        reader.accept("id: 42")
        reader.accept("data: x")
        val frame = reader.accept("")
        assertNotNull(frame)
        assertEquals("ping", frame.event)
        assertEquals("42", frame.id)
    }

    @Test
    fun 非法的重连间隔应被忽略而不是让解析失败() {
        val reader = SseFrameReader()
        reader.accept("retry: 稍后")
        assertNull(reader.accept(""), "唯一的字段非法，这一帧应当不存在")
    }

    @Test
    fun 连续空行不应产生空帧() {
        val reader = SseFrameReader()
        assertNull(reader.accept(""))
        assertNull(reader.accept(""))
    }

    @Test
    fun 未以空行收尾的最后一帧应能被补交() {
        // 收尾帧常常正是携带结束信息的那一帧，丢了最难排查
        val reader = SseFrameReader()
        reader.accept("data: last")
        val frame = reader.flushPendingFrame()
        assertNotNull(frame)
        assertEquals("last", frame.data)
        assertNull(reader.flushPendingFrame(), "补交过一次之后不该重复返回")
    }

    @Test
    fun 事件名不应延续到下一帧() {
        val reader = SseFrameReader()
        reader.accept("event: ping")
        reader.accept("data: a")
        reader.accept("")
        reader.accept("data: b")
        val second = reader.accept("")
        assertNotNull(second)
        assertNull(second.event, "事件名只属于声明它的那一帧")
    }
}
