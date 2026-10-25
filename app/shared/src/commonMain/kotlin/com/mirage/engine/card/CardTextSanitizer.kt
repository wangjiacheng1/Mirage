package com.mirage.engine.card

/**
 * ============================================================================
 * CardTextSanitizer —— 把别家框架的「状态块」从正文里清掉
 * ============================================================================
 *
 * ## 这是干什么的
 *
 * 很多带状态玩法的卡，会在开场消息末尾塞两样东西：
 * 一整段写着初始数值的标签块，和一个状态面板的占位标签。
 * 在原来的运行环境里，这两样会被框架吃掉、换成一个漂亮的面板，用户看不见原文。
 *
 * 我们不执行卡里带的那套东西（面板由我们自己画），所以这些标签到了这里
 * 就是两个麻烦：
 *
 * 1. **用户会看见一大坨 JSON 贴在开场白后面**，像是出了故障；
 * 2. 更要命的是，它留在了聊天记录里 —— 模型每轮都看到「上一条消息长这样」，
 *    于是它开始**照着写**，正文里凭空冒出状态块和数值。
 *    而我们整套设计的前提恰恰是「正文里一个数字都不出现」。
 *
 * 第 2 条是实测中真实发生过的：开场消息末尾带着这些标签，
 * 几轮之后模型就在正文结尾自己吐出了一个状态块。
 *
 * ## 为什么是「清掉」而不是「渲染」
 *
 * 那个占位标签在原环境里会被替换成一整页带样式的内容，里面是可执行的东西。
 * 我们不执行它 —— 状态栏由我们自己按数据画，兼容守在数据这一层，不在显示这一层。
 * 所以这里只做一件事：把标签连同它包住的内容整段拿掉。
 */
object CardTextSanitizer {

    /**
     * 已知的状态块标签名。
     *
     * 只清列出来的这几个，不做「凡是尖括号都清掉」那种一刀切 ——
     * 正文里完全可能出现正常的尖括号内容（比如对话里提到某个符号），
     * 清过头比漏清更糟：漏清只是多一行字，清过头会把故事本身吃掉。
     */
    private val BLOCK_TAGS = listOf("initvar", "mvu-status", "mvu_status", "statusbar", "state")

    /**
     * 清掉正文里的状态块。
     *
     * 三种写法都要认，而且判断顺序很要紧：
     * 自闭合的（只有一个标签）、成对的（标签包着内容）、
     * 以及只有开标签没有闭标签的残缺写法（流式输出被中途打断时会出现）。
     *
     * 🔴 这里踩过一次：先去找闭标签，找不到就从开标签一路清到结尾 ——
     * 结果自闭合标签本来就没有闭标签，于是它后面的所有正文都被当成块内容删掉了。
     * 所以必须先看这个标签本身是不是自闭合的，再去考虑配对。
     */
    fun stripStatusBlocks(text: String): String {
        var result = text
        BLOCK_TAGS.forEach { tag -> result = removeBlocks(result, tag) }
        // 清完之后往往留下成片空行，收一下。保留段落之间的一个空行，
        // 不要把整段故事挤成一坨
        return result.replace(MULTI_BLANK_LINES, "\n\n").trim()
    }

    /** 这段文字里有没有状态块。导入预检时用来提示用户。 */
    fun containsStatusBlock(text: String): Boolean =
        BLOCK_TAGS.any { tag -> findTagStart(text, tag, 0) >= 0 }

    /**
     * 清掉某一种标签的所有块。
     *
     * 手工扫描而不是用一个贪婪的正则：块里的内容可能很长、可能带换行，
     * 不同平台的正则对跨行匹配的默认行为并不一致，手工扫一遍结果可预期。
     */
    private fun removeBlocks(text: String, tag: String): String {
        var result = text
        var searchFrom = 0
        while (true) {
            val start = findTagStart(result, tag, searchFrom)
            if (start < 0) return result

            val openEnd = result.indexOf('>', startIndex = start)
            if (openEnd < 0) {
                // 连开标签都没写完，后面不可能还有正文
                return result.substring(0, start)
            }

            val selfClosing = result[openEnd - 1] == '/'
            if (selfClosing) {
                result = result.removeRange(start, openEnd + 1)
                searchFrom = start
                continue
            }

            val closeAt = result.indexOf("</$tag>", startIndex = openEnd, ignoreCase = true)
            result = if (closeAt >= 0) {
                result.removeRange(start, closeAt + tag.length + 3)
            } else {
                // 有开标签、没有闭标签：这段内容被截断了。
                // 从开标签一直清到结尾 —— 残缺的半个状态块留着同样会被模型学去
                result.substring(0, start)
            }
            searchFrom = start
        }
    }

    /**
     * 找标签的起始位置。
     *
     * 要求标签名后面紧跟的是空白、斜杠或右尖括号，否则不算 ——
     * 否则一个以标签名开头的更长的词（比如某个更长的标签）也会被误伤。
     */
    private fun findTagStart(text: String, tag: String, from: Int): Int {
        var index = from
        while (true) {
            val found = text.indexOf("<$tag", startIndex = index, ignoreCase = true)
            if (found < 0) return -1
            val after = found + tag.length + 1
            if (after >= text.length) return found
            val next = text[after]
            if (next == '>' || next == '/' || next.isWhitespace()) return found
            index = found + 1
        }
    }

    private val MULTI_BLANK_LINES = Regex("\n{3,}")
}
