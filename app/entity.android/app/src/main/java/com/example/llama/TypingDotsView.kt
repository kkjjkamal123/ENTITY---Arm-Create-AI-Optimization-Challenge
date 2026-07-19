package com.example.llama

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.sin

// Three-dot "thinking" pulse drawn from a single reused Paint. Self-animating
// via one ValueAnimator that starts/stops with visibility, so RecyclerView
// recycling never leaks a running animator. Falls back to static dots when the
// animation gate is off.
class TypingDotsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.mono_fg)
    }
    private val density = resources.displayMetrics.density
    private val dotRadius = 3f * density
    private val dotGap = 6f * density
    private var phase = 0f
    private var animator: ValueAnimator? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (paddingLeft + paddingRight + dotRadius * 6 + dotGap * 2).toInt()
        val h = (paddingTop + paddingBottom + dotRadius * 2).toInt()
        setMeasuredDimension(
            View.resolveSize(w, widthMeasureSpec),
            View.resolveSize(h, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val startX = paddingLeft + dotRadius
        val running = animator?.isRunning == true
        for (i in 0 until 3) {
            val cx = startX + i * (dotRadius * 2 + dotGap)
            val a = if (running) {
                val v = sin((phase + i * 0.28f) * 2f * Math.PI).toFloat()
                0.35f + 0.65f * ((v + 1f) / 2f)
            } else {
                0.5f
            }
            paint.alpha = (a * 255).toInt()
            canvas.drawCircle(cx, cy, dotRadius, paint)
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible && Anim.enabled(context)) start() else stop()
    }

    private fun start() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedFraction
                invalidate()
            }
            start()
        }
    }

    private fun stop() {
        animator?.cancel()
        animator = null
        invalidate()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }
}
