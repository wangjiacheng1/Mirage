package com.mirage.android

import com.mirage.engine.prompt.NarrativePreset
import com.mirage.engine.summary.CompactionPlanner
import com.mirage.engine.summary.CompactionTrigger

/**
 * 全部行为开关集中在这里。
 *
 * 📌 **规矩：凡是「可以关掉／可以调大小」的东西，都写在这个文件里，不要散落到各处。**
 *
 * 这样做的好处很实际：排查问题时第一件事往往是「把某个功能关掉看看还复现吗」，
 * 开关散在十个文件里就得先找一遍；集中起来则一眼看全、改一处就生效。
 *
 * 现在这些值是写死的常量。等做设置界面时，这里会换成「从设置里读、读不到用默认值」，
 * **而调用方一行都不用改** —— 这也是为什么它们从一开始就不散落在使用处。
 */
object AppSwitches {

    /**
     * 是否在所有卡设定之前加一段通用叙事要求。
     *
     * 关掉之后，发给模型的就只有卡自己的内容 —— 用来判断
     * 「正文的毛病是我们的预设造成的，还是卡本身就这样」。
     */
    const val USE_GLOBAL_NARRATIVE_PRESET: Boolean = true

    /** 通用叙事要求的内容。将来做设置界面时，这里换成用户可编辑的文本。 */
    val GLOBAL_NARRATIVE_PRESET: String get() = NarrativePreset.DEFAULT

    /**
     * 是否把当前状态栏数值随正文一起发给模型。
     *
     * 开着它模型才知道现在什么处境（血剩多少、人在哪），写出来的内容才不会和面板矛盾。
     * 关掉可以省一点长度，也可以用来确认「正文里冒出数字」是不是因为给它看了数值。
     */
    const val SEND_STATE_WITH_NARRATIVE: Boolean = true

    /**
     * 是否启用后台状态结算。
     *
     * 关掉之后只聊天、状态栏不动。
     * 先关掉它就能把正文和数值两条链路分开看。
     */
    const val ENABLE_SETTLEMENT: Boolean = true

    /**
     * 匹配世界观条目触发词时，往回看几条聊天。
     *
     * 调大则更早的话题也能触发设定，但不相干的设定会更容易被带进来。
     */
    const val RECENT_SCAN_MESSAGES: Int = 4

    /**
     * 是否打印网络诊断日志。
     */
    const val ENABLE_NETWORK_LOG_IN_DEBUG: Boolean = true

    /**
     * 什么时候把旧的聊天记录压成摘要。
     *
     * 默认手动：压缩是有损的，而且要多花一次钱，不该背着人做。
     * 换成 `EveryNTurns(20)` 或 `ContextPercent(70)` 就变成自动。
     */
    val COMPACTION_TRIGGER: CompactionTrigger get() = CompactionTrigger.Manual

    /**
     * 压缩时保留几轮原文不动。
     *
     * 最近发生的事必须是原话 —— 摘要会丢掉语气、称呼、具体用词，
     * 而接下来几轮要接住的恰恰是这些。
     */
    const val KEEP_RECENT_TURNS: Int = CompactionPlanner.DEFAULT_KEEP_RECENT_TURNS
}
