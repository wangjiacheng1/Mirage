package com.mirage.engine.credential

/**
 * 访问凭据的存取口子。
 *
 * 凭据按接入点标识分开存放，而不是全局只存一份：用户可能同时配置多个接入点，
 * 共用一份会导致切换时互相覆盖。
 *
 * 所有方法都是挂起函数。即使某些平台的实现可以同步完成，接口也按异步定义 ——
 * 涉及密钥库和文件读写的实现一定会有耗时操作，接口若定成同步，
 * 后面要换实现就得改掉所有调用处。
 *
 * ## 实现方必须遵守的约定
 *
 * - **写入必须加密。** 这是整个接口存在的理由，不是可选项。
 * - **读出的明文不得缓存到任何可被导出的地方**（日志、崩溃报告、备份文件）。
 * - 取不到就返回 null，不要抛异常。没有配置过凭据是正常状态，不是错误。
 */
interface CredentialStore {

    /**
     * 读出某个接入点的凭据明文。
     *
     * @return 没有存过则为 null。
     */
    suspend fun read(providerId: String): String?

    /**
     * 存入某个接入点的凭据。
     *
     * 实现方需要自行处理「覆盖已有值」的情况。
     */
    suspend fun write(providerId: String, secret: String)

    /** 删掉某个接入点的凭据。不存在时不应报错。 */
    suspend fun delete(providerId: String)

    /**
     * 是否存过某个接入点的凭据。
     *
     * 单独提供这个方法而不是让调用方拿 [read] 判空，是为了让界面在
     * 「显示是否已配置」这种场景下**完全不必解密**。能不接触明文就不接触，
     * 每一次解密都是一次可能把明文留在内存或日志里的机会。
     */
    suspend fun contains(providerId: String): Boolean
}
