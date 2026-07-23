package com.entity.bench

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Every autosaved result, newest first. Tap opens the saved result page; long-press
// deletes.
class HistoryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        Palette.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        Insets.pad(findViewById(android.R.id.content))
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val entries = ResultStore.summaries(this)
        findViewById<TextView>(R.id.history_count).text = entries.size.toString()
        findViewById<View>(R.id.history_empty).visibility =
            if (entries.isEmpty()) View.VISIBLE else View.GONE
        val list = findViewById<LinearLayout>(R.id.history_list)
        list.removeAllViews()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        for (e in entries) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.btn_outline)
                setPadding(Ui.dp(this@HistoryActivity, 12), Ui.dp(this@HistoryActivity, 11),
                    Ui.dp(this@HistoryActivity, 12), Ui.dp(this@HistoryActivity, 11))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = Ui.dp(this@HistoryActivity, 8) }
                setOnClickListener {
                    startActivity(Intent(this@HistoryActivity, ResultActivity::class.java)
                        .putExtra(ResultActivity.EXTRA_FILE, e.file))
                }
                setOnLongClickListener {
                    confirmDelete(e)
                    true
                }
            }
            val left = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            left.addView(TextView(this).apply {
                text = e.model
                textSize = 12.5f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(getColor(R.color.mono_fg))
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            })
            val what = if (e.type == BenchResult.TYPE_SUSTAINED)
                "${e.durationMin} min sustained" else "${e.runs} runs · 3-arm"
            left.addView(TextView(this).apply {
                text = "${fmt.format(Date(e.ts))} · $what · ${if (e.charging) "charging" else "unplugged"}"
                textSize = 10f
                setTextColor(getColor(R.color.mono_fg))
                setPadding(0, Ui.dp(this@HistoryActivity, 3), 0, 0)
            })
            row.addView(left)
            val right = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = Ui.dp(this@HistoryActivity, 10) }
            }
            right.addView(TextView(this).apply {
                text = if (e.type == BenchResult.TYPE_SUSTAINED) "SUST" else BenchExport.signed(e.deltaPct)
                textSize = 14f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(getColor(R.color.mono_fg))
                gravity = Gravity.END
            })
            right.addView(TextView(this).apply {
                text = if (e.type == BenchResult.TYPE_SUSTAINED) "no cooldown" else "decode vs naive"
                textSize = 9f
                setTextColor(getColor(R.color.mono_fg))
                gravity = Gravity.END
            })
            row.addView(right)
            list.addView(row)
        }
    }

    private fun confirmDelete(e: ResultStore.Summary) {
        AlertDialog.Builder(this)
            .setMessage(R.string.result_delete_confirm)
            .setPositiveButton(R.string.result_delete) { _, _ ->
                ResultStore.delete(this, e.file)
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
