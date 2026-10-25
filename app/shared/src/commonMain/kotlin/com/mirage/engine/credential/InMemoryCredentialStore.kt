package com.mirage.engine.credential

/**
 * 只存在内存里的凭据存放实现。
 *
 * 两个用途：
 * - 测试。它让所有依赖凭据的逻辑都能在没有平台设施的环境里跑。
 * - 「本次使用，不保存」这种模式。用户不愿意把凭据留在设备上时，
 *   进程结束即消失正好满足需求。
 *
 * ⚠️ **不要把它当成正式的存放方式。** 它不加密，进程内任何能拿到这个对象的
 * 代码都能读到明文。
 */
class InMemoryCredentialStore : CredentialStore {

    private val entries = mutableMapOf<String, String>()

    override suspend fun read(providerId: String): String? = entries[providerId]

    override suspend fun write(providerId: String, secret: String) {
        entries[providerId] = secret
    }

    override suspend fun delete(providerId: String) {
        entries.remove(providerId)
    }

    override suspend fun contains(providerId: String): Boolean = entries.containsKey(providerId)

    /** 清空。测试在用例之间复位时用。 */
    fun clear() = entries.clear()
}
