package com.mirage.engine.llm

/**
 * 一条对话消息在「发给模型」这个场景下的角色。
 *
 * 这里刻意只有三个值，而不是把服务商协议里出现过的角色全列上：
 * 我们只负责「发出去」，不需要表达服务商内部的中间状态。
 * 多出来的角色会让调用方产生「是不是该用这个」的犹豫，反而容易用错。
 */
enum class ChatRole {
    /** 系统指令。约束模型的行为，通常放在消息列表最前面。 */
    System,

    /** 来自使用者的输入。 */
    User,

    /** 模型此前产出的回复。多轮对话时用它把历史回传。 */
    Assistant,
    ;

    /**
     * 转成协议里使用的小写字面量。
     *
     * 单独写一个方法而不是在序列化处临时拼，是因为请求体组装、调试输出、
     * 日志脱敏三处都要用到同一个映射，散开写迟早会出现大小写不一致。
     */
    fun wireValue(): String = when (this) {
        System -> "system"
        User -> "user"
        Assistant -> "assistant"
    }
}

/**
 * 发送给模型的单条消息。
 *
 * 只有角色和正文两个字段。附件、图片、工具结果等暂不支持 ——
 * 等真的要做时再扩展，提前留空字段只会让所有调用点都得处理 null。
 */
data class ChatMessage(
    val role: ChatRole,
    val content: String,
)
