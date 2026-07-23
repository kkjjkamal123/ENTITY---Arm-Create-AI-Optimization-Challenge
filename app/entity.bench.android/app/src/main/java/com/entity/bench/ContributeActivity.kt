package com.entity.bench

import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Choose which saved results to contribute, and send them.
 *
 * The automatic path only ever covers runs finished *after* the toggle was switched on,
 * which leaves every earlier result stranded with no way to share it. This screen is that
 * way: every saved run, individually selectable, with "all" and "not sent yet" shortcuts.
 *
 * Nothing here sends silently. Selection is explicit, the count is shown before the send,
 * and a row that has already been contributed says so and is unselected by default - so
 * the obvious action never re-sends the same run.
 */
class ContributeActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var empty: View

    private var rows: List<ResultStore.Summary> = emptyList()
    private val selected = linkedSetOf<String>()
    private var sending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        Palette.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contribute)
        Insets.pad(findViewById(android.R.id.content))

        list = findViewById(R.id.result_list)
        status = findViewById(R.id.send_status)
        empty = findViewById(R.id.empty)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_select_all).setOnClickListener {
            // Toggle: a second tap clears, so "select all" is not a one-way door.
            if (selected.size == rows.size) selected.clear()
            else { selected.clear(); rows.forEach { selected += it.file } }
            render()
        }
        findViewById<View>(R.id.btn_select_unsent).setOnClickListener {
            selected.clear()
            rows.filterNot { ResultUploader.isSent(this, it.file) }.forEach { selected += it.file }
            render()
        }
        findViewById<View>(R.id.btn_send).setOnClickListener { send() }
    }

    override fun onResume() {
        super.onResume()
        rows = ResultStore.summaries(this)
        // Default selection is the useful one: everything not already contributed.
        if (selected.isEmpty()) {
            rows.filterNot { ResultUploader.isSent(this, it.file) }.forEach { selected += it.file }
        }
        render()
    }

    private fun render() {
        list.removeAllViews()
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE

        for (r in rows) {
            val sent = ResultUploader.isSent(this, r.file)
            val on = r.file in selected
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_box)
                setPadding(Ui.dp(this@ContributeActivity, 14), Ui.dp(this@ContributeActivity, 12),
                           Ui.dp(this@ContributeActivity, 14), Ui.dp(this@ContributeActivity, 12))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = Ui.dp(this@ContributeActivity, 8) }
                setOnClickListener {
                    if (on) selected -= r.file else selected += r.file
                    render()
                }
            }

            // Title row: a check box on the left, the state pill on the right.
            val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            top.addView(TextView(this).apply {
                text = if (on) "[x]" else "[ ]"
                setTextColor(Palette.color(this@ContributeActivity, R.attr.monoFg))
                textSize = 13f
                typeface = android.graphics.Typeface.MONOSPACE
            })
            top.addView(TextView(this).apply {
                text = r.model
                setTextColor(Palette.color(this@ContributeActivity, R.attr.monoFg))
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginStart = Ui.dp(this@ContributeActivity, 8) }
            })
            if (sent) top.addView(TextView(this).apply {
                text = getString(R.string.contribute_sent)
                setBackgroundResource(R.drawable.bg_dashed)
                setTextColor(Palette.color(this@ContributeActivity, R.attr.monoDim))
                textSize = 9f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(Ui.dp(this@ContributeActivity, 7), Ui.dp(this@ContributeActivity, 2),
                           Ui.dp(this@ContributeActivity, 7), Ui.dp(this@ContributeActivity, 2))
            })
            card.addView(top)

            // Facts line, same order on every card so the list scans vertically.
            val when_ = DateUtils.getRelativeTimeSpanString(
                r.ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
            )
            val kind = if (r.type == BenchResult.TYPE_SUSTAINED) "${r.durationMin} min sustained"
                       else "${r.runs} runs"
            card.addView(TextView(this).apply {
                text = "$when_ · $kind · ${"%.1f".format(r.autoTg)} tok/s auto"
                setTextColor(Palette.color(this@ContributeActivity, R.attr.monoDim))
                textSize = 10f
                typeface = android.graphics.Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = Ui.dp(this@ContributeActivity, 6) }
            })

            // The honesty line. A charging run cannot carry a power or tok/W claim, and the
            // contributor should know that before sending rather than discover it later.
            card.addView(TextView(this).apply {
                text = getString(
                    if (r.charging) R.string.contribute_power_invalid
                    else R.string.contribute_power_valid
                )
                setTextColor(Palette.color(this@ContributeActivity, R.attr.monoDim))
                textSize = 9.5f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = Ui.dp(this@ContributeActivity, 4) }
            })

            list.addView(card)
        }

        val sentCount = rows.count { ResultUploader.isSent(this, it.file) }
        status.text = getString(R.string.contribute_status, selected.size, sentCount)
        findViewById<TextView>(R.id.btn_send).apply {
            isEnabled = selected.isNotEmpty() && !sending && ResultUploader.configured
            alpha = if (isEnabled) 1f else 0.45f
        }
    }

    private fun send() {
        if (!ResultUploader.configured) {
            Toast.makeText(this, R.string.contribute_unconfigured, Toast.LENGTH_LONG).show(); return
        }
        val files = selected.toList()
        if (files.isEmpty()) return

        // Sending is the one irreversible step on this screen - a row cannot be recalled
        // from the dataset - so it is confirmed, with the count and what it means.
        AlertDialog.Builder(this)
            .setTitle(R.string.contribute_send)
            .setMessage(getString(R.string.contribute_confirm, files.size))
            .setPositiveButton(R.string.contribute_send) { _, _ -> doSend(files) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun doSend(files: List<String>) {
        sending = true
        val btn = findViewById<TextView>(R.id.btn_send)
        lifecycleScope.launch {
            var ok = 0
            for ((i, f) in files.withIndex()) {
                btn.text = getString(R.string.contribute_sending, i + 1, files.size)
                render()
                val sent = withContext(Dispatchers.IO) {
                    runCatching { ResultUploader.uploadSaved(this@ContributeActivity, f) }
                        .getOrDefault(false)
                }
                if (sent) { ok++; selected -= f }
            }
            sending = false
            btn.text = getString(R.string.contribute_send)
            render()
            val failed = files.size - ok
            Toast.makeText(
                this@ContributeActivity,
                if (failed == 0) getString(R.string.contribute_done, ok)
                else getString(R.string.contribute_partial, ok, failed),
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
