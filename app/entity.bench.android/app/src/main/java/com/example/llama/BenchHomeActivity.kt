package com.example.llama

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.ColorStateList
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
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.gguf.FileType
import com.arm.aichat.gguf.GgufMetadataReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream

// ENTITY Bench home: device under test, run configuration, last result and
// history. The benchmark itself runs in BenchmarkActivity; this screen loads
// the model, presets the run count and auto-starts it.
class BenchHomeActivity : AppCompatActivity() {

    private lateinit var engine: InferenceEngine
    private lateinit var prefs: SharedPreferences

    private lateinit var modelNameTv: TextView
    private lateinit var kleidiTv: TextView
    private lateinit var runBtn: TextView
    private lateinit var powerStateTv: TextView
    private lateinit var dutGrid: GridLayout

    private var selectedModel: File? = null
    private var selectedRuns = 3
    private var busy = false
    private var pulse: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bench_home)

        prefs = getSharedPreferences(Settings.PREFS, Context.MODE_PRIVATE)
        modelNameTv = findViewById(R.id.model_name)
        kleidiTv = findViewById(R.id.model_kleidi)
        runBtn = findViewById(R.id.btn_run)
        powerStateTv = findViewById(R.id.dut_power_state)
        dutGrid = findViewById(R.id.dut_grid)

        findViewById<View>(R.id.home_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.row_model).setOnClickListener { showModelPicker() }
        runBtn.setOnClickListener { onRun() }
        findViewById<View>(R.id.btn_open_results).setOnClickListener { openBenchmark(autoStart = false) }

        for ((id, n) in listOf(R.id.runs_1 to 1, R.id.runs_3 to 3, R.id.runs_5 to 5)) {
            findViewById<TextView>(id).setOnClickListener {
                selectedRuns = n
                styleRunButtons()
            }
        }
        styleRunButtons()
        buildArms()

        findViewById<TextView>(R.id.dut_name).text =
            "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"

        lifecycleScope.launch {
            engine = withContext(Dispatchers.Default) { AiChat.getInferenceEngine(applicationContext) }
            restoreSelectedModel()
        }

        val dot = findViewById<View>(R.id.dot_live)
        if (Anim.enabled(this)) {
            pulse = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.35f, 1f).apply {
                duration = 2400
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }
    }

    override fun onDestroy() {
        pulse?.cancel()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        Anim.setUserEnabled(prefs.getBoolean(Settings.KEY_ANIM, Settings.DEF_ANIM))
        refreshDeviceCard()
        renderHistory()
    }

    // ---- device under test ----

    private fun refreshDeviceCard() {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) > 0
        val tempC = (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0

        powerStateTv.text = if (plugged) "CHARGING - power metrics off" else "UNPLUGGED ✓"
        powerStateTv.setTextColor(getColor(if (plugged) R.color.bench_amber else R.color.bench_accent))

        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        val current = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val reporting = current != 0 && current != Int.MIN_VALUE

        val mem = ActivityManager.MemoryInfo().also {
            (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
        }
        val freeGb = mem.availMem / 1_073_741_824.0

        dutGrid.removeAllViews()
        gridRow("topology", topology())
        gridRow("abi", android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
        gridRow("temp / free RAM", "%.1f°C · %.1f GB".format(tempC, freeGb))
        gridRow("battery current", if (reporting) "reporting ✓" else "not reported",
            accent = reporting)
    }

    private fun topology(): String {
        val freqs = File("/sys/devices/system/cpu")
            .listFiles { f -> f.name.matches(Regex("cpu[0-9]+")) }
            ?.mapNotNull { f ->
                runCatching { File(f, "cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull()
            } ?: return "unknown"
        if (freqs.isEmpty()) return "unknown"
        val top = freqs.max()
        val big = freqs.count { it == top }
        val little = freqs.size - big
        val ghz = { khz: Long -> "%.1fGHz".format(khz / 1_000_000.0) }
        return if (little == 0) "$big× ${ghz(top)}"
        else "$big× ${ghz(top)} + $little× ${ghz(freqs.filter { it != top }.max())}"
    }

    private fun gridRow(label: String, value: String, accent: Boolean = false) {
        fun cell(text: String, end: Boolean, color: Int): TextView = TextView(this).apply {
            this.text = text
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setTextColor(color)
            gravity = if (end) Gravity.END else Gravity.START
            layoutParams = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(GridLayout.UNDEFINED, 1f)
            ).apply { width = 0; topMargin = dp(3) }
        }
        dutGrid.addView(cell(label, false, getColor(R.color.bench_muted)))
        dutGrid.addView(cell(value, true,
            getColor(if (accent) R.color.bench_accent else R.color.bench_text2)))
    }

    // ---- run configuration ----

    private fun styleRunButtons() {
        for ((id, n) in listOf(R.id.runs_1 to 1, R.id.runs_3 to 3, R.id.runs_5 to 5)) {
            val tv = findViewById<TextView>(id)
            val on = n == selectedRuns
            tv.setBackgroundResource(if (on) R.drawable.bg_bench_run else R.drawable.bg_bench_inner)
            tv.setTextColor(getColor(if (on) R.color.bench_on_accent else R.color.bench_sub))
        }
    }

    private fun buildArms() {
        val arms = findViewById<LinearLayout>(R.id.arms_list)
        val rows = listOf(
            "naïve" to "8 threads, all cores - the default",
            "threads-only" to "Auto's count, pinning off (= llama.cpp -t N)",
            "Auto" to "ranked fast cores, pinned",
        )
        for ((name, desc) in rows) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(3), 0, dp(3))
            }
            row.addView(TextView(this).apply {
                text = "✓"
                textSize = 11f
                setTextColor(getColor(R.color.bench_on_accent))
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_bench_dot)
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
            })
            row.addView(TextView(this).apply {
                text = name
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 12f
                setTextColor(getColor(R.color.bench_text))
                layoutParams = LinearLayout.LayoutParams(dp(110), LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { marginStart = dp(10) }
            })
            row.addView(TextView(this).apply {
                text = desc
                textSize = 11f
                setTextColor(getColor(R.color.bench_muted))
            })
            arms.addView(row)
        }
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
        if (busy) return
        val models = scanModels()
        val labels = models.map {
            val b = it.length()
            val size = if (b >= 1_000_000_000L) "%.2f GB".format(b / 1e9) else "%.0f MB".format(b / 1e6)
            "${it.name}\n$size"
        } + "Import from device…"
        AlertDialog.Builder(this)
            .setTitle("Select a model")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < models.size) selectModel(models[which]) else getContent.launch(arrayOf("*/*"))
            }
            .show()
    }

    private val getContent = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { importModel(it) } }

    private fun importModel(uri: Uri) {
        setBusy("Importing…")
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
                clearBusy()
                result.onSuccess { selectModel(it) }
                    .onFailure { Toast.makeText(this@BenchHomeActivity, "Import failed: ${it.message}", Toast.LENGTH_LONG).show() }
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
        val name = prefs.getString(KEY_LAST_BENCH_MODEL, null) ?: return
        scanModels().firstOrNull { it.nameWithoutExtension == name }?.let { selectModel(it) }
    }

    private fun selectModel(model: File) {
        selectedModel = model
        modelNameTv.text = model.nameWithoutExtension
        prefs.edit().putString(KEY_LAST_BENCH_MODEL, model.nameWithoutExtension).apply()
        lifecycleScope.launch(Dispatchers.IO) {
            val meta = runCatching {
                FileInputStream(model).use { GgufMetadataReader.create().readStructuredMetadata(it) }
            }.getOrNull()
            val ft = FileType.fromCode(meta?.architecture?.fileType)
            withContext(Dispatchers.Main) {
                kleidiTv.visibility = View.VISIBLE
                if (ft.kleidiAiAccelerated) {
                    kleidiTv.text = "KleidiAI ✓"
                    kleidiTv.setTextColor(getColor(R.color.bench_accent))
                } else {
                    kleidiTv.text = "no KleidiAI"
                    kleidiTv.setTextColor(getColor(R.color.bench_amber))
                }
            }
        }
    }

    // ---- run ----

    private fun onRun() {
        if (busy) return
        val model = selectedModel ?: run { showModelPicker(); return }
        setBusy("Loading ${model.nameWithoutExtension}…")
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val state = engine.state.value
                if (state is InferenceEngine.State.ModelReady || state is InferenceEngine.State.Error) {
                    runCatching { engine.cleanUp() }
                }
                val v = Settings.load(prefs)
                val ctx = if (v.auto) adaptiveContext(model) else v.ctx
                val threads = when {
                    v.efficiency -> 2
                    v.auto -> 0
                    else -> v.threads
                }
                prefs.edit().putInt(Settings.KEY_ACTIVE_CTX, ctx).apply()
                engine.applyConfig(ctx, threads, v.temp, v.topK, v.topP)
                engine.loadModel(model.path)
            }
            withContext(Dispatchers.Main) {
                clearBusy()
                result.onSuccess { openBenchmark(autoStart = true) }
                    .onFailure { Toast.makeText(this@BenchHomeActivity, "Failed to load: ${it.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun adaptiveContext(model: File): Int {
        val sizeGb = model.length() / 1_000_000_000.0
        val mem = ActivityManager.MemoryInfo().also {
            (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
        }
        val freeGb = mem.availMem / 1_073_741_824.0
        return when {
            sizeGb < 1.6 -> if (freeGb > 3.0) 8192 else 4096
            else -> if (freeGb > 2.2) 4096 else 2048
        }
    }

    private fun openBenchmark(autoStart: Boolean) {
        val i = Intent(this, BenchmarkActivity::class.java)
            .putExtra(BenchmarkActivity.EXTRA_MODEL, selectedModel?.nameWithoutExtension ?: "Loaded model")
            .putExtra(BenchmarkActivity.EXTRA_RUNS, selectedRuns)
            .putExtra(BenchmarkActivity.EXTRA_AUTOSTART, autoStart)
        startActivity(i)
    }

    private fun setBusy(label: String) {
        busy = true
        runBtn.text = label
        runBtn.setBackgroundResource(R.drawable.bg_bench_inner)
        runBtn.setTextColor(getColor(R.color.bench_sub))
    }

    private fun clearBusy() {
        busy = false
        runBtn.text = getString(R.string.bench_run_cta)
        runBtn.setBackgroundResource(R.drawable.bg_bench_run)
        runBtn.setTextColor(getColor(R.color.bench_on_accent))
    }

    // ---- last result and history ----

    private fun renderHistory() {
        val file = File(filesDir, BenchmarkActivity.HISTORY_FILE)
        val entries = runCatching {
            file.readLines().mapNotNull { l -> runCatching { JSONObject(l) }.getOrNull() }
        }.getOrDefault(emptyList()).reversed()

        val resultCard = findViewById<View>(R.id.card_result)
        val historyCard = findViewById<View>(R.id.card_history)
        if (entries.isEmpty()) {
            resultCard.visibility = View.GONE
            historyCard.visibility = View.GONE
            return
        }

        val last = entries.first()
        resultCard.visibility = View.VISIBLE
        val naive = last.optDouble("naive", 0.0)
        val threads = last.optDouble("threads", 0.0)
        val auto = last.optDouble("auto", 0.0)
        val threadsPct = if (naive > 0) (threads / naive - 1) * 100 else 0.0
        val pinPct = if (threads > 0) (auto / threads - 1) * 100 else 0.0

        findViewById<TextView>(R.id.result_meta).text = "${last.optInt("runs")}-run median"
        findViewById<TextView>(R.id.result_headline).text =
            "Decode %+.0f%% · threads earn it".format(if (naive > 0) (auto / naive - 1) * 100 else 0.0)
        findViewById<TextView>(R.id.result_sub).text =
            "Pinning adds %+.0f%%. The gain is the thread count (%+.0f%%), keeping work off the little cores.".format(pinPct, threadsPct)

        val bars = findViewById<LinearLayout>(R.id.result_bars)
        bars.removeAllViews()
        val mx = maxOf(naive, threads, auto, 0.001)
        bar(bars, "naïve", naive, mx, R.color.bench_bar_dim)
        bar(bars, "threads", threads, mx, R.color.bench_bar_mid)
        bar(bars, "Auto", auto, mx, R.color.bench_accent)

        historyCard.visibility = View.VISIBLE
        val list = findViewById<LinearLayout>(R.id.history_list)
        list.removeAllViews()
        for (e in entries.take(5)) {
            val n = e.optDouble("naive", 0.0)
            val a = e.optDouble("auto", 0.0)
            val delta = if (n > 0) (a / n - 1) * 100 else 0.0
            val rel = DateUtils.getRelativeTimeSpanString(
                e.optLong("ts"), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            historyRow(list, e.optString("model"),
                "${e.optInt("runs")} runs · ${if (e.optBoolean("charging")) "charging" else "unplugged"} · $rel",
                "%+.0f%%".format(delta))
        }
    }

    private fun bar(parent: LinearLayout, label: String, value: Double, max: Double, colorRes: Int) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, dp(3))
        }
        row.addView(TextView(this).apply {
            text = label
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 10.5f
            setTextColor(getColor(R.color.bench_sub))
            layoutParams = LinearLayout.LayoutParams(dp(72), LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        val frac = (value / max).toFloat().coerceIn(0.02f, 1f)
        val track = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.bg_bench_inner)
            layoutParams = LinearLayout.LayoutParams(0, dp(16), 1f)
        }
        track.addView(View(this).apply {
            setBackgroundResource(R.drawable.bg_bench_run)
            backgroundTintList = ColorStateList.valueOf(getColor(colorRes))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, frac)
        })
        track.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f - frac)
        })
        row.addView(track)
        row.addView(TextView(this).apply {
            text = "%.1f t/s".format(value)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            gravity = Gravity.END
            setTextColor(getColor(R.color.bench_text2))
            layoutParams = LinearLayout.LayoutParams(dp(74), LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        parent.addView(row)
    }

    private fun historyRow(parent: LinearLayout, model: String, meta: String, delta: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_bench_card)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        left.addView(TextView(this).apply {
            text = model
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12.5f
            setTextColor(getColor(R.color.bench_text))
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        left.addView(TextView(this).apply {
            text = meta
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 10.5f
            setTextColor(getColor(R.color.bench_muted))
            setPadding(0, dp(3), 0, 0)
        })
        row.addView(left)
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        right.addView(TextView(this).apply {
            text = delta
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 13f
            setTextColor(getColor(R.color.bench_accent))
            gravity = Gravity.END
        })
        right.addView(TextView(this).apply {
            text = "decode vs naïve"
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 10f
            setTextColor(getColor(R.color.bench_muted))
            gravity = Gravity.END
        })
        row.addView(right)
        parent.addView(row)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val KEY_LAST_BENCH_MODEL = "bench_last_model"
    }
}
