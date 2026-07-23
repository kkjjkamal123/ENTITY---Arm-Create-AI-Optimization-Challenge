package com.example.llama

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Every autosaved benchmark, newest first. Tap opens the saved run, long-press deletes.
//
// One activity in two modes rather than two screens: the detail view is the stored
// summary text plus the two actions the live result screen already offers, so a
// separate result activity would only re-render what was serialised at save time.
class BenchHistoryActivity : AppCompatActivity() {

    private var detailStem: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        Palette.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bench_history)
        Insets.pad(findViewById(android.R.id.content))
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        detailStem = intent.getStringExtra(EXTRA_STEM)
    }

    override fun onResume() {
        super.onResume()
        val stem = detailStem
        if (stem != null) renderDetail(stem) else renderList()
    }

    private fun show(vararg ids: Pair<Int, Boolean>) {
        ids.forEach { (id, visible) ->
            findViewById<View>(id).visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    private fun renderList() {
        val entries = BenchHistory.summaries(this)
        findViewById<TextView>(R.id.history_title).setText(R.string.history_title)
        findViewById<TextView>(R.id.history_count).text = entries.size.toString()
        show(
            R.id.detail_box to false,
            R.id.history_empty to entries.isEmpty(),
            R.id.history_list to entries.isNotEmpty(),
            R.id.history_clear to entries.isNotEmpty(),
        )
        findViewById<View>(R.id.history_clear).setOnClickListener { confirmClear() }

        val list = findViewById<LinearLayout>(R.id.history_list)
        list.removeAllViews()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val ink = Ui.fg(this)
        for (e in entries) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.btn_outline)
                setPadding(dp(12), dp(11), dp(12), dp(11))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
                setOnClickListener {
                    startActivity(
                        Intent(this@BenchHistoryActivity, BenchHistoryActivity::class.java)
                            .putExtra(EXTRA_STEM, e.stem)
                    )
                }
                setOnLongClickListener { confirmDelete(e.stem) { renderList() }; true }
            }
            val left = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            left.addView(TextView(this).apply {
                text = e.model
                textSize = 12.5f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(ink)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.MIDDLE
            })
            left.addView(TextView(this).apply {
                text = "${fmt.format(Date(e.ts))} · ${what(e)} · " +
                    if (e.charging) "charging" else "unplugged"
                textSize = 10f
                setTextColor(ink)
                setPadding(0, dp(3), 0, 0)
            })
            row.addView(left)
            val right = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(10) }
            }
            val sustained = e.type == BenchHistory.TYPE_SUSTAINED
            right.addView(TextView(this).apply {
                text = if (sustained) getString(R.string.history_sustained_tag) else signed(e.deltaPct)
                textSize = 14f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(ink)
                gravity = Gravity.END
            })
            right.addView(TextView(this).apply {
                setText(
                    if (sustained) R.string.history_sustained_caption
                    else R.string.history_delta_caption
                )
                textSize = 9f
                setTextColor(ink)
                gravity = Gravity.END
            })
            row.addView(right)
            list.addView(row)
        }
    }

    private fun what(e: BenchHistory.Summary) =
        if (e.type == BenchHistory.TYPE_SUSTAINED) "${e.durationMin} min sustained"
        else "${e.runs} run${if (e.runs == 1) "" else "s"} · 3-arm"

    private fun renderDetail(stem: String) {
        val summary = BenchHistory.summaries(this).firstOrNull { it.stem == stem }
        val text = BenchHistory.text(this, stem)
        if (summary == null || text == null) {
            Toast.makeText(this, R.string.history_load_failed, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        findViewById<TextView>(R.id.history_title).text = getString(R.string.history_title)
        findViewById<TextView>(R.id.history_count).text = ""
        show(
            R.id.history_empty to false,
            R.id.history_list to false,
            R.id.history_clear to false,
            R.id.detail_box to true,
        )
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        findViewById<TextView>(R.id.detail_meta).text =
            "${summary.model}\n${fmt.format(Date(summary.ts))} · ${what(summary)} · " +
                if (summary.charging) "charging" else "unplugged"
        findViewById<TextView>(R.id.detail_text).text = text

        findViewById<View>(R.id.detail_copy).setOnClickListener {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("ENTITY benchmark", text))
            Toast.makeText(this, R.string.copied_confirmation, Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.detail_export).setOnClickListener { exportCsv(stem) }
        findViewById<View>(R.id.detail_delete).setOnClickListener {
            confirmDelete(stem) { finish() }
        }
    }

    // The CSV was written at save time, so re-exporting is a copy - there is no result
    // to rebuild and nothing to lose if this activity dies behind the picker.
    private fun exportCsv(stem: String) {
        if (BenchHistory.csv(this, stem).isNullOrEmpty()) {
            Toast.makeText(this, R.string.history_load_failed, Toast.LENGTH_SHORT).show()
            return
        }
        pendingStem = stem
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/csv"
            putExtra(Intent.EXTRA_TITLE, "entity_bench_$stem.csv")
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_EXPORT_CSV)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingStem?.let { outState.putString(STATE_PENDING_STEM, it) }
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        pendingStem = savedInstanceState.getString(STATE_PENDING_STEM)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_EXPORT_CSV || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val csv = pendingStem?.let { BenchHistory.csv(this, it) }
        if (csv.isNullOrEmpty()) {
            Toast.makeText(this, R.string.history_load_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val ok = runCatching {
            contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) } != null
        }.getOrDefault(false)
        if (ok) pendingStem = null
        Toast.makeText(this, if (ok) "CSV exported" else "CSV export failed", Toast.LENGTH_SHORT).show()
    }

    private fun confirmDelete(stem: String, then: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(R.string.history_delete_confirm)
            .setPositiveButton(R.string.history_delete) { _, _ ->
                BenchHistory.delete(this, stem)
                Toast.makeText(this, R.string.history_deleted, Toast.LENGTH_SHORT).show()
                then()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setMessage(R.string.history_clear_confirm)
            .setPositiveButton(R.string.history_clear) { _, _ ->
                BenchHistory.clear(this)
                renderList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun dp(v: Int) = Ui.dp(this, v)

    private fun signed(v: Double) = (if (v >= 0) "+" else "") + String.format(Locale.US, "%.0f%%", v)

    private var pendingStem: String? = null

    companion object {
        const val EXTRA_STEM = "stem"
        private const val REQ_EXPORT_CSV = 4201
        private const val STATE_PENDING_STEM = "pending_stem"
    }
}
