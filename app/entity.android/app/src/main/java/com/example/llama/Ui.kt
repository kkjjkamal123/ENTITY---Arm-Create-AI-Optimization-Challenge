package com.example.llama

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat

// Tiny shared helpers for the code-built parts of the mono UI.
// Everything obeys the two-color rule: selection and emphasis are inversions.
object Ui {

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun fg(ctx: Context) = Palette.color(ctx, R.attr.monoFg)
    fun bg(ctx: Context) = Palette.color(ctx, R.attr.monoBg)
    fun dim(ctx: Context) = Palette.color(ctx, R.attr.monoDim)
    fun onFill(ctx: Context) = Palette.color(ctx, R.attr.monoOnFill)
    fun danger(ctx: Context) = Palette.color(ctx, R.attr.monoDanger)

    // Segmented option: selected = solid inversion, unselected = 1dp outline.
    fun seg(tv: TextView, selected: Boolean) {
        tv.setBackgroundResource(if (selected) R.drawable.bg_fill else R.drawable.bg_inner)
        tv.setTextColor(if (selected) bg(tv.context) else fg(tv.context))
    }

    // Square check box: on = solid fill, off = 1dp outline.
    fun check(box: View, on: Boolean) {
        box.setBackgroundResource(if (on) R.drawable.bg_fill else R.drawable.bg_inner)
    }
}
