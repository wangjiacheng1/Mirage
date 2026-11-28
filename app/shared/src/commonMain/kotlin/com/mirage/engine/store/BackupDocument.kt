package com.mirage.engine.store

import kotlinx.serialization.Serializable

/**
 * 一份完整备份的内容。
 *
 * 🔴 **这里面绝不包含访问密钥。** 备份文件会被导出到手机存储、发到电脑、
 * 传进网盘，等于完全离开我们的掌控。密钥留在系统密钥库里，换机重填一次即可，
 * 而密钥一旦随文件泄露，用户是直接损失钱。
 *
 * @property formatVersion 文件格式版本。以后格式变了，靠它判断能不能读、
 *   要不要按旧格式解析。没有它的话，旧文件只会解析失败而说不出原因。
 * @property exportedAt 导出时间，给用户在一堆备份文件里认哪份是哪份。
 */
@Serializable
data class BackupDocument(
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val exportedAt: Long = 0L,
    val sessions: List<BackupSession> = emptyList(),
) {
    companion object {
        /**
         * 当前格式版本。改动字段含义时必须加一。
         *
         * 第 2 版加了结算回执和剧情摘要。**旧文件照样能读** ——
         * 新增的两段都有默认空值，第 1 版的文件解析出来就是「没有回执、没有摘要」，
         * 这正是它当时的真实情况。
         */
        const val CURRENT_FORMAT_VERSION: Int = 2
    }
}

/** 备份里的一局游戏：卡、全部消息、全部数值变化。 */
@Serializable
data class BackupSession(
    val title: String,
    val cardJson: String? = null,
    val updatedAt: Long = 0L,
    /**
     * 这一局聊到哪一条了。存的是**备份文件内部的编号**，不是数据库里的编号 ——
     * 导入时所有编号都会重新分配，所以文件里只能用自己的一套。
     */
    val headMessageId: Long? = null,
    val messages: List<BackupMessage> = emptyList(),
    val stateEvents: List<BackupStateEvent> = emptyList(),
    /**
     * 每一轮结算完的那张回执。
     *
     * 不备份的话，恢复出来会是「数值变过，但没有任何交代」——
     * 一边有变过的数字、一边说不出为什么，比干脆不显示更让人起疑。
     */
    val receipts: List<BackupReceipt> = emptyList(),
    /**
     * 已经压好的剧情摘要。
     *
     * 🔴 **这一段是花钱买来的，而且无法从原文恢复** ——
     * 重压一次会得到另一段文字。不备份的话，换机之后又按原文发，
     * 之前压缩省下的上下文和花掉的钱一起作废。
     */
    val volumes: List<BackupVolume> = emptyList(),
)

/** 备份里的一条消息。 */
@Serializable
data class BackupMessage(
    val id: Long,
    val parentId: Long? = null,
    val fromUser: Boolean,
    val text: String,
    val turn: Int? = null,
    val createdAt: Long = 0L,
)

/**
 * 备份里的一次数值变化。
 *
 * 值存成文本，和数据库里一个样 —— 这样导出导入不用再转一道，
 * 少一次转换就少一处可能出错的地方。
 */
@Serializable
data class BackupStateEvent(
    val messageId: Long,
    val sequence: Long,
    val revision: Long,
    val path: String,
    val operation: String,
    val beforeJson: String,
    val afterJson: String,
    val reason: String? = null,
    val adjustedBy: String? = null,
)

/**
 * 备份里的一张结算回执。
 *
 * 变化和拦截存成文本，和数据库里一个样 —— 导出导入不用再转一道，
 * 少一次转换就少一处可能出错的地方。
 *
 * @property messageId **备份文件内部的消息编号**，导入时会换成新分配的。
 */
@Serializable
data class BackupReceipt(
    val messageId: Long,
    val turn: Int,
    val status: String,
    val summary: String? = null,
    val changesJson: String,
    val failuresJson: String,
    val usageTotalTokens: Int? = null,
    val usageCost: Double? = null,
    val attempt: Int = 1,
    val createdAt: Long = 0L,
)

/** 备份里的一卷剧情摘要。 */
@Serializable
data class BackupVolume(
    val index: Int,
    val fromTurn: Int,
    val toTurn: Int,
    val text: String,
    val merged: Boolean = false,
    val createdAt: Long = 0L,
    /**
     * 压这一卷时停在哪条消息上。**备份文件内部的编号**，导入时会换成新分配的。
     *
     * 换不出来就留空 —— 留空的含义是「不知道」，读取时一律当这卷算数。
     * 这比当它作废好：作废会让导入后的上下文凭空涨回去。
     */
    val basedOnMessageId: Long? = null,
)
