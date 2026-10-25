package com.mirage.android

import android.content.Context

/**
 * 记住使用者选过的接入点和模型名。
 *
 * 这些不是机密，所以用普通的键值存储即可，不必走加密通道 ——
 * 把非机密数据也塞进加密存储，只会让每次读写都多一次无谓的解密，
 * 还会模糊「什么才是真正需要保护的」这条界线。
 *
 * 之所以要持久化：密钥是存住了，但重启后接入点和模型名回到默认值，
 * 使用者得重新选一遍、重新输一遍模型标识。密钥还在、却不能直接用，
 * 这种「配了一半」的状态比完全没配更让人困惑。
 */
class SelectionStore(context: Context) {

    private val preferences = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** 上次选中的接入点标识。没有记录时返回 null。 */
    fun readProviderId(): String? = preferences.getString(KEY_PROVIDER, null)

    /**
     * 上次使用的模型标识。
     *
     * 按接入点分开记录：不同接入点的模型命名完全不同，
     * 共用一个会导致换了接入点之后带着一个必然无效的模型名去请求，
     * 而服务端的回应是「模型不存在」—— 指向完全错误的排查方向。
     */
    fun readModelId(providerId: String): String? =
        preferences.getString(KEY_MODEL_PREFIX + providerId, null)

    fun writeProviderId(providerId: String) {
        preferences.edit().putString(KEY_PROVIDER, providerId).apply()
    }

    fun writeModelId(providerId: String, modelId: String) {
        preferences.edit().putString(KEY_MODEL_PREFIX + providerId, modelId).apply()
    }

    private companion object {
        const val FILE_NAME = "mirage_selection"
        const val KEY_PROVIDER = "provider"
        const val KEY_MODEL_PREFIX = "model."
    }
}
