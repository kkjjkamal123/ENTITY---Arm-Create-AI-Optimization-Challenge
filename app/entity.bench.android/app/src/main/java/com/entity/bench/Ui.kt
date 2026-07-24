package com.entity.bench

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView

// Tiny shared helpers for the code-built parts of the UI (grids, bars, list rows).
// Everything obeys the two-color rule: selection and emphasis are inversions.
object Ui {

    fun dp(a: Activity, v: Int) = (v * a.resources.displayMetrics.density).toInt()

    // Segmented option: selected = solid inversion, unselected = 1dp outline.
    fun seg(a: Activity, tv: TextView, selected: Boolean) {
        tv.setBackgroundResource(if (selected) R.drawable.bg_fill else R.drawable.bg_inner)
        tv.setTextColor(Palette.color(a, if (selected) R.attr.monoOnFill else R.attr.monoFg))
    }

    // Square check box for a title+description settings row: on = solid fill, off = 1dp
    // outline. Same vocabulary as seg(), but sized as an icon next to text rather than
    // filling a whole button, so a persistent on/off setting reads as a checkbox and not
    // as a fourth action button (see the Contribute row in SettingsActivity).
    fun check(box: View, on: Boolean) {
        box.setBackgroundResource(if (on) R.drawable.bg_fill else R.drawable.bg_inner)
    }

    // Two-column label/value grid row (device card, live telemetry).
    fun gridRow(a: Activity, grid: GridLayout, label: String, value: String, boldValue: Boolean = false) {
        fun cell(text: String, end: Boolean, bold: Boolean): TextView = TextView(a).apply {
            this.text = text
            typeface = if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
            textSize = 11f
            setTextColor(a.getColor(R.color.mono_fg))
            gravity = if (end) Gravity.END else Gravity.START
            layoutParams = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(GridLayout.UNDEFINED, 1f)
            ).apply { width = 0; topMargin = dp(a, 4) }
        }
        grid.addView(cell(label, false, false))
        grid.addView(cell(value, true, boldValue))
    }

    // Optimization chip: solid inversion when the lever is live on this device, dashed
    // outline when the silicon or the OS does not provide it. Same two-state vocabulary
    // as the KleidiAI badge, so the indicator reads as part of the app, not a bolt-on.
    fun optChip(a: Activity, grid: GridLayout, label: String, on: Boolean) {
        grid.addView(TextView(a).apply {
            text = label
            typeface = Typeface.create(Typeface.MONOSPACE, if (on) Typeface.BOLD else Typeface.NORMAL)
            textSize = 10f
            gravity = Gravity.CENTER
            setBackgroundResource(if (on) R.drawable.bg_fill else R.drawable.bg_dashed)
            setTextColor(a.getColor(if (on) R.color.mono_bg else R.color.mono_fg))
            setPadding(dp(a, 8), dp(a, 6), dp(a, 8), dp(a, 6))
            layoutParams = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED, 1f),
                GridLayout.spec(GridLayout.UNDEFINED, 1f)
            ).apply { width = 0; setMargins(dp(a, 2), dp(a, 4), dp(a, 2), 0) }
        })
    }

    // Horizontal bar: bordered track, hard fill, right-aligned value.
    fun bar(a: Activity, parent: LinearLayout, label: String, value: Double, max: Double, emphasize: Boolean) {
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(a, 3), 0, dp(a, 3))
        }
        row.addView(TextView(a).apply {
            text = label
            typeface = if (emphasize) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
            textSize = 10.5f
            setTextColor(a.getColor(R.color.mono_fg))
            layoutParams = LinearLayout.LayoutParams(dp(a, 76), LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        val frac = (value / max).toFloat().coerceIn(0.02f, 1f)
        val track = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.bg_inner)
            setPadding(dp(a, 2), dp(a, 2), dp(a, 2), dp(a, 2))
            layoutParams = LinearLayout.LayoutParams(0, dp(a, 16), 1f)
        }
        track.addView(View(a).apply {
            setBackgroundResource(R.drawable.bg_fill)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, frac)
        })
        track.addView(View(a).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f - frac)
        })
        row.addView(track)
        row.addView(TextView(a).apply {
            text = "%.1f t/s".format(value)
            typeface = if (emphasize) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
            textSize = 10.5f
            gravity = Gravity.END
            setTextColor(a.getColor(R.color.mono_fg))
            layoutParams = LinearLayout.LayoutParams(dp(a, 74), LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        parent.addView(row)
    }
}
