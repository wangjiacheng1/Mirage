package com.mirage.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.mirage.engine.llm.TokenUsage
import java.util.Locale
import com.mirage.android.R

/**
 * 把一次调用的用量渲染成一行字。
 *
 * 单独放在这里是因为**有两处要用同一套口径**：对话本身的开销，和后台结算的开销。
 * 两处各写一遍的话，迟早会出现「同一个数字在两个地方格式不一样」，
 * 而这恰恰是最容易让人怀疑账目的地方。
 *
 * 思考部分单独点出来：它通常比正文贵得多，而且默认就是开着的 ——
 * 不显式关掉就会产生这笔开销，使用者有权看见它到底占了多少。
 */
@Composable
fun usageText(usage: TokenUsage): String {
    if (usage.isEmpty) return stringResource(R.string.usage_unavailable)

    val total = usage.totalTokens
    val reasoning = usage.reasoningTokens
    val tokensPart = when {
        total != null && reasoning != null ->
            stringResource(R.string.usage_tokens_with_reasoning, total, reasoning)

        total != null -> stringResource(R.string.usage_tokens, total)
        else -> ""
    }

    return listOfNotNull(tokensPart.takeIf { it.isNotEmpty() }, costText(usage.cost))
        .joinToString("  ")
}

/**
 * 把这一轮两次调用的用量合成一行：正文一次、后台算状态栏一次。
 *
 * 只看「本次用量」那行会以为这一轮就花了这些，实际后台还有一笔 ——
 * 这行就是把总账摆出来，并写清是哪两笔加起来的。
 *
 * 两笔有任意一笔还没拿到数字就返回 null、整行不显示。
 * 宁可不给，也不能给一个少算了一半的总数 —— 那比没有更误导人。
 */
@Composable
fun turnTotalUsageText(narrative: TokenUsage, settlement: TokenUsage): String? {
    val narrativeTokens = narrative.withDerivedTotal().totalTokens ?: return null
    val settlementTokens = settlement.withDerivedTotal().totalTokens ?: return null

    val total = narrative + settlement
    val head = stringResource(
        R.string.usage_turn_total,
        total.totalTokens ?: (narrativeTokens + settlementTokens),
        narrativeTokens,
        settlementTokens,
    )
    return listOfNotNull(head, costText(total.cost)).joinToString("  ")
}

/**
 * 金额那一截。没有金额时返回 null，由调用方决定整行怎么拼。
 *
 * 位数固定给足：这类计价常见到小数点后第六位才有有效数字，
 * 按常规两位显示会把真实开销显示成 0.00，看起来像免费。
 */
@Composable
private fun costText(cost: Double?): String? =
    cost?.let { stringResource(R.string.usage_cost, String.format(Locale.US, "%.6f", it)) }
