package com.mirage.android

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.mirage.engine.llm.TokenUsage
import java.util.Locale

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

    // 金额位数固定给足：这类计价常见到小数点后第六位才有有效数字，
    // 按常规两位显示会把真实开销显示成 0.00，看起来像免费
    val costPart = usage.cost?.let {
        stringResource(R.string.usage_cost, String.format(Locale.US, "%.6f", it))
    }

    return listOfNotNull(tokensPart.takeIf { it.isNotEmpty() }, costPart).joinToString("  ")
}
