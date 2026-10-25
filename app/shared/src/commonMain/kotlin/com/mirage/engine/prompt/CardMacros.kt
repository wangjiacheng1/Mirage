package com.mirage.engine.prompt

/**
 * ============================================================================
 * CardMacros —— 把卡里的占位写法换成真名字
 * ============================================================================
 *
 * 卡的正文和设定里到处写着 `{{user}}`、`{{char}}` 这类占位，
 * 意思是「这里填玩家的名字」「这里填角色的名字」。
 *
 * 不替换直接发出去，模型看到的就是这两个花括号本身。实测的一张卡里
 * `{{user}}` 出现了 19 次 —— 等于每条设定都夹着一个它读不懂的符号，
 * 它还可能把这个符号原样写进故事里。
 *
 * ## 认不出来的占位一律原样留着
 *
 * 占位的种类很多（时间、随机数、历史片段……），我们只换确定知道含义的那两个。
 * 把认不出来的占位删掉或者瞎填，会悄悄改变卡作者的本意；
 * 原样留着最多是多几个符号，而且一眼就能看出来是哪个占位没支持。
 */
object CardMacros {

    /** 玩家名字的几种写法。大小写都有人写。 */
    private val USER_KEYS = listOf("{{user}}", "{{User}}", "{{USER}}")

    /** 角色名字的几种写法。 */
    private val CHAR_KEYS = listOf("{{char}}", "{{Char}}", "{{CHAR}}")

    /**
     * 替换占位。
     *
     * @param userName 玩家怎么称呼。没有专门设定时用第二人称，
     *   读起来就是「你推开门」，这比硬塞一个假名字自然。
     * @param charName 角色名，直接取卡里的名字。
     */
    fun expand(text: String, userName: String, charName: String): String {
        if (text.isEmpty()) return text
        var result = text
        USER_KEYS.forEach { key -> result = result.replace(key, userName) }
        CHAR_KEYS.forEach { key -> result = result.replace(key, charName) }
        return result
    }
}
