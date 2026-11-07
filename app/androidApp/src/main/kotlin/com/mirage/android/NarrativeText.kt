package com.mirage.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.mirage.engine.text.InlineMarkup
import com.mirage.engine.text.MarkupStyle

/**
 * 把正文的标记渲染成带样式的文字。
 *
 * 解析在引擎那边做（纯算、有单测），这里只负责「每种样式长什么样」——
 * 颜色和字重是跟着主题走的，放进引擎就改不动了。
 *
 * 角色扮演的排版约定：`*动作*` 用斜体、台词用引号并稍微提亮，
 * 这两样不渲染的话，满屏星号会让正文很难读。
 */
@Composable
fun narrativeText(raw: String): AnnotatedString {
    val marked = InlineMarkup.parse(raw)
    val quoteColor = MaterialTheme.colorScheme.primary

    return buildAnnotatedString {
        append(marked.text)
        marked.spans.forEach { span ->
            // 解析出来的范围理论上一定落在文字里，但这里仍然裁一次：
            // 范围越界会直接抛异常，而一段正文显示不出来比样式不对严重得多
            val start = span.start.coerceIn(0, marked.text.length)
            val end = span.end.coerceIn(start, marked.text.length)
            if (start == end) return@forEach

            val style = when (span.style) {
                MarkupStyle.Bold -> SpanStyle(fontWeight = FontWeight.Bold)
                MarkupStyle.Italic -> SpanStyle(fontStyle = FontStyle.Italic)
                MarkupStyle.Quote -> SpanStyle(color = quoteColor)
                MarkupStyle.Code -> SpanStyle(fontFamily = FontFamily.Monospace)
            }
            addStyle(style, start, end)
        }
    }
}
