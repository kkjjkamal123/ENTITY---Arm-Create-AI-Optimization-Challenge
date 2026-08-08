package com.example.llama

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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

/**
 * One screen for every model question: what is on the phone, what it is, and what else
 * this phone could run. It replaces a list dialog whose rows were four wrapped lines of
 * prose - the same facts are here, but each one lands in the same place on every card,
 * so the list can be scanned instead of read.
 *
 * The engine lives in [MainActivity], so this screen never loads a model itself: picking
 * one returns its path as an activity result and MainActivity does the loading. That also
 * means a download finishing here can hand straight over to the chat.
 */
class ModelsActivity : AppCompatActivity() {

    companion object {
        /** Absolute path of the model the user chose, when RESULT_OK. */
        const val EXTRA_PICKED = "picked_model_path"

        /**
         * Name (without extension) of the model the engine currently has loaded, or
         * absent if none. This is NOT read from prefs: KEY_ACTIVE_MODEL persists across
         * restarts while the engine does not, so trusting it made a freshly reopened app
         * show LOADED on a disabled button with nothing actually loaded.
         */
        const val EXTRA_LOADED = "loaded_model_name"
    }

    private lateinit var installedList: LinearLayout
    private lateinit var catalogList: LinearLayout
    private lateinit var installedEmpty: View
    private lateinit var installedSummary: TextView
    private lateinit var dlBox: View
    private lateinit var dlLabel: TextView
    private lateinit var dlProgress: ProgressBar
    private var downloadJob: Job? = null

    private lateinit var probeHeadline: TextView
    private lateinit var probeDetail: TextView
    private lateinit var probeDevice: TextView
    private lateinit var probeRun: TextView

    /**
     * Result of the model-free device probe, kept for the lifetime of the screen so the
     * catalog cards below can show per-model estimates once it has run. Null until the
     * user asks for it - the probe pins every performance core for about half a second,
     * which is not something to do unannounced on someone's phone.
     */
    private var probe: DeviceProbe.Profile? = null

    private val prefs by lazy { getSharedPreferences(Settings.PREFS, Context.MODE_PRIVATE) }
    private val modelDir by lazy { ModelStore.dirs(this).first() }

    override fun onCreate(savedInstanceState: Bundle?) {
        Palette.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_models)
        Insets.pad(findViewById(android.R.id.content))

        installedList = findViewById(R.id.installed_list)
        catalogList = findViewById(R.id.catalog_list)
        installedEmpty = findViewById(R.id.installed_empty)
        installedSummary = findViewById(R.id.installed_summary)
        dlBox = findViewById(R.id.dl_box)
        dlLabel = findViewById(R.id.dl_label)
        dlProgress = findViewById(R.id.dl_progress)

        probeHeadline = findViewById(R.id.probe_headline)
        probeDetail = findViewById(R.id.probe_detail)
        probeDevice = findViewById(R.id.probe_device)
        probeRun = findViewById(R.id.probe_run)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_import).setOnClickListener { getContent.launch(arrayOf("*/*")) }
        findViewById<View>(R.id.dl_cancel).setOnClickListener { downloadJob?.cancel() }
        probeRun.setOnClickListener { runProbe() }
    }

    /**
     * Measures the device and recommends a model, without downloading one.
     *
     * The probe saturates the performance cores, so it runs on Dispatchers.Default and the
     * button is disabled while it works rather than letting a second run pile on top of the
     * first and measure the contention instead of the hardware.
     */
    private fun runProbe() {
        probeRun.isEnabled = false
        probeRun.text = getString(R.string.probe_running)
        lifecycleScope.launch {
            val mem = ActivityManager.MemoryInfo().also {
                (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
            }
            val flags = ModelCatalog.featureFlags(
                runCatching { AiChat.getInferenceEngine(applicationContext).cpuInfo() }.getOrDefault("")
            )
            val cores = DeviceOptimizer.topClusterCoreCount(DeviceOptimizer.maxFreqsKhz())
            val p = withContext(Dispatchers.Default) {
                DeviceProbe.measure(mem.totalMem, cores, flags)
            }
            probe = p
            renderProbe(p)
            // Catalog cards gain per-model estimates once a profile exists.
            renderCatalog()
            probeRun.isEnabled = true
            probeRun.text = getString(R.string.probe_rerun)
        }
    }

    private fun renderProbe(p: DeviceProbe.Profile) {
        val rec = DeviceProbe.recommend(p)
        if (rec == null) {
            probeHeadline.text = getString(R.string.probe_none)
            probeDetail.text = getString(R.string.probe_none_note)
        } else {
            probeHeadline.text = rec.headline
            probeDetail.text = rec.why
        }
        probeDevice.visibility = View.VISIBLE
        val isa = if (p.flags.isEmpty()) "no ISA extensions detected" else p.flags.joinToString(", ")
        probeDevice.text =
            "Measured in %d ms · %.1f GB/s memory bandwidth · %d performance core%s · %.1f GB RAM · %s".format(
                p.elapsedMs, p.bandwidthGBs, p.perfCores, if (p.perfCores == 1) "" else "s", p.ramGb, isa,
            ) + (rec?.runnerUp?.let { "\nRunner-up: ${it.name} ${it.quant}" } ?: "")
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    // ---------- rendering ----------

    private fun render() {
        renderInstalled()
        renderCatalog()
    }

    private fun renderInstalled() {
        val models = ModelStore.scan(this)
        val active = intent.getStringExtra(EXTRA_LOADED)
        installedList.removeAllViews()
        installedEmpty.visibility = if (models.isEmpty()) View.VISIBLE else View.GONE
        installedSummary.text =
            if (models.isEmpty()) ""
            else "${models.size} · ${ModelStore.sizeLabel(models.sumOf { it.length() })}"

        for (f in models) {
            val card = inflate(installedList)
            val isActive = f.nameWithoutExtension == active
            // A downloaded catalog entry knows its own parameter count; an imported file
            // only tells us what its filename says, so only claim what is actually known.
            val known = ModelCatalog.ALL.firstOrNull { it.fileName == f.name }
            val quant = known?.quant ?: quantFromName(f.name)

            card.name.text = f.name
            card.meta.text = listOfNotNull(
                known?.let { "%.2fB".format(it.paramsB) },
                quant,
                ModelStore.sizeLabel(f.length()),
            ).joinToString(" · ")

            if (isActive) pill(card.state, "ACTIVE", solid = true)
            kleidiPill(card.kleidi, quant)
            card.fit.visibility = View.GONE
            card.reason.visibility = View.GONE

            card.primary.text = getString(if (isActive) R.string.models_loaded else R.string.models_load)
            card.primary.isEnabled = !isActive
            card.primary.alpha = if (isActive) 0.45f else 1f
            card.primary.setOnClickListener { if (!isActive) pick(f) }

            card.secondary.visibility = View.VISIBLE
            card.secondary.text = getString(R.string.models_delete)
            card.secondary.setOnClickListener { confirmDelete(f, isActive) }
        }
    }

    private fun renderCatalog() {
        val mem = ActivityManager.MemoryInfo().also {
            (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
        }
        // No engine on this screen, so read the ISA off a fresh handle; an empty string
        // degrades to "no dotprod" rather than failing.
        val flags = ModelCatalog.featureFlags(
            runCatching { AiChat.getInferenceEngine(applicationContext).cpuInfo() }.getOrDefault("")
        )
        val recommended = ModelCatalog.recommended(mem.totalMem, flags)
        val entries = ModelCatalog.ALL.sortedByDescending {
            when (ModelCatalog.assess(it, mem.totalMem, flags).fit) {
                ModelCatalog.Fit.GREAT -> 3; ModelCatalog.Fit.OK -> 2
                ModelCatalog.Fit.TIGHT -> 1; ModelCatalog.Fit.TOO_BIG -> 0
            }
        }

        catalogList.removeAllViews()
        for (e in entries) {
            val target = File(modelDir, e.fileName)
            // Already downloaded: it has a card under "On this phone", so listing it here
            // too would show the same file twice with two different actions.
            if (target.exists() && target.length() == e.sizeBytes) continue

            val a = ModelCatalog.assess(e, mem.totalMem, flags)
            val partial = ModelDownloader.partFileFor(modelDir, e).exists()

            val card = inflate(catalogList)
            card.name.text = e.name
            // Vendor and role were missing before: a catalog spanning seven organisations
            // reads as one vendor's list without them, and a coding or reasoning model is
            // not interchangeable with a chat model at the same size.
            val role = if (e.role == ModelCatalog.Role.GENERAL) "" else " · ${e.role.label}"
            card.meta.text = "%s · %.2fB · %s · %s%s".format(
                e.vendor, e.paramsB, e.quant, ModelCatalog.humanSize(e.sizeBytes), role,
            )

            kleidiPill(card.kleidi, e.quant)
            // Solid inversion is this design's strongest emphasis, so a card gets at most
            // one: the top-right state pill. For the single best choice that is
            // RECOMMENDED, exactly where ACTIVE sits on an installed card.
            if (e.id == recommended?.id) {
                pill(card.state, "RECOMMENDED", solid = true)
                card.fit.visibility = View.GONE
            } else {
                pill(card.fit, when (a.fit) {
                    ModelCatalog.Fit.GREAT -> "GOOD FIT"
                    ModelCatalog.Fit.OK -> "FITS"
                    ModelCatalog.Fit.TIGHT -> "TIGHT"
                    ModelCatalog.Fit.TOO_BIG -> "TOO BIG"
                }, solid = false)
            }

            card.reason.visibility = View.VISIBLE
            // Once the device has been probed, every row carries its own estimate. Before
            // that it carries only the fit note, because an estimate with no measurement
            // behind it would be a guess dressed as a number.
            card.reason.text = probe?.let { p ->
                val est = DeviceProbe.estimate(e, p)
                "%s\nEstimated ~%.0f tok/s generation · ~%.1fs to first token on a 512-token prompt".format(
                    a.reason, est.decodeToksPerS, est.ttftSeconds,
                )
            } ?: a.reason

            card.primary.text =
                getString(if (partial) R.string.models_resume else R.string.models_download)
            card.primary.setOnClickListener { startDownload(e) }
            card.secondary.visibility = View.GONE
        }
    }

    // ---------- card plumbing ----------

    private class Card(v: View) {
        val root: View = v
        val name: TextView = v.findViewById(R.id.m_name)
        val state: TextView = v.findViewById(R.id.m_state)
        val meta: TextView = v.findViewById(R.id.m_meta)
        val kleidi: TextView = v.findViewById(R.id.m_pill_kleidi)
        val fit: TextView = v.findViewById(R.id.m_pill_fit)
        val reason: TextView = v.findViewById(R.id.m_reason)
        val primary: TextView = v.findViewById(R.id.m_primary)
        val secondary: TextView = v.findViewById(R.id.m_secondary)
    }

    private fun inflate(parent: ViewGroup): Card {
        val v = LayoutInflater.from(this).inflate(R.layout.item_model, parent, false)
        parent.addView(v)
        return Card(v)
    }

    private fun pill(tv: TextView, text: String, solid: Boolean) {
        tv.visibility = View.VISIBLE
        tv.text = text
        tv.setBackgroundResource(if (solid) R.drawable.bg_fill else R.drawable.bg_dashed)
        tv.setTextColor(if (solid) Palette.color(this, R.attr.monoOnFill) else Palette.color(this, R.attr.monoFg))
    }

    /** Only claim KleidiAI for the two types that actually reach it. */
    private fun kleidiPill(tv: TextView, quant: String?) {
        when {
            quant == null -> tv.visibility = View.GONE
            quant == "Q4_0" || quant == "Q8_0" -> pill(tv, "KLEIDIAI", solid = true)
            else -> pill(tv, "NO KLEIDIAI", solid = false)
        }
    }

    private fun quantFromName(name: String): String? =
        Regex("(Q\\d+_[0-9KMSL]+(?:_[A-Z])?|F16|BF16|F32)", RegexOption.IGNORE_CASE)
            .find(name)?.value?.uppercase()

    // ---------- actions ----------

    private fun pick(model: File) {
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_PICKED, model.absolutePath))
        finish()
    }

    private fun confirmDelete(model: File, isActive: Boolean) {
        if (isActive) {
            Toast.makeText(this, R.string.model_active_block, Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.model_delete_confirm, model.name, ModelStore.sizeLabel(model.length())))
            .setPositiveButton(R.string.models_delete) { _, _ ->
                if (model.delete()) render()
                else Toast.makeText(this, "Could not delete file.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startDownload(e: ModelCatalog.Entry) {
        if (downloadJob?.isActive == true) {
            Toast.makeText(this, R.string.models_one_at_a_time, Toast.LENGTH_SHORT).show()
            return
        }
        dlBox.visibility = View.VISIBLE
        dlLabel.text = getString(R.string.models_downloading, e.name, 0)
        dlProgress.progress = 0

        downloadJob = lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching {
                ModelDownloader.download(e, modelDir) { p ->
                    runOnUiThread {
                        dlLabel.text = getString(R.string.models_downloading, e.name, p.percent)
                        dlProgress.progress = p.percent
                    }
                }
            }
            withContext(Dispatchers.Main) {
                dlBox.visibility = View.GONE
                result
                    .onSuccess { pick(it) }
                    .onFailure { err ->
                        render()   // a cancelled transfer leaves a .part, so the row becomes RESUME
                        if (err !is CancellationException) {
                            Toast.makeText(this@ModelsActivity,
                                getString(R.string.models_download_failed, err.message ?: ""),
                                Toast.LENGTH_LONG).show()
                        }
                    }
            }
        }
    }

    private val getContent = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { importModel(it) } }

    /** Copy a picked file in, then hand it to MainActivity exactly like any other pick. */
    private fun importModel(uri: Uri) {
        dlBox.visibility = View.VISIBLE
        dlLabel.text = getString(R.string.models_importing)
        dlProgress.progress = 0
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val target = File(modelDir, ModelStore.safeName(contentResolver, uri))
                if (!target.exists() || target.length() == 0L) {
                    val total = ModelStore.sizeOf(contentResolver, uri)
                    contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { out ->
                            val buf = ByteArray(1 shl 16)
                            var copied = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                copied += n
                                if (total > 0) {
                                    val pct = ((copied * 100) / total).toInt().coerceIn(0, 100)
                                    runOnUiThread { dlProgress.progress = pct }
                                }
                            }
                        }
                    } ?: error("Can't read that file. Pick the .gguf again from your storage.")
                }
                target
            }
            withContext(Dispatchers.Main) {
                dlBox.visibility = View.GONE
                result.onSuccess { pick(it) }
                    .onFailure {
                        Toast.makeText(this@ModelsActivity,
                            "Import failed: ${it.message}", Toast.LENGTH_LONG).show()
                    }
            }
        }
    }
}
