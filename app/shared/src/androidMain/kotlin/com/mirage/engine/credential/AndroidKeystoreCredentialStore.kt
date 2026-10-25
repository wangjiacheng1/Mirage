package com.mirage.engine.credential

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 用系统密钥库保护凭据的存放实现。
 *
 * ## 为什么不用现成的加密存储组件
 *
 * 官方那个加密存储组件当前仍是预览版本，且已被标记为不再推荐使用。
 * 直接基于系统密钥库自己做，代码量并不大，还少一个会过期的依赖。
 *
 * ## 工作方式
 *
 * 真正的密钥由系统密钥库生成并保管，**我们的代码从头到尾拿不到密钥材料本身**，
 * 只能请求系统用它做加解密。因此即使应用的数据文件被整个拷走，
 * 没有同一台设备的密钥库也解不开。
 *
 * 密文连同每次加密新生成的随机量一起，以字符串形式存在普通的键值存储里 ——
 * 那里只有密文，没有明文，也没有密钥。
 *
 * ## 🔴 一个必须正确处理的真实情况
 *
 * **密钥库里的密钥不参与应用数据备份。** 所以存在这样的场景：
 * 用户换了设备、从备份恢复，密文被恢复回来了，但密钥没有 ——
 * 此时解密必然失败。
 *
 * 这不是异常，是预期内的状态。处理方式是：把这条读不出来的记录删掉并当作
 * 「没有配置过」，让用户重新填一次即可恢复。如果任由异常抛出去，
 * 表现会是配置界面直接崩溃，而用户完全不知道该怎么办。
 *
 * 同理，应用清单里应当关闭数据备份，避免把注定解不开的密文带到新设备上。
 */
class AndroidKeystoreCredentialStore(
    context: Context,
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
    preferencesName: String = DEFAULT_PREFERENCES_NAME,
) : CredentialStore {

    /**
     * 只存密文的键值存储。
     *
     * 用应用级上下文，避免持有界面上下文造成泄漏 —— 这个对象的生命周期
     * 通常和整个应用一样长。
     */
    private val preferences: SharedPreferences = context.applicationContext
        .getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    override suspend fun read(providerId: String): String? = withContext(Dispatchers.IO) {
        val stored = preferences.getString(entryKey(providerId), null)
            ?: return@withContext null

        val envelope = SecretEnvelope.decode(stored)
        if (envelope == null) {
            // 格式不认识，多半是更早版本留下的数据。删掉并当作没配置过，
            // 留着它只会让每次读取都白跑一遍
            removeEntry(providerId)
            return@withContext null
        }

        runCatching { decrypt(envelope) }.getOrElse {
            // 解不开的唯一合理处置是丢弃。最常见的原因是密钥已不存在
            // （换设备、从备份恢复、或用户清掉了锁屏导致密钥失效），
            // 这些情况下这条密文永远不可能再被解开，留着没有任何意义
            removeEntry(providerId)
            null
        }
    }

    override suspend fun write(providerId: String, secret: String) {
        withContext(Dispatchers.IO) {
            val encoded = encrypt(secret).encode()
            // 用同步提交而不是异步提交：此刻已经在后台线程上，没有阻塞界面的风险，
            // 而异步提交在进程随后立刻被杀死时有丢失的可能 ——
            // 用户刚填完凭据就丢，是最让人困惑的一种故障
            preferences.edit().putString(entryKey(providerId), encoded).commit()
        }
    }

    override suspend fun delete(providerId: String) {
        withContext(Dispatchers.IO) { removeEntry(providerId) }
    }

    override suspend fun contains(providerId: String): Boolean = withContext(Dispatchers.IO) {
        // 刻意只看密文在不在，不做解密。判断「是否已配置」不需要接触明文，
        // 每一次多余的解密都是一次让明文落在内存里的机会
        preferences.contains(entryKey(providerId))
    }

    private fun removeEntry(providerId: String) {
        preferences.edit().remove(entryKey(providerId)).commit()
    }

    private fun entryKey(providerId: String) = ENTRY_KEY_PREFIX + providerId

    // ------------------------------------------------------------------
    // 加解密
    // ------------------------------------------------------------------

    private fun encrypt(plaintext: String): SecretEnvelope {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // 不传随机量，由系统生成。密钥的生成参数要求每次加密都用新的随机量，
        // 自己传反而会被拒绝 —— 这个限制是对的，重复使用随机量会让这个算法
        // 的安全性整个垮掉
        cipher.init(Cipher.ENCRYPT_MODE, obtainKey())
        return SecretEnvelope(
            nonce = cipher.iv,
            ciphertext = cipher.doFinal(plaintext.encodeToByteArray()),
        )
    }

    private fun decrypt(envelope: SecretEnvelope): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            obtainKey(),
            GCMParameterSpec(AUTH_TAG_BITS, envelope.nonce),
        )
        return cipher.doFinal(envelope.ciphertext).decodeToString()
    }

    /**
     * 取出密钥，没有就生成一个。
     *
     * 每次用时现取而不是缓存成字段：密钥可能在运行期间失效
     * （例如用户去掉了锁屏），缓存会让我们一直拿着一个已经不能用的引用。
     * 这个操作本身很轻。
     */
    private fun obtainKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                // 刻意不要求用户身份验证。要求了的话，每次发请求前都得弹指纹，
                // 对一个需要频繁调用的功能来说完全不可用
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        /** 默认的密钥别名。 */
        const val DEFAULT_KEY_ALIAS: String = "mirage.credential.v1"

        /** 默认的密文存放文件名。 */
        const val DEFAULT_PREFERENCES_NAME: String = "mirage_credentials"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BITS = 256

        /** 校验标签长度。固定 128 位，和加密时的默认值一致，否则解密会失败。 */
        private const val AUTH_TAG_BITS = 128

        private const val ENTRY_KEY_PREFIX = "credential."
    }
}
