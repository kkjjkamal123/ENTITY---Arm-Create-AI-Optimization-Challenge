package com.example.llama

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.LineBackgroundSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import androidx.core.content.ContextCompat

// Hand-rolled Markdown -> Spanned. Deliberately narrow: bold, italic, inline
// code, fenced code blocks, bullet lists and headings. Any parse failure or
// malformed markup falls back to plain text; it never throws.
object Markdown {

    private const val SPAN = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

    fun render(src: String, context: Context): CharSequence {
        return try {
            build(src, ContextCompat.getColor(context, R.color.code_bg))
        } catch (t: Throwable) {
            src
        }
    }

    private fun build(src: String, codeBg: Int): CharSequence {
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
                    if (out.length > codeStart) applyCode(out, codeStart, out.length, codeBg)
                }
                continue
            }
            if (inFence) {
                out.append(raw).append('\n')
                continue
            }
            appendBlockLine(out, raw, trimmed, codeBg)
            out.append('\n')
        }
        while (out.isNotEmpty() && out.last() == '\n') out.delete(out.length - 1, out.length)
        return out
    }

    private fun appendBlockLine(out: SpannableStringBuilder, raw: String, trimmed: String, codeBg: Int) {
        if (trimmed.startsWith("#")) {
            var level = 0
            while (level < trimmed.length && trimmed[level] == '#') level++
            if (level in 1..6 && level < trimmed.length && trimmed[level] == ' ') {
                val start = out.length
                appendInline(out, trimmed.substring(level + 1), codeBg)
                out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, SPAN)
                out.setSpan(RelativeSizeSpan(headingScale(level)), start, out.length, SPAN)
                return
            }
        }
        if (isBullet(trimmed)) {
            out.append("•  ")
            appendInline(out, trimmed.substring(2), codeBg)
            return
        }
        appendInline(out, raw, codeBg)
    }

    private fun appendInline(out: SpannableStringBuilder, s: String, codeBg: Int) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '`') {
                val close = s.indexOf('`', i + 1)
                if (close > i) {
                    val start = out.length
                    out.append(s, i + 1, close)
                    out.setSpan(TypefaceSpan("monospace"), start, out.length, SPAN)
                    out.setSpan(RelativeSizeSpan(0.92f), start, out.length, SPAN)
                    out.setSpan(BackgroundColorSpan(codeBg), start, out.length, SPAN)
                    i = close + 1
                    continue
                }
            } else if (c == '*' && i + 1 < s.length && s[i + 1] == '*') {
                val close = s.indexOf("**", i + 2)
                if (close > i + 1) {
                    val start = out.length
                    appendInline(out, s.substring(i + 2, close), codeBg)
                    out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, SPAN)
                    i = close + 2
                    continue
                }
            } else if (c == '*') {
                val close = s.indexOf('*', i + 1)
                if (close > i) {
                    val start = out.length
                    appendInline(out, s.substring(i + 1, close), codeBg)
                    out.setSpan(StyleSpan(Typeface.ITALIC), start, out.length, SPAN)
                    i = close + 1
                    continue
                }
            }
            out.append(c)
            i++
        }
    }

    private fun applyCode(out: SpannableStringBuilder, start: Int, end: Int, bg: Int) {
        out.setSpan(TypefaceSpan("monospace"), start, end, SPAN)
        out.setSpan(RelativeSizeSpan(0.92f), start, end, SPAN)
        out.setSpan(CodeBlockSpan(bg), start, end, SPAN)
    }

    private fun isBullet(t: String) =
        t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ")

    private fun headingScale(level: Int) = when (level) {
        1 -> 1.4f
        2 -> 1.25f
        else -> 1.12f
    }

    private class CodeBlockSpan(private val bg: Int) : LineBackgroundSpan {
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
            paint.color = bg
            canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), paint)
            paint.color = prev
        }
    }
}
