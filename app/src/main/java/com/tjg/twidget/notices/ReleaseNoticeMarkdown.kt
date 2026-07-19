package com.tjg.twidget.notices

import android.content.Context
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.QuoteSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import androidx.core.content.ContextCompat
import com.tjg.twidget.R

/** Lightweight native Markdown styling for the subset used by GitHub release notes. */
object ReleaseNoticeMarkdown {
    fun render(context: Context, markdown: String): CharSequence {
        val output = SpannableStringBuilder()
        val lines = markdown.trim().lines()
        var previousKind: LineKind? = null

        lines.forEach { rawLine ->
            val line = rawLine.trimEnd()
            if (line.isBlank()) {
                appendBlankLine(output)
                previousKind = null
                return@forEach
            }

            val parsed = parseLine(line)
            val kind = parsed.kind
            if (output.isNotEmpty() && previousKind != null && previousKind != kind && output.last() != '\n') {
                output.append('\n')
            }

            when (kind) {
                LineKind.HEADING -> appendHeading(
                    context,
                    output,
                    parsed.level,
                    parsed.content,
                )
                LineKind.BULLET -> appendListItem(
                    context,
                    output,
                    marker = "•",
                    content = parsed.content,
                    depth = parsed.depth,
                )
                LineKind.ORDERED -> appendListItem(
                    context,
                    output,
                    marker = parsed.marker,
                    content = parsed.content,
                    depth = parsed.depth,
                )
                LineKind.QUOTE -> appendQuote(context, output, parsed.content)
                LineKind.PARAGRAPH -> appendParagraph(context, output, parsed.content)
            }
            previousKind = kind
        }

        while (output.isNotEmpty() && output.last().isWhitespace()) output.delete(output.length - 1, output.length)
        return output
    }

    private fun appendHeading(
        context: Context,
        output: SpannableStringBuilder,
        level: Int,
        content: String,
    ) {
        ensureBlockGap(output)
        val start = output.length
        appendInline(context, output, content)
        output.setSpan(StyleSpan(Typeface.BOLD), start, output.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        output.setSpan(
            RelativeSizeSpan(if (level <= 2) 1.3f else if (level == 3) 1.16f else 1.06f),
            start,
            output.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        output.append('\n')
    }

    private fun appendListItem(
        context: Context,
        output: SpannableStringBuilder,
        marker: String,
        content: String,
        depth: Int,
    ) {
        val start = output.length
        output.append(marker).append(' ')
        appendInline(context, output, content)
        output.setSpan(
            LeadingMarginSpan.Standard(dp(context, 14 + depth * 12), dp(context, 28 + depth * 12)),
            start,
            output.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        output.append('\n')
    }

    private fun appendQuote(context: Context, output: SpannableStringBuilder, content: String) {
        ensureBlockGap(output)
        val start = output.length
        appendInline(context, output, content)
        val accent = ContextCompat.getColor(context, R.color.oneui_accent)
        output.setSpan(QuoteSpan(accent), start, output.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        output.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(context, R.color.oneui_text_secondary)),
            start,
            output.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        output.append('\n')
    }

    private fun appendParagraph(context: Context, output: SpannableStringBuilder, content: String) {
        appendInline(context, output, content)
        output.append('\n')
    }

    private fun appendInline(context: Context, output: SpannableStringBuilder, source: String) {
        var index = 0
        while (index < source.length) {
            when {
                source[index] == '\\' && index + 1 < source.length -> {
                    output.append(source[index + 1])
                    index += 2
                }
                source.startsWith("![", index) -> {
                    val closeLabel = source.indexOf(']', index + 2)
                    val closeUrl = if (closeLabel >= 0 && source.getOrNull(closeLabel + 1) == '(') {
                        source.indexOf(')', closeLabel + 2)
                    } else -1
                    if (closeUrl >= 0) {
                        output.append(source.substring(index + 2, closeLabel))
                        index = closeUrl + 1
                    } else {
                        output.append(source[index++])
                    }
                }
                source[index] == '[' -> {
                    val closeLabel = source.indexOf(']', index + 1)
                    val closeUrl = if (closeLabel >= 0 && source.getOrNull(closeLabel + 1) == '(') {
                        source.indexOf(')', closeLabel + 2)
                    } else -1
                    if (closeUrl >= 0) {
                        val start = output.length
                        appendInline(context, output, source.substring(index + 1, closeLabel))
                        output.setSpan(
                            URLSpan(source.substring(closeLabel + 2, closeUrl)),
                            start,
                            output.length,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                        output.setSpan(
                            ForegroundColorSpan(ContextCompat.getColor(context, R.color.oneui_accent)),
                            start,
                            output.length,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                        index = closeUrl + 1
                    } else {
                        output.append(source[index++])
                    }
                }
                source.startsWith("**", index) || source.startsWith("__", index) -> {
                    val marker = source.substring(index, index + 2)
                    val close = source.indexOf(marker, index + 2)
                    if (close >= 0) {
                        val start = output.length
                        appendInline(context, output, source.substring(index + 2, close))
                        output.setSpan(StyleSpan(Typeface.BOLD), start, output.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        index = close + 2
                    } else {
                        output.append(source[index++])
                    }
                }
                source[index] == '*' || source[index] == '_' -> {
                    val marker = source[index]
                    val close = source.indexOf(marker, index + 1)
                    if (close > index + 1) {
                        val start = output.length
                        appendInline(context, output, source.substring(index + 1, close))
                        output.setSpan(StyleSpan(Typeface.ITALIC), start, output.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        index = close + 1
                    } else {
                        output.append(source[index++])
                    }
                }
                source[index] == '`' -> {
                    val close = source.indexOf('`', index + 1)
                    if (close > index + 1) {
                        val start = output.length
                        output.append(source.substring(index + 1, close))
                        output.setSpan(TypefaceSpan("monospace"), start, output.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        output.setSpan(
                            BackgroundColorSpan(ContextCompat.getColor(context, R.color.oneui_divider)),
                            start,
                            output.length,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                        index = close + 1
                    } else {
                        output.append(source[index++])
                    }
                }
                else -> output.append(source[index++])
            }
        }
    }

    private fun ensureBlockGap(output: SpannableStringBuilder) {
        if (output.isEmpty()) return
        if (output.last() != '\n') output.append('\n')
        if (output.length < 2 || output[output.length - 2] != '\n') output.append('\n')
    }

    private fun appendBlankLine(output: SpannableStringBuilder) {
        if (output.isEmpty()) return
        if (output.last() != '\n') output.append('\n')
        if (output.length < 2 || output[output.length - 2] != '\n') output.append('\n')
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    internal fun parseLine(line: String): ParsedLine {
        HEADING.matchEntire(line.trimStart())?.let {
            return ParsedLine(LineKind.HEADING, it.groupValues[2], level = it.groupValues[1].length)
        }
        BULLET.matchEntire(line)?.let {
            return ParsedLine(LineKind.BULLET, it.groupValues[2], depth = it.groupValues[1].length / 2)
        }
        ORDERED.matchEntire(line)?.let {
            return ParsedLine(
                LineKind.ORDERED,
                it.groupValues[3],
                depth = it.groupValues[1].length / 2,
                marker = "${it.groupValues[2]}.",
            )
        }
        QUOTE.matchEntire(line)?.let {
            return ParsedLine(LineKind.QUOTE, it.groupValues[1])
        }
        return ParsedLine(LineKind.PARAGRAPH, line.trim())
    }

    internal data class ParsedLine(
        val kind: LineKind,
        val content: String,
        val level: Int = 0,
        val depth: Int = 0,
        val marker: String = "",
    )

    internal enum class LineKind { HEADING, BULLET, ORDERED, QUOTE, PARAGRAPH }

    private val HEADING = Regex("^(#{1,6})\\s+(.+)$")
    private val BULLET = Regex("^(\\s*)[-*+]\\s+(.+)$")
    private val ORDERED = Regex("^(\\s*)(\\d+)[.)]\\s+(.+)$")
    private val QUOTE = Regex("^\\s*>\\s?(.*)$")
}
