package com.entity.bench

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.arm.aichat.AiChat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// The live benchmark screen. No configuration here - the run starts immediately with
// what the home screen chose, and when it finishes the SAVED result page opens.
class RunActivity : AppCompatActivity() {

    private lateinit var statusTv: TextView
    private lateinit var pctTv: TextView
    private lateinit var elapsedTv: TextView
    private lateinit var fill: View
    private lateinit var rest: View
    private lateinit var liveGrid: GridLayout

    private var job: Job? = null
    private var startedAt = 0L

    private val ticker = object : Runnable {
        override fun run() {
            val s = (SystemClock.elapsedRealtime() - startedAt) / 1000
            elapsedTv.text = "%d:%02d".format(s / 60, s % 60)
            elapsedTv.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Palette.apply(this)
        super.onCreate(savedInstanceState)
        // A recreated instance has lost the in-flight run; go back to home rather
        // than pretend to be benchmarking.
        if (savedInstanceState != null) {
            finish()
            return
        }
        setContentView(R.layout.activity_run)
        Insets.pad(findViewById(android.R.id.content))
        // Honour the Settings toggle rather than forcing it: default is on, because a
        // sustained run outlasts most lock timeouts and a locked screen ends the run.
        if (Prefs.get(this).getBoolean(Prefs.KEY_KEEP_ON, Prefs.DEF_KEEP_ON)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        statusTv = findViewById(R.id.run_status)
        pctTv = findViewById(R.id.progress_pct)
        elapsedTv = findViewById(R.id.run_elapsed)
        fill = findViewById(R.id.progress_fill)
        rest = findViewById(R.id.progress_rest)
        liveGrid = findViewById(R.id.live_grid)

        val modelPath = intent.getStringExtra(EXTRA_MODEL_PATH) ?: run { finish(); return }
        val model = File(modelPath)
        findViewById<TextView>(R.id.run_model).text = model.nameWithoutExtension

        findViewById<View>(R.id.btn_abort).setOnClickListener { confirmAbort() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmAbort()
        })

        startedAt = SystemClock.elapsedRealtime()
        elapsedTv.post(ticker)
        setProgress(0.0)
        renderLive(null, 0.0)

        val mode = intent.getIntExtra(EXTRA_MODE, Prefs.MODE_ABLATION)
        val nRuns = intent.getIntExtra(EXTRA_RUNS, 3)
        val durationMin = intent.getIntExtra(EXTRA_DURATION_MIN, 5)
        val effArm = intent.getBooleanExtra(EXTRA_EFF_ARM, false)

        job = lifecycleScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val engine = AiChat.getInferenceEngine(applicationContext)
                    var lastLiveMs = 0L
                    val runner = BenchRunner(applicationContext, engine).also { r ->
                        r.onStatus = { t -> runOnUiThread { statusTv.text = t } }
                        r.onProgress = { f -> runOnUiThread { setProgress(f) } }
                        // Telemetry arrives every ~150 ms; repainting that often would
                        // burn main-thread CPU while the benchmark runs. Once a second
                        // is plenty for a readout.
                        r.onLive = { s ->
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastLiveMs >= 1_000L) {
                                lastLiveMs = now
                                runOnUiThread { renderLive(s, currentPct) }
                            }
                        }
                    }
                    runOnUiThread { statusTv.text = "loading ${model.nameWithoutExtension}" }
                    runner.loadModel(model)
                    when (mode) {
                        Prefs.MODE_SUSTAINED ->
                            runner.runSustained(model.nameWithoutExtension, durationMin * 60_000L)
                        Prefs.MODE_SWEEP ->
                            runner.runSweep(model.nameWithoutExtension, nRuns)
                        else ->
                            runner.runAblation(model.nameWithoutExtension, nRuns, effArm)
                    }
                }
            }
            outcome
                .onSuccess { result ->
                    // A write can fail - a full disk, most plausibly, after a run that may
                    // have taken ten minutes. Uncaught it escaped this coroutine and took
                    // the app down, which loses the result and explains nothing. The result
                    // is lost either way; the user should at least be told why.
                    val f = withContext(Dispatchers.IO) {
                        runCatching { ResultStore.save(this@RunActivity, result) }
                    }.getOrElse { e ->
                        Toast.makeText(
                            this@RunActivity,
                            "Benchmark finished but could not be saved: ${e.message}",
                            Toast.LENGTH_LONG,
                        ).show()
                        finish()
                        return@onSuccess
                    }
                    // Opt-in contribution. Saved locally first, so a failed or refused
                    // upload can never cost the user their result.
                    if (ResultUploader.enabled(this@RunActivity)) {
                        runCatching { ResultUploader.upload(this@RunActivity, result, f.name) }
                    }
                    startActivity(Intent(this@RunActivity, ResultActivity::class.java)
                        .putExtra(ResultActivity.EXTRA_FILE, f.name))
                    finish()
                }
                .onFailure { e ->
                    if (e !is CancellationException) {
                        Toast.makeText(this@RunActivity, "Benchmark failed: ${e.message}", Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
        }
    }

    override fun onDestroy() {
        elapsedTv.removeCallbacks(ticker)
        super.onDestroy()
    }

    private fun confirmAbort() {
        AlertDialog.Builder(this)
            .setMessage("Abort this benchmark? Nothing is saved for a cancelled run.")
            .setPositiveButton(R.string.run_cancel) { _, _ ->
                job?.cancel()
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private var currentPct = 0.0

    private fun setProgress(f: Double) {
        currentPct = f.coerceIn(0.0, 1.0)
        (fill.layoutParams as LinearLayout.LayoutParams).weight = currentPct.toFloat()
        (rest.layoutParams as LinearLayout.LayoutParams).weight = (1.0 - currentPct).toFloat()
        fill.requestLayout()
        pctTv.text = "%d%%".format((currentPct * 100).toInt())
    }

    private fun renderLive(s: TelemetrySample?, pct: Double) {
        liveGrid.removeAllViews()
        Ui.gridRow(this, liveGrid, "battery temp",
            s?.let { "%.1fC".format(it.batteryTempC) } ?: "-")
        Ui.gridRow(this, liveGrid, "power",
            s?.takeIf { it.watts > 0 }?.let { "%.2f W".format(it.watts) } ?: "-")
        Ui.gridRow(this, liveGrid, "thermal",
            s?.let { BenchExport.thermalLabel(it.thermalStatus) } ?: "-")
        Ui.gridRow(this, liveGrid, "app CPU",
            s?.let { "%.0f%%".format(it.processCpuPercent) } ?: "-")
        Ui.gridRow(this, liveGrid, "progress", "%d%%".format((pct * 100).toInt()), boldValue = true)
    }

    companion object {
        const val EXTRA_MODEL_PATH = "model_path"
        const val EXTRA_MODE = "mode"
        const val EXTRA_RUNS = "runs"
        const val EXTRA_DURATION_MIN = "duration_min"
        const val EXTRA_EFF_ARM = "efficiency_arm"
    }
}
