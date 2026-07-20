package com.entity.bench

import android.app.ActivityManager
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.arm.aichat.gguf.FileType
import com.arm.aichat.gguf.GgufMetadataReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

// Home: device under test, run configuration, last result, history. The benchmark
// itself runs in RunActivity; every finished run is autosaved and opens as its own
// result page.
class BenchHomeActivity : AppCompatActivity() {

    private lateinit var modelNameTv: TextView
    private lateinit var kleidiTv: TextView
    private lateinit var powerStateTv: TextView
    private lateinit var dutGrid: GridLayout

    private var selectedModel: File? = null
    private var mode = Prefs.MODE_ABLATION
    private var runs = 3
    private var durationMin = 5
    private var effArm = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bench_home)

        modelNameTv = findViewById(R.id.model_name)
        kleidiTv = findViewById(R.id.model_kleidi)
        powerStateTv = findViewById(R.id.dut_power_state)
        dutGrid = findViewById(R.id.dut_grid)

        val p = Prefs.get(this)
        mode = p.getInt(Prefs.KEY_MODE, Prefs.MODE_ABLATION)
        runs = p.getInt(Prefs.KEY_RUNS, 3)
        durationMin = p.getInt(Prefs.KEY_DURATION, 5)
        effArm = p.getBoolean(Prefs.KEY_EFF_ARM, false)

        findViewById<View>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.row_model).setOnClickListener { showModelPicker() }
        findViewById<View>(R.id.btn_run).setOnClickListener { onRun() }
        findViewById<View>(R.id.btn_all_results).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        findViewById<TextView>(R.id.mode_ablation).setOnClickListener { setMode(Prefs.MODE_ABLATION) }
        findViewById<TextView>(R.id.mode_sustained).setOnClickListener { setMode(Prefs.MODE_SUSTAINED) }
        findViewById<TextView>(R.id.mode_sweep).setOnClickListener { setMode(Prefs.MODE_SWEEP) }
        for ((id, n) in listOf(R.id.runs_1 to 1, R.id.runs_3 to 3, R.id.runs_5 to 5)) {
            findViewById<TextView>(id).setOnClickListener {
                runs = n
                Prefs.get(this).edit().putInt(Prefs.KEY_RUNS, n).apply()
                styleConfig()
            }
        }
        for ((id, n) in listOf(R.id.dur_2 to 2, R.id.dur_5 to 5, R.id.dur_10 to 10)) {
            findViewById<TextView>(id).setOnClickListener {
                durationMin = n
                Prefs.get(this).edit().putInt(Prefs.KEY_DURATION, n).apply()
                styleConfig()
            }
        }
        findViewById<View>(R.id.row_eff_arm).setOnClickListener {
            effArm = !effArm
            Prefs.get(this).edit().putBoolean(Prefs.KEY_EFF_ARM, effArm).apply()
            styleConfig()
        }
        styleConfig()

        findViewById<TextView>(R.id.dut_name).text =
            "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
        findViewById<TextView>(R.id.footer).text =
            "v${BuildConfig.VERSION_NAME} · arm64 · no network · results stay on this phone"

        restoreSelectedModel()
    }

    override fun onResume() {
        super.onResume()
        refreshDeviceCard()
        renderResults()
    }

    // ---- run configuration ----

    private fun setMode(m: Int) {
        mode = m
        Prefs.get(this).edit().putInt(Prefs.KEY_MODE, m).apply()
        styleConfig()
    }

    private fun styleConfig() {
        val ablation = mode == Prefs.MODE_ABLATION
        val sustained = mode == Prefs.MODE_SUSTAINED
        val sweep = mode == Prefs.MODE_SWEEP
        Ui.seg(this, findViewById(R.id.mode_ablation), ablation)
        Ui.seg(this, findViewById(R.id.mode_sustained), sustained)
        Ui.seg(this, findViewById(R.id.mode_sweep), sweep)
        // The sweep reuses the runs-per-config selector; only the efficiency arm is
        // ablation-only, so the group shows for both and its checkbox hides for a sweep.
        findViewById<View>(R.id.group_ablation).visibility = if (sustained) View.GONE else View.VISIBLE
        findViewById<View>(R.id.group_sustained).visibility = if (sustained) View.VISIBLE else View.GONE
        findViewById<View>(R.id.eff_arm_box).visibility = if (ablation) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.workload_note).setText(
            when {
                sustained -> R.string.workload_sustained
                sweep -> R.string.workload_sweep
                else -> R.string.workload
            }
        )

        for ((id, n) in listOf(R.id.runs_1 to 1, R.id.runs_3 to 3, R.id.runs_5 to 5)) {
            Ui.seg(this, findViewById(id), n == runs)
        }
        for ((id, n) in listOf(R.id.dur_2 to 2, R.id.dur_5 to 5, R.id.dur_10 to 10)) {
            Ui.seg(this, findViewById(id), n == durationMin)
        }
        findViewById<View>(R.id.eff_arm_box).setBackgroundResource(
            if (effArm) R.drawable.bg_fill else R.drawable.bg_inner)
    }

    // ---- device under test ----

    private fun refreshDeviceCard() {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) > 0
        val tempC = (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0

        powerStateTv.text = getString(if (plugged) R.string.dut_charging else R.string.dut_unplugged)

        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        val current = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val reporting = current != 0 && current != Int.MIN_VALUE

        val mem = ActivityManager.MemoryInfo().also {
            (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
        }
        val freeGb = mem.availMem / 1_073_741_824.0

        dutGrid.removeAllViews()
        Ui.gridRow(this, dutGrid, "topology", topology())
        Ui.gridRow(this, dutGrid, "abi", android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
        Ui.gridRow(this, dutGrid, "temp / free RAM", "%.1fC · %.1f GB".format(tempC, freeGb))
        Ui.gridRow(this, dutGrid, "battery current", if (reporting) "reporting" else "not reported",
            boldValue = reporting)
    }

    private fun topology(): String {
        val freqs = DeviceInfo.maxFreqsKhz().filter { it > 0 }
        if (freqs.isEmpty()) return "unknown"
        val top = freqs.max()
        val big = freqs.count { it == top }
        val little = freqs.size - big
        val ghz = { khz: Long -> "%.1fGHz".format(khz / 1_000_000.0) }
        return if (little == 0) "${big}x ${ghz(top)}"
        else "${big}x ${ghz(top)} + ${little}x ${ghz(freqs.filter { it != top }.max())}"
    }

    // ---- model selection ----

    private fun modelDirs() =
        listOfNotNull(getExternalFilesDir("models"), File(filesDir, "models"))
            .onEach { if (!it.exists()) it.mkdirs() }

    private fun scanModels(): List<File> =
        modelDirs()
            .flatMap { it.listFiles { f -> f.extension == "gguf" }?.toList() ?: emptyList() }
            .distinctBy { it.name }
            .sortedBy { it.name }

    private fun showModelPicker() {
        val models = scanModels()
        val labels = models.map {
            val b = it.length()
            val size = if (b >= 1_000_000_000L) "%.2f GB".format(b / 1e9) else "%.0f MB".format(b / 1e6)
            "${it.name}\n$size"
        } + getString(R.string.model_import)
        AlertDialog.Builder(this)
            .setTitle(R.string.model_picker_title)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < models.size) selectModel(models[which]) else getContent.launch(arrayOf("*/*"))
            }
            .show()
    }

    private val getContent = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { importModel(it) } }

    private fun importModel(uri: Uri) {
        modelNameTv.text = getString(R.string.model_importing)
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val target = File(modelDirs().first(), pickedFileName(uri))
                if (!target.exists() || target.length() == 0L) {
                    val input = contentResolver.openInputStream(uri)
                        ?: error("Can't read that file. Pick the .gguf again from your storage.")
                    input.use { ins -> target.outputStream().use { ins.copyTo(it) } }
                }
                target
            }
            withContext(Dispatchers.Main) {
                result.onSuccess { selectModel(it) }
                    .onFailure {
                        modelNameTv.setText(R.string.model_none)
                        Toast.makeText(this@BenchHomeActivity, "Import failed: ${it.message}", Toast.LENGTH_LONG).show()
                    }
            }
        }
    }

    private fun pickedFileName(uri: Uri): String {
        var name: String? = null
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) name = c.getString(i)
                }
            }
        }
        var clean = (name ?: "model-${System.currentTimeMillis()}").substringAfterLast('/').trim()
        if (!clean.endsWith(".gguf", ignoreCase = true)) clean += ".gguf"
        return clean
    }

    private fun restoreSelectedModel() {
        val name = Prefs.get(this).getString(Prefs.KEY_MODEL, null) ?: return
        scanModels().firstOrNull { it.nameWithoutExtension == name }?.let { selectModel(it) }
    }

    private fun selectModel(model: File) {
        selectedModel = model
        modelNameTv.text = model.nameWithoutExtension
        Prefs.get(this).edit().putString(Prefs.KEY_MODEL, model.nameWithoutExtension).apply()
        lifecycleScope.launch(Dispatchers.IO) {
            val meta = runCatching {
                FileInputStream(model).use { GgufMetadataReader.create().readStructuredMetadata(it) }
            }.getOrNull()
            val ft = FileType.fromCode(meta?.architecture?.fileType)
            withContext(Dispatchers.Main) {
                kleidiTv.visibility = View.VISIBLE
                kleidiTv.text = if (ft.kleidiAiAccelerated) "KLEIDIAI" else "NO KLEIDIAI"
            }
        }
    }

    // ---- run ----

    private fun onRun() {
        val model = selectedModel ?: run { showModelPicker(); return }
        val start = {
            startActivity(
                Intent(this, RunActivity::class.java)
                    .putExtra(RunActivity.EXTRA_MODEL_PATH, model.path)
                    .putExtra(RunActivity.EXTRA_MODE, mode)
                    .putExtra(RunActivity.EXTRA_RUNS, runs)
                    .putExtra(RunActivity.EXTRA_DURATION_MIN, durationMin)
                    .putExtra(RunActivity.EXTRA_EFF_ARM, effArm)
            )
        }
        // A sweep is every width times both placements times the run count, each with a
        // full cooldown. That is a long unattended session, so say so before starting it
        // rather than let someone discover it twenty minutes in.
        if (mode == Prefs.MODE_SWEEP) {
            val counts = DeviceInfo.sweepThreadCounts(DeviceInfo.maxFreqsKhz())
            val passes = counts.size * 2 * runs
            AlertDialog.Builder(this)
                .setMessage(
                    "Sweep: ${counts.joinToString(", ")} threads, each pinned and unpinned, " +
                    "$runs run${if (runs == 1) "" else "s"} each.\n\n" +
                    "$passes passes with a cooldown before every one - budget roughly " +
                    "${passes * 75 / 60} minutes. Keep the phone unplugged, still and cool."
                )
                .setPositiveButton(R.string.run_cta) { _, _ -> start() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            start()
        }
    }

    // ---- last result and history ----

    private fun renderResults() {
        val entries = ResultStore.summaries(this)
        val resultCard = findViewById<View>(R.id.card_result)
        val historyCard = findViewById<View>(R.id.card_history)
        if (entries.isEmpty()) {
            resultCard.visibility = View.GONE
            historyCard.visibility = View.GONE
            return
        }

        val last = entries.first()
        resultCard.visibility = View.VISIBLE
        val bars = findViewById<LinearLayout>(R.id.result_bars)
        bars.removeAllViews()
        val headline = findViewById<TextView>(R.id.result_headline)
        val sub = findViewById<TextView>(R.id.result_sub)

        if (last.type == BenchResult.TYPE_SUSTAINED) {
            findViewById<TextView>(R.id.result_meta).text = "${last.durationMin} min sustained"
            headline.text = "THREADS-ONLY VS AUTO"
            sub.text = "No cooldown between passes - open the full result for the per-pass decode trend."
            val mx = maxOf(last.threadsTg, last.autoTg, 0.001)
            Ui.bar(this, bars, "threads", last.threadsTg, mx, emphasize = false)
            Ui.bar(this, bars, "auto", last.autoTg, mx, emphasize = true)
        } else if (last.type == BenchResult.TYPE_SWEEP) {
            findViewById<TextView>(R.id.result_meta).text = "${last.runs}-run sweep"
            headline.text = last.best.ifEmpty { "THREAD SWEEP" }.uppercase()
            sub.text = "Fastest configuration measured on this device - open the full result for every row."
            Ui.bar(this, bars, "best", last.autoTg, maxOf(last.autoTg, 0.001), emphasize = true)
        } else {
            findViewById<TextView>(R.id.result_meta).text = "${last.runs}-run median"
            val threadsPct = if (last.naiveTg > 0) (last.threadsTg / last.naiveTg - 1) * 100 else 0.0
            val pinPct = if (last.threadsTg > 0) (last.autoTg / last.threadsTg - 1) * 100 else 0.0
            headline.text = "DECODE ${BenchExport.signed(last.deltaPct)} VS NAIVE"
            sub.text = "Thread count alone earns ${BenchExport.signed(threadsPct)}; pinning adds ${BenchExport.signed(pinPct)} on top."
            val mx = maxOf(last.naiveTg, last.threadsTg, last.autoTg, 0.001)
            Ui.bar(this, bars, "naive", last.naiveTg, mx, emphasize = false)
            Ui.bar(this, bars, "threads", last.threadsTg, mx, emphasize = false)
            Ui.bar(this, bars, "auto", last.autoTg, mx, emphasize = true)
        }

        // The whole point of this button: it opens the SAVED result page, never a
        // fresh benchmark screen.
        findViewById<View>(R.id.btn_open_result).setOnClickListener {
            startActivity(Intent(this, ResultActivity::class.java)
                .putExtra(ResultActivity.EXTRA_FILE, last.file))
        }

        historyCard.visibility = View.VISIBLE
        val list = findViewById<LinearLayout>(R.id.history_list)
        list.removeAllViews()
        for (e in entries.take(5)) {
            addHistoryRow(list, e)
        }
        findViewById<TextView>(R.id.btn_all_results).text =
            "${getString(R.string.history_all)} (${entries.size})"
    }

    private fun addHistoryRow(parent: LinearLayout, e: ResultStore.Summary) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.btn_outline)
            setPadding(Ui.dp(this@BenchHomeActivity, 12), Ui.dp(this@BenchHomeActivity, 10),
                Ui.dp(this@BenchHomeActivity, 12), Ui.dp(this@BenchHomeActivity, 10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Ui.dp(this@BenchHomeActivity, 8) }
            setOnClickListener {
                startActivity(Intent(this@BenchHomeActivity, ResultActivity::class.java)
                    .putExtra(ResultActivity.EXTRA_FILE, e.file))
            }
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        left.addView(TextView(this).apply {
            text = e.model
            textSize = 12f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.mono_fg))
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        val rel = DateUtils.getRelativeTimeSpanString(e.ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        val what = when (e.type) {
            BenchResult.TYPE_SUSTAINED -> "${e.durationMin} min sustained"
            BenchResult.TYPE_SWEEP -> "${e.runs}-run sweep"
            else -> "${e.runs} runs"
        }
        left.addView(TextView(this).apply {
            text = "$what · ${if (e.charging) "charging" else "unplugged"} · $rel"
            textSize = 10f
            setTextColor(getColor(R.color.mono_fg))
            setPadding(0, Ui.dp(this@BenchHomeActivity, 3), 0, 0)
        })
        row.addView(left)
        row.addView(TextView(this).apply {
            text = when (e.type) {
                BenchResult.TYPE_SUSTAINED -> "SUST"
                BenchResult.TYPE_SWEEP -> BenchExport.fmt(e.autoTg)
                else -> BenchExport.signed(e.deltaPct)
            }
            textSize = 14f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.mono_fg))
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = Ui.dp(this@BenchHomeActivity, 10) }
        })
        parent.addView(row)
    }
}
