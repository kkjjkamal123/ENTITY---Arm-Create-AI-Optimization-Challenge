package com.example.llama

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import java.util.ArrayDeque

// Lightweight multi-series line graph. No external library: fixed ring buffers
// keep memory bounded, and each series is normalized to its own min/max so
// values with different units (tok/s, watts, °C, GB) can overlay cleanly.
// Deliberately the ONE colored surface in the mono UI: seven overlaid series
// need hue to stay readable, so data gets color and chrome stays ink.
class MetricsGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private class Series(
        val key: String,
        val label: String,
        val color: Int,
        val format: (Float) -> String,
    ) {
        val values = ArrayDeque<Float>()
        var enabled = true
    }

    private val cap = 120
    private val series = listOf(
        Series(Settings.KEY_STAT_TOKENS, "tok", 0xFF4E79A7.toInt()) { it.toInt().toString() },
        Series(Settings.KEY_STAT_SPEED, "tok/s", 0xFF10A37F.toInt()) { "%.1f".format(it) },
        Series(Settings.KEY_STAT_TTFT, "TTFT", 0xFFB07AA1.toInt()) { "${it.toInt()}ms" },
        Series(Settings.KEY_STAT_TEMP, "°C", 0xFFE15759.toInt()) { "%.1f".format(it) },
        Series(Settings.KEY_STAT_POWER, "W", 0xFFF28E2B.toInt()) { "%.2f".format(it) },
        Series(Settings.KEY_STAT_CPU, "CPU", 0xFF9C755F.toInt()) { "%.0f%%".format(it) },
        Series(Settings.KEY_STAT_MEMORY, "GB", 0xFF17A2B8.toInt()) { "%.1f".format(it) },
    )

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.6f)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x22888888
        strokeWidth = dp(1f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(10f)
        color = 0xFF9A9AA6.toInt()
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val fillPath = Path()

    // Optional eye candy, gated behind the user's Animations setting like every
    // other decorative effect (Anim.enabled), so a minimal UI stays minimal.
    private var fillEnabled = false
    private var smoothEnabled = false

    // Set when the phone is measurably not keeping up. Anti-aliasing, the area fill and
    // curve smoothing are the expensive parts of this view, and they are what gets given
    // up first so the cycles go to decode instead. A phone that holds its frame rate is
    // never put in this mode and never loses the nicer rendering.
    private var strained = false

    fun setStrained(value: Boolean) {
        if (strained == value) return
        strained = value
        linePaint.isAntiAlias = !value
        fillPaint.isAntiAlias = !value
        gridPaint.isAntiAlias = !value
        textPaint.isAntiAlias = !value
        invalidate()
    }

    fun setStyle(fill: Boolean, smooth: Boolean) {
        if (fillEnabled != fill || smoothEnabled != smooth) {
            fillEnabled = fill
            smoothEnabled = smooth
            invalidate()
        }
    }

    fun setSeriesEnabled(key: String, enabled: Boolean) {
        series.firstOrNull { it.key == key }?.let {
            if (it.enabled != enabled) {
                it.enabled = enabled
                invalidate()
            }
        }
    }

    fun addSample(
        tokens: Float,
        speed: Float,
        ttft: Float,
        temp: Float,
        power: Float,
        cpu: Float,
        memory: Float,
    ) {
        push(Settings.KEY_STAT_TOKENS, tokens)
        push(Settings.KEY_STAT_SPEED, speed)
        push(Settings.KEY_STAT_TTFT, ttft)
        push(Settings.KEY_STAT_TEMP, temp)
        push(Settings.KEY_STAT_POWER, power)
        push(Settings.KEY_STAT_CPU, cpu)
        push(Settings.KEY_STAT_MEMORY, memory)
        invalidate()
    }

    private fun push(key: String, v: Float) {
        val s = series.first { it.key == key }
        s.values.addLast(v)
        while (s.values.size > cap) s.values.removeFirst()
    }

    fun clear() {
        series.forEach { it.values.clear() }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val active = series.filter { it.enabled }

        // Wrapping legend: coloured dot + label + latest value.
        val padX = dp(10f)
        val gap = dp(14f)
        val dotR = dp(3.5f)
        var lx = padX
        var ly = dp(16f)
        val lineH = dp(16f)
        for (s in active) {
            val latest = s.values.lastOrNull()
            val txt = "${s.label} ${latest?.let(s.format) ?: "-"}"
            val itemW = dotR * 2 + dp(5f) + textPaint.measureText(txt)
            if (lx + itemW > w - padX && lx > padX) {
                lx = padX
                ly += lineH
            }
            dotPaint.color = s.color
            canvas.drawCircle(lx + dotR, ly - dp(4f), dotR, dotPaint)
            canvas.drawText(txt, lx + dotR * 2 + dp(5f), ly, textPaint)
            lx += itemW + gap
        }

        val top = ly + dp(8f)
        val bottom = h - dp(8f)
        if (bottom <= top) return

        for (i in 0..2) {
            val y = top + (bottom - top) * i / 2f
            canvas.drawLine(padX, y, w - padX, y, gridPaint)
        }

        // Spread whatever samples exist across the full width instead of pinning them to
        // 120 fixed slots. Anchoring to the buffer capacity drew the first minute of a
        // session as a spike jammed against the right edge, which read as a broken chart
        // rather than a filling one.
        val filled = active.maxOfOrNull { it.values.size } ?: return
        val stepX = (w - 2 * padX) / (filled - 1).coerceAtLeast(1).toFloat()
        val fancy = Anim.enabled(context)
        val useFill = fillEnabled && fancy && !strained
        val useSmooth = smoothEnabled && fancy && !strained
        for (s in active) {
            val n = s.values.size
            if (n < 2) continue
            var mn = Float.MAX_VALUE
            var mx = -Float.MAX_VALUE
            for (v in s.values) {
                if (v < mn) mn = v
                if (v > mx) mx = v
            }
            val range = if (mx - mn < 1e-6f) 1f else mx - mn
            path.reset()
            var idx = filled - n
            var first = true
            var firstX = 0f
            var px = 0f
            var py = 0f
            for (v in s.values) {
                val x = padX + idx * stepX
                val y = bottom - (v - mn) / range * (bottom - top)
                when {
                    first -> { path.moveTo(x, y); firstX = x }
                    useSmooth -> path.quadTo(px, py, (px + x) / 2f, (py + y) / 2f)
                    else -> path.lineTo(x, y)
                }
                first = false
                px = x
                py = y
                idx++
            }
            if (useSmooth) path.lineTo(px, py)
            if (useFill) {
                fillPath.set(path)
                fillPath.lineTo(px, bottom)
                fillPath.lineTo(firstX, bottom)
                fillPath.close()
                fillPaint.color = (s.color and 0x00FFFFFF) or 0x2E000000
                canvas.drawPath(fillPath, fillPaint)
            }
            linePaint.color = s.color
            canvas.drawPath(path, linePaint)
        }
    }
}
