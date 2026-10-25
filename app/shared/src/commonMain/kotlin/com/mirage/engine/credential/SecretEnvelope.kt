package com.mirage.engine.credential

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * 密文的存放格式。
 *
 * 加密结果不是一段裸密文，至少还包含一个每次加密都必须不同的随机量。
 * 把它们拼成一个带版本号的字符串统一存放，好处有两个：
 *
 * - 存储层只需要会存字符串，不必关心加密细节；
 * - 带了版本号，将来换算法或换参数时能认出旧数据并做迁移，
 *   而不是把旧数据当成损坏直接丢掉 —— 那意味着用户的配置凭空消失。
 *
 * 格式：`v1.<随机量>.<密文>`，两段都用 Base64 编码。
 * 选点号而不是冒号作分隔符，是因为 Base64 的字符集里不含点号，
 * 不会和内容本身混淆。
 */
@OptIn(ExperimentalEncodingApi::class)
data class SecretEnvelope(
    /** 加密用的随机量。每次加密必须重新生成。 */
    val nonce: ByteArray,

    /** 密文本体。 */
    val ciphertext: ByteArray,
) {
    /** 编码成可直接存放的字符串。 */
    fun encode(): String = listOf(
        CURRENT_VERSION,
        Base64.encode(nonce),
        Base64.encode(ciphertext),
    ).joinToString(SEPARATOR)

    // 数据类对数组成员生成的相等判断比较的是引用，这里改成比较内容，
    // 否则两个内容相同的信封会被判为不等，测试和去重逻辑都会出错。
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SecretEnvelope) return false
        return nonce.contentEquals(other.nonce) && ciphertext.contentEquals(other.ciphertext)
    }

    override fun hashCode(): Int = 31 * nonce.contentHashCode() + ciphertext.contentHashCode()

    /**
     * 刻意不输出任何内容。
     *
     * 数据类默认生成的文本表示会把成员值打印出来。一旦这个对象被顺手写进日志
     * 或崩溃报告，密文就外流了。密文本身虽然不等于明文，但没有任何理由让它外流。
     */
    override fun toString(): String = "SecretEnvelope(已隐藏)"

    companion object {
        /** 当前格式版本。 */
        const val CURRENT_VERSION: String = "v1"

        private const val SEPARATOR = "."

        /** 一个信封应当有的段数：版本、随机量、密文。 */
        private const val EXPECTED_PARTS = 3

        /**
         * 从存放的字符串还原。
         *
         * @return 格式不认识或内容损坏时返回 null。
         *
         * 返回 null 而不是抛异常，是因为这里的失败多半意味着
         * 「存储里有一条我们读不懂的旧数据」。让它表现为「没有凭据」，
         * 用户重新填一次就能恢复；抛异常则会让整个配置界面打不开。
         */
        fun decode(encoded: String): SecretEnvelope? {
            val parts = encoded.split(SEPARATOR)
            if (parts.size != EXPECTED_PARTS) return null
            if (parts[0] != CURRENT_VERSION) return null

            val nonce = runCatching { Base64.decode(parts[1]) }.getOrNull() ?: return null
            val ciphertext = runCatching { Base64.decode(parts[2]) }.getOrNull() ?: return null
            if (nonce.isEmpty() || ciphertext.isEmpty()) return null

            return SecretEnvelope(nonce, ciphertext)
        }
    }
}
