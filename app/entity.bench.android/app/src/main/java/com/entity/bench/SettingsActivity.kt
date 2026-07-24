package com.entity.bench

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        Palette.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        Insets.pad(findViewById(android.R.id.content))

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.about_version).text =
            "ENTITY BENCH v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · arm64"

        for ((id, value) in listOf(
            R.id.theme_system to Prefs.THEME_SYSTEM,
            R.id.theme_light to Prefs.THEME_LIGHT,
            R.id.theme_dark to Prefs.THEME_DARK,
        )) {
            findViewById<TextView>(id).setOnClickListener {
                Prefs.get(this).edit().putInt(Prefs.KEY_THEME, value).apply()
                styleTheme()
                // AppCompat recreates every started activity when the mode changes.
                Prefs.applyTheme(this)
            }
        }
        for ((id, value) in listOf(
            R.id.palette_mono to Palette.MONOCHROME,
            R.id.palette_colour to Palette.COLOUR,
        )) {
            findViewById<TextView>(id).setOnClickListener {
                if (Palette.current(this) == value) return@setOnClickListener
                Prefs.get(this).edit().putInt(Prefs.KEY_PALETTE, value).apply()
                // The palette is a theme, applied at inflation, so the screen must be
                // rebuilt for it to show. Other screens pick it up in their own onCreate.
                recreate()
            }
        }
        styleTheme()
        stylePalette()
        buildBenchmark()
        buildData()
        buildContribute()
    }

    // ---------- Contribute ----------

    private fun buildContribute() {
        val card = findViewById<View>(R.id.card_contribute)
        val toggle = findViewById<View>(R.id.row_contribute)
        // A build with no endpoint cannot send anything, so say so rather than offering a
        // switch that silently does nothing.
        if (!ResultUploader.configured) {
            toggle.isEnabled = false
            toggle.alpha = 0.45f
            findViewById<TextView>(R.id.contribute_value).setText(R.string.contribute_unconfigured)
            findViewById<View>(R.id.row_contribute_show).setOnClickListener { showPayload() }
            findViewById<View>(R.id.row_contribute_pick).setOnClickListener {
                startActivity(Intent(this, ContributeActivity::class.java))
            }
            return
        }
        toggle.setOnClickListener {
            ResultUploader.setEnabled(this, !ResultUploader.enabled(this))
            paintContribute()
        }
        findViewById<View>(R.id.row_contribute_show).setOnClickListener { showPayload() }
        findViewById<View>(R.id.row_contribute_pick).setOnClickListener {
            startActivity(Intent(this, ContributeActivity::class.java))
        }
        paintContribute()
    }

    private fun paintContribute() {
        Ui.check(findViewById(R.id.contribute_check), ResultUploader.enabled(this))
        val q = ResultUploader.queuedCount(this)
        findViewById<TextView>(R.id.contribute_value).text =
            if (q > 0) getString(R.string.contribute_queued, q) else getString(R.string.contribute_none)
    }

    /** Show the real body for the most recent run - not a sample, not a description. */
    private fun showPayload() {
        val latest = ResultStore.summaries(this).firstOrNull()
        val result = latest?.let { ResultStore.load(this, it.file) }
        val text = if (result == null) getString(R.string.contribute_empty)
                   else ResultUploader.payload(this, result).toString(2)
        AlertDialog.Builder(this)
            .setTitle(R.string.contribute_show)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        refreshData()
    }

    // ---------- Benchmark ----------

    private fun buildBenchmark() {
        val keepOn = findViewById<TextView>(R.id.row_keep_on)
        val effArm = findViewById<TextView>(R.id.row_eff_arm_set)
        fun paint() {
            Ui.seg(this, keepOn, Prefs.get(this).getBoolean(Prefs.KEY_KEEP_ON, Prefs.DEF_KEEP_ON))
            Ui.seg(this, effArm, Prefs.get(this).getBoolean(Prefs.KEY_EFF_ARM, false))
        }
        keepOn.setOnClickListener {
            val v = !Prefs.get(this).getBoolean(Prefs.KEY_KEEP_ON, Prefs.DEF_KEEP_ON)
            Prefs.get(this).edit().putBoolean(Prefs.KEY_KEEP_ON, v).apply(); paint()
        }
        effArm.setOnClickListener {
            val v = !Prefs.get(this).getBoolean(Prefs.KEY_EFF_ARM, false)
            Prefs.get(this).edit().putBoolean(Prefs.KEY_EFF_ARM, v).apply(); paint()
        }
        paint()
    }

    // ---------- Data ----------

    private fun buildData() {
        findViewById<View>(R.id.row_models).setOnClickListener {
            startActivity(Intent(this, ModelsActivity::class.java))
        }
        findViewById<View>(R.id.row_results).setOnClickListener { confirmClearResults() }
    }

    private fun refreshData() {
        val models = ModelStore.scan(this)
        findViewById<TextView>(R.id.models_value).text =
            if (models.isEmpty()) getString(R.string.settings_models_none)
            else "${models.size} model${if (models.size == 1) "" else "s"} · " +
                ModelStore.sizeLabel(models.sumOf { it.length() }) + " on disk"

        val results = ResultStore.summaries(this)
        findViewById<TextView>(R.id.results_value).text =
            if (results.isEmpty()) getString(R.string.settings_results_none)
            else "${results.size} saved run${if (results.size == 1) "" else "s"}"
    }

    private fun confirmClearResults() {
        val results = ResultStore.summaries(this)
        if (results.isEmpty()) {
            Toast.makeText(this, R.string.settings_results_none, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_results)
            .setMessage(R.string.settings_results_clear)
            .setPositiveButton(R.string.models_delete) { _, _ ->
                results.forEach { ResultStore.delete(this, it.file) }
                refreshData()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun stylePalette() {
        val current = Palette.current(this)
        Ui.seg(this, findViewById(R.id.palette_mono), current == Palette.MONOCHROME)
        Ui.seg(this, findViewById(R.id.palette_colour), current == Palette.COLOUR)
    }

    private fun styleTheme() {
        val current = Prefs.get(this).getInt(Prefs.KEY_THEME, Prefs.THEME_SYSTEM)
        Ui.seg(this, findViewById(R.id.theme_system), current == Prefs.THEME_SYSTEM)
        Ui.seg(this, findViewById(R.id.theme_light), current == Prefs.THEME_LIGHT)
        Ui.seg(this, findViewById(R.id.theme_dark), current == Prefs.THEME_DARK)
    }
}
