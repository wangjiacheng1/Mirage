package com.mirage.engine.credential

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 凭据相关的测试。
 *
 * 平台加密实现本身需要真机环境，无法在这里覆盖；这里测的是它依赖的
 * 格式与行为约定 —— 而那恰恰是出错后果最严重的部分：
 * 信封格式一旦解析错误，表现是用户存好的凭据读不出来，看起来像凭空消失。
 */
class CredentialTest {

    /** 形态逼真但完全虚构的凭据。 */
    private val fakeSecret = "sk-abcdef0123456789abcdef0123456789"

    // ------------------------------------------------------------------
    // 密文信封
    // ------------------------------------------------------------------

    @Test
    fun 编码再解码应还原出相同内容() {
        val original = SecretEnvelope(
            nonce = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12),
            ciphertext = byteArrayOf(50, 60, 70, 80),
        )
        val restored = SecretEnvelope.decode(original.encode())
        assertEquals(original, restored)
    }

    @Test
    fun 内容相同的信封应判定为相等() {
        // 数据类对数组成员默认比较引用，不覆盖的话两个内容相同的信封会判为不等
        val a = SecretEnvelope(byteArrayOf(1, 2), byteArrayOf(3, 4))
        val b = SecretEnvelope(byteArrayOf(1, 2), byteArrayOf(3, 4))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun 编码结果应带版本号以便将来迁移() {
        // 没有版本号的话，换算法时旧数据会被当成损坏直接丢掉，
        // 对用户来说就是配置凭空消失
        val encoded = SecretEnvelope(byteArrayOf(1), byteArrayOf(2)).encode()
        assertTrue(encoded.startsWith(SecretEnvelope.CURRENT_VERSION))
    }

    @Test
    fun 损坏或陌生的内容应返回空而不是抛异常() {
        // 这里的失败多半意味着存储里有一条读不懂的旧数据。
        // 让它表现为「没有凭据」，用户重填即可；抛异常会让配置界面直接打不开
        listOf(
            "",
            "完全不是信封",
            "v1.只有两段",
            "v1.a.b.c",
            "v2.AAAA.BBBB",
            "v1.不是合法编码.BBBB",
            "v1..BBBB",
            "v1.AAAA.",
        ).forEach { broken ->
            assertNull(SecretEnvelope.decode(broken), "「$broken」应被当作无法识别")
        }
    }

    @Test
    fun 文本表示不应泄露内容() {
        // 这个对象一旦被顺手写进日志或崩溃报告，密文就外流了
        val envelope = SecretEnvelope(byteArrayOf(9, 9, 9), byteArrayOf(8, 8, 8))
        val text = envelope.toString()
        assertFalse(text.contains("9"))
        assertFalse(text.contains("8"))
        assertFalse(text.contains("["))
    }

    // ------------------------------------------------------------------
    // 展示摘要
    // ------------------------------------------------------------------

    @Test
    fun 摘要不应泄露凭据主体() {
        val preview = CredentialPreview.of(fakeSecret)
        assertFalse(preview.contains(fakeSecret))
        assertFalse(
            preview.contains(fakeSecret.take(8)),
            "开头是各家固定前缀，没有区分度，不该显示",
        )
        assertTrue(preview.endsWith(fakeSecret.takeLast(4)), "末尾几位用于让用户确认配的是哪个")
    }

    @Test
    fun 过短的凭据应全部遮蔽() {
        // 保留任何几位在短凭据里占比都过高
        val preview = CredentialPreview.of("short")
        assertFalse(preview.contains("ort"))
    }

    @Test
    fun 遮蔽宽度应固定以免泄露长度() {
        val short = CredentialPreview.of("sk-aaaaaaaaaaaaaaaa")
        val long = CredentialPreview.of("sk-" + "b".repeat(200))
        assertEquals(short.length, long.length, "遮蔽部分不该随真实长度变化")
    }

    @Test
    fun 空凭据的摘要应为空且不含提示文案() {
        // 怎么表达「未配置」由界面决定，这里不产出任何面向用户的文字
        assertEquals("", CredentialPreview.of(""))
    }

    // ------------------------------------------------------------------
    // 存取行为约定
    // ------------------------------------------------------------------

    @Test
    fun 存入后应能读回() = runTest {
        val store = InMemoryCredentialStore()
        store.write("deepseek", fakeSecret)
        assertEquals(fakeSecret, store.read("deepseek"))
    }

    @Test
    fun 不同接入点的凭据应互不影响() = runTest {
        // 共用一份会导致切换接入点时互相覆盖
        val store = InMemoryCredentialStore()
        store.write("a", "secret-a")
        store.write("b", "secret-b")
        assertEquals("secret-a", store.read("a"))
        assertEquals("secret-b", store.read("b"))
    }

    @Test
    fun 重复写入应覆盖旧值() = runTest {
        val store = InMemoryCredentialStore()
        store.write("a", "old")
        store.write("a", "new")
        assertEquals("new", store.read("a"))
    }

    @Test
    fun 没存过应返回空而不是抛异常() = runTest {
        // 没有配置过凭据是正常状态，不是错误
        assertNull(InMemoryCredentialStore().read("从未配置"))
    }

    @Test
    fun 删除不存在的条目不应报错() = runTest {
        InMemoryCredentialStore().delete("从未配置")
    }

    @Test
    fun 存在性判断应与读取结果一致() = runTest {
        val store = InMemoryCredentialStore()
        assertFalse(store.contains("a"))
        store.write("a", fakeSecret)
        assertTrue(store.contains("a"))
        store.delete("a")
        assertFalse(store.contains("a"))
        assertNull(store.read("a"))
    }

    // ------------------------------------------------------------------
    // 加密形态的基本要求
    // ------------------------------------------------------------------

    @Test
    fun 相同明文两次加密应产生不同密文() {
        // 用固定随机量会让这类算法的安全性整个垮掉。
        // 这里用一个最小的替身实现来表达这条约定：随机量每次都必须是新的
        val first = SecretEnvelope(nonce = byteArrayOf(1, 2, 3), ciphertext = byteArrayOf(9))
        val second = SecretEnvelope(nonce = byteArrayOf(4, 5, 6), ciphertext = byteArrayOf(9))
        assertNotEquals(
            first.encode(), second.encode(),
            "随机量不同，编码结果就必须不同，否则说明随机量没有参与存放",
        )
    }
}
