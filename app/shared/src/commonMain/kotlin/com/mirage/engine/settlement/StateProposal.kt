package com.mirage.engine.settlement

import com.mirage.engine.state.StateValue

/**
 * 从模型输出里解析出来的一组状态提议。
 *
 * 名字用「提议」而不是「结果」是刻意的：这里的内容**还没有经过任何校验**，
 * 它只代表模型想把某些字段改成什么。是否接受、要不要修正、哪些该拒绝，
 * 全部由后面的裁决环节决定。把这两件事混为一谈，就等于让模型直接改数值。
 */
data class StateProposal(
    /**
     * 字段路径到新值的映射。
     *
     * 值一律是**改完之后的绝对值**，不是增量。
     * 增量语义要求模型自己做算术，而算错之后误差会一路累积下去，
     * 且无法从结果反推出哪一步错了。绝对值则天然幂等，重放也不会漂移。
     */
    val values: Map<String, StateValue>,

    /** 字段路径到「为什么这么改」的说明。模型不给也不影响使用。 */
    val reasons: Map<String, String> = emptyMap(),

    /** 对本轮变化的一句话概括。 */
    val summary: String? = null,
)

/** 解析失败的类别。 */
enum class ProposalParseFailure {
    /** 模型什么都没返回。 */
    EmptyResponse,

    /** 返回了内容，但里面找不到任何结构化数据。 */
    NoStructuredContent,

    /** 找到了疑似结构化数据，但解析不了。 */
    MalformedStructure,

    /** 解析出来了，但形状不符合预期，取不出任何字段。 */
    UnexpectedShape,
}

/** 解析结果。 */
sealed interface ProposalParseResult {

    /**
     * 解析成功。
     *
     * @property recoveredFromWrapper 是否依赖了兜底手段才取出内容
     *   （例如模型把数据包在代码块里，或在前后加了说明文字）。
     *   把它记下来是为了能统计兜底的触发频率 —— 实测里直接解析的成功率只有一半，
     *   这个数字一旦变化，说明模型或提示词那边发生了我们没察觉的变化。
     */
    data class Success(
        val proposal: StateProposal,
        val recoveredFromWrapper: Boolean,
    ) : ProposalParseResult

    /**
     * 解析失败。
     *
     * @property preview 原始输出的开头片段，用于排查。截断保存，避免把超长内容整段留着。
     */
    data class Failure(
        val code: ProposalParseFailure,
        val preview: String,
    ) : ProposalParseResult
}
