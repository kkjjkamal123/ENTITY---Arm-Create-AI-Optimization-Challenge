package com.example.llama

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineBackgroundSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan

// Hand-rolled Markdown -> Spanned in the two-color style. Deliberately narrow:
// bold, italic, inline code (reverse video), fenced code blocks (left ink bar),
// bullet lists and headings. Any parse failure falls back to plain text.
object Markdown {

    private const val SPAN = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

    fun render(src: String, context: Context): CharSequence {
        return try {
            build(src, Ui.fg(context), Ui.bg(context), Ui.dp(context, 3), Ui.dp(context, 10))
        } catch (t: Throwable) {
            src
        }
    }

    private fun build(src: String, ink: Int, paper: Int, barWidth: Int, barGap: Int): CharSequence {
        val out = SpannableStringBuilder()
        val lines = src.split("\n")
        var inFence = false
        var codeStart = -1
        for (raw in lines) {
            val trimmed = raw.trimStart()
            if (trimmed.startsWith("```")) {
                if (!inFence) {
                    inFence = true
                    codeStart = out.length
                } else {
                    inFence = false
                    if (out.length > codeStart) applyCode(out, codeStart, out.length, ink, barWidth, barGap)
                }
                continue
            }
            if (inFence) {
                out.append(raw).append('\n')
                continue
            }
            appendBlockLine(out, raw, trimmed, ink, paper)
            out.append('\n')
        }
        while (out.isNotEmpty() && out.last() == '\n') out.delete(out.length - 1, out.length)
        return out
    }

    private fun appendBlockLine(out: SpannableStringBuilder, raw: String, trimmed: String, ink: Int, paper: Int) {
        if (trimmed.startsWith("#")) {
            var level = 0
            while (level < trimmed.length && trimmed[level] == '#') level++
            if (level in 1..6 && level < trimmed.length && trimmed[level] == ' ') {
                val start = out.length
                appendInline(out, trimmed.substring(level + 1), ink, paper)
                out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, SPAN)
                out.setSpan(RelativeSizeSpan(headingScale(level)), start, out.length, SPAN)
                return
            }
        }
        if (isBullet(trimmed)) {
            out.append("•  ")
            appendInline(out, trimmed.substring(2), ink, paper)
            return
        }
        appendInline(out, raw, ink, paper)
    }

    private fun appendInline(out: SpannableStringBuilder, s: String, ink: Int, paper: Int) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '`') {
                val close = s.indexOf('`', i + 1)
                if (close > i) {
                    val start = out.length
                    // Reverse video, terminal-style: ink block, paper text.
                    out.append(' ')
                    out.append(s, i + 1, close)
                    out.append(' ')
                    out.setSpan(BackgroundColorSpan(ink), start, out.length, SPAN)
                    out.setSpan(ForegroundColorSpan(paper), start, out.length, SPAN)
                    i = close + 1
                    continue
                }
            } else if (c == '*' && i + 1 < s.length && s[i + 1] == '*') {
                val close = s.indexOf("**", i + 2)
                if (close > i + 1) {
                    val start = out.length
                    appendInline(out, s.substring(i + 2, close), ink, paper)
                    out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, SPAN)
                    i = close + 2
                    continue
                }
            } else if (c == '*') {
                val close = s.indexOf('*', i + 1)
                if (close > i) {
                    val start = out.length
                    appendInline(out, s.substring(i + 1, close), ink, paper)
                    out.setSpan(StyleSpan(Typeface.ITALIC), start, out.length, SPAN)
                    i = close + 1
                    continue
                }
            }
            out.append(c)
            i++
        }
    }

    private fun applyCode(out: SpannableStringBuilder, start: Int, end: Int, ink: Int, barWidth: Int, barGap: Int) {
        out.setSpan(RelativeSizeSpan(0.92f), start, end, SPAN)
        out.setSpan(LeadingMarginSpan.Standard(barWidth + barGap), start, end, SPAN)
        out.setSpan(CodeBarSpan(ink, barWidth), start, end, SPAN)
    }

    private fun isBullet(t: String) =
        t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ")

    private fun headingScale(level: Int) = when (level) {
        1 -> 1.4f
        2 -> 1.25f
        else -> 1.12f
    }

    // Fenced code marker: a hard ink bar down the left edge instead of a gray wash.
    private class CodeBarSpan(private val ink: Int, private val barWidth: Int) : LineBackgroundSpan {
        override fun drawBackground(
            canvas: Canvas,
            paint: Paint,
            left: Int,
            right: Int,
            top: Int,
            baseline: Int,
            bottom: Int,
            text: CharSequence,
            start: Int,
            end: Int,
            lineNumber: Int,
        ) {
            val prev = paint.color
            paint.color = ink
            canvas.drawRect(left.toFloat(), top.toFloat(), (left + barWidth).toFloat(), bottom.toFloat(), paint)
            paint.color = prev
        }
    }
}
