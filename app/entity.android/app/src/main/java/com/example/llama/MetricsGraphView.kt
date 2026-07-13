package com.example.llama

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.util.ArrayDeque

// Lightweight multi-series line graph. No external library: fixed ring buffers
// keep memory bounded, and each series is normalized to its own min/max so
// values with different units (tok/s, watts, °C, GB) can overlay cleanly.
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
        Series("stat_tokens", "tok", 0xFF4E79A7.toInt()) { it.toInt().toString() },
        Series("stat_speed", "tok/s", 0xFF10A37F.toInt()) { "%.1f".format(it) },
        Series("stat_ttft", "TTFT", 0xFFB07AA1.toInt()) { "${it.toInt()}ms" },
        Series("stat_temp", "°C", 0xFFE15759.toInt()) { "%.1f".format(it) },
        Series("stat_power", "W", 0xFFF28E2B.toInt()) { "%.2f".format(it) },
        Series("stat_memory", "GB", 0xFF17A2B8.toInt()) { "%.1f".format(it) },
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
        textSize = sp(11f)
        color = 0xFF9A9AA6.toInt()
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setSeriesEnabled(key: String, enabled: Boolean) {
        series.firstOrNull { it.key == key }?.let {
            if (it.enabled != enabled) {
                it.enabled = enabled
                invalidate()
            }
        }
    }

    fun addSample(tokens: Float, speed: Float, ttft: Float, temp: Float, power: Float, memory: Float) {
        push("stat_tokens", tokens)
        push("stat_speed", speed)
        push("stat_ttft", ttft)
        push("stat_temp", temp)
        push("stat_power", power)
        push("stat_memory", memory)
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
        var ly = dp(15f)
        val lineH = dp(18f)
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

        val stepX = (w - 2 * padX) / (cap - 1).toFloat()
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
            linePaint.color = s.color
            var idx = cap - n
            var first = true
            var px = 0f
            var py = 0f
            for (v in s.values) {
                val x = padX + idx * stepX
                val y = bottom - (v - mn) / range * (bottom - top)
                if (!first) canvas.drawLine(px, py, x, y, linePaint)
                first = false
                px = x
                py = y
                idx++
            }
        }
    }
}
