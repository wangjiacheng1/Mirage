package com.mirage.engine.credential

/**
 * 生成凭据的展示用摘要。
 *
 * 界面需要让用户确认「我配的是哪一个」，但不应该把完整凭据显示出来 ——
 * 屏幕会被截图、会被录屏、也会被旁人看到。
 *
 * 抽成一处而不是让各个界面自己截断，是因为「保留几位、怎么遮」这件事
 * 一旦各写一套，迟早有一处保留得太多。统一在这里也便于以后整体收紧。
 */
object CredentialPreview {

    /** 短于这个长度的凭据一律全遮，因为保留任何几位都会占比过高。 */
    private const val MIN_LENGTH_TO_REVEAL = 12

    /** 末尾保留的位数。只保留末尾，因为开头往往是各家固定的前缀，没有区分度。 */
    private const val REVEALED_SUFFIX_LENGTH = 4

    /** 遮蔽部分的固定宽度。不按真实长度来，避免泄露凭据长度。 */
    private const val MASK = "••••••"

    /**
     * 生成摘要。
     *
     * @return 形如 `••••••6789`。空凭据返回空字符串，由界面决定怎么表达「未配置」——
     *   这里不产出任何提示文案。
     */
    fun of(secret: String): String {
        if (secret.isEmpty()) return ""
        if (secret.length < MIN_LENGTH_TO_REVEAL) return MASK
        return MASK + secret.takeLast(REVEALED_SUFFIX_LENGTH)
    }
}
