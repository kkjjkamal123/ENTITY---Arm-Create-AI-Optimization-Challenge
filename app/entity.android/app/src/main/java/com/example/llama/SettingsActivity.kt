package com.example.llama

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import com.arm.aichat.AiChat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// The whole screen is composed in code through the row builders below, so every
// control shares one implementation of the two-color rule.
class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var root: LinearLayout
    private val manualSliders = mutableListOf<SeekBar>()
    private var manualBox: LinearLayout? = null
    private var modelsValue: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = getSharedPreferences(Settings.PREFS, Context.MODE_PRIVATE)
        root = findViewById(R.id.settings_root)
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        buildTheme()
        buildInterface()
        buildMetrics()
        buildChat()
        buildInference()
        buildData()
    }

    // ---------- Sections ----------

    private fun buildTheme() {
        val box = section(R.string.sec_theme)
        segRow(
            box,
            listOf(getString(R.string.theme_system), getString(R.string.theme_light), getString(R.string.theme_dark)),
            selected = { prefs.getInt(Settings.KEY_THEME, Settings.DEF_THEME) },
        ) { which ->
            prefs.edit().putInt(Settings.KEY_THEME, which).apply()
            // AppCompat recreates every started activity when the mode changes.
            AppCompatDelegate.setDefaultNightMode(Settings.nightMode(which))
        }
    }

    private fun buildInterface() {
        val box = section(R.string.sec_interface)
        miniLabel(box, getString(R.string.text_size_label))
        segRow(
            box,
            listOf(getString(R.string.text_small), getString(R.string.text_medium), getString(R.string.text_large)),
            selected = { prefs.getInt(Settings.KEY_TEXT_SIZE, Settings.DEF_TEXT_SIZE) },
            topMargin = 6,
        ) { which -> prefs.edit().putInt(Settings.KEY_TEXT_SIZE, which).apply() }

        checkRow(box, getString(R.string.settings_anim), getString(R.string.settings_anim_desc),
            Settings.KEY_ANIM, Settings.DEF_ANIM) { Anim.setUserEnabled(it) }

        val iconLabels = arrayOf(
            getString(R.string.icon_auto), getString(R.string.icon_black), getString(R.string.icon_white)
        )
        val iconValue = actionRow(box, getString(R.string.settings_icon),
            iconLabels[prefs.getInt(IconStyle.KEY, IconStyle.AUTO)]) { valueView ->
            val current = prefs.getInt(IconStyle.KEY, IconStyle.AUTO)
            AlertDialog.Builder(this)
                .setTitle(R.string.settings_icon)
                .setSingleChoiceItems(iconLabels, current) { dialog, which ->
                    prefs.edit().putInt(IconStyle.KEY, which).apply()
                    valueView.text = iconLabels[which]
                    IconStyle.apply(this, which)
                    dialog.dismiss()
                    Toast.makeText(this, "Icon updated — it may take a moment to refresh on your home screen.", Toast.LENGTH_LONG).show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        iconValue.text = iconLabels[prefs.getInt(IconStyle.KEY, IconStyle.AUTO)]
    }

    private fun buildMetrics() {
        val box = section(R.string.sec_metrics)
        checkRow(box, getString(R.string.settings_show_stats), getString(R.string.settings_show_stats_desc),
            Settings.KEY_SHOW_STATS, false)
        checkRow(box, getString(R.string.settings_show_graph), getString(R.string.settings_show_graph_desc),
            Settings.KEY_SHOW_GRAPH, false)

        miniLabel(box, getString(R.string.graph_style_label))
        checkRow(box, getString(R.string.graph_fill), null, Settings.KEY_GRAPH_FILL, false)
        checkRow(box, getString(R.string.graph_smooth), null, Settings.KEY_GRAPH_SMOOTH, false)

        miniLabel(box, getString(R.string.stats_shown_label))
        for ((key, labelRes) in Settings.STAT_KEYS) {
            checkRow(box, getString(labelRes), null, key, true)
        }
    }

    private fun buildChat() {
        val box = section(R.string.sec_chat)
        val promptValue = actionRow(box, getString(R.string.settings_system_prompt), null) { valueView ->
            editSystemPrompt(valueView)
        }
        promptValue.text = Settings.systemPrompt(prefs)

        checkRow(box, getString(R.string.settings_haptics), getString(R.string.settings_haptics_desc),
            Settings.KEY_HAPTICS, Settings.DEF_HAPTICS)
        checkRow(box, getString(R.string.settings_keep_on), getString(R.string.settings_keep_on_desc),
            Settings.KEY_KEEP_ON, Settings.DEF_KEEP_ON)
    }

    private fun buildInference() {
        val box = section(R.string.sec_inference)
        checkRow(box, getString(R.string.settings_auto), getString(R.string.settings_auto_desc),
            Settings.KEY_AUTO, Settings.DEF_AUTO) { setManualEnabled(!it) }

        miniLabel(box, getString(R.string.settings_manual))
        val manual = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(manual)
        manualBox = manual

        slider(manual, getString(R.string.settings_temp), 0, 100,
            init = prefs.getInt(Settings.KEY_TEMP, Settings.DEF_TEMP),
            render = { "%.2f".format(it / 100f) },
            save = { prefs.edit().putInt(Settings.KEY_TEMP, it).apply() })

        slider(manual, getString(R.string.settings_topk), 0, 100,
            init = prefs.getInt(Settings.KEY_TOPK, Settings.DEF_TOPK),
            render = { if (it == 0) "off" else it.toString() },
            save = { prefs.edit().putInt(Settings.KEY_TOPK, it).apply() })

        slider(manual, getString(R.string.settings_topp), 0, 100,
            init = prefs.getInt(Settings.KEY_TOPP, Settings.DEF_TOPP),
            render = { "%.2f".format(it / 100f) },
            save = { prefs.edit().putInt(Settings.KEY_TOPP, it).apply() })

        slider(manual, getString(R.string.settings_maxtok), 0, 1984,
            init = prefs.getInt(Settings.KEY_MAXTOK, Settings.DEF_MAXTOK) - 64,
            render = { (it + 64).toString() },
            save = { prefs.edit().putInt(Settings.KEY_MAXTOK, it + 64).apply() })

        slider(manual, getString(R.string.settings_ctx), 0, Settings.CTX_STEPS.size - 1,
            init = Settings.CTX_STEPS.indexOf(prefs.getInt(Settings.KEY_CTX, Settings.DEF_CTX)).coerceAtLeast(0),
            render = { Settings.CTX_STEPS[it].toString() },
            save = { prefs.edit().putInt(Settings.KEY_CTX, Settings.CTX_STEPS[it]).apply() })

        slider(manual, getString(R.string.settings_threads), 1, 8,
            init = prefs.getInt(Settings.KEY_THREADS, Settings.DEF_THREADS),
            render = { it.toString() },
            save = { prefs.edit().putInt(Settings.KEY_THREADS, it).apply() })

        setManualEnabled(!prefs.getBoolean(Settings.KEY_AUTO, Settings.DEF_AUTO))

        checkRow(box, getString(R.string.settings_efficiency), getString(R.string.settings_efficiency_desc),
            Settings.KEY_EFFICIENCY, Settings.DEF_EFFICIENCY)

        // Re-open the first-run suggestion on demand. recreate() so every row
        // redraws with whatever the dialog just wrote.
        outlineButton(box, getString(R.string.settings_optimize)) {
            val info = runCatching { AiChat.getInferenceEngine(applicationContext).cpuInfo() }.getOrDefault("")
            DeviceOptimizer.show(this, info) { recreate() }
        }
    }

    private fun buildData() {
        val box = section(R.string.sec_data)
        modelsValue = actionRow(box, getString(R.string.settings_models), null) { manageModels() }
        refreshModelsValue()

        actionRow(box, getString(R.string.settings_export), getString(R.string.settings_export_desc)) {
            exportAllChats()
        }
        actionRow(box, getString(R.string.settings_clear), getString(R.string.settings_clear_desc)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.settings_clear)
                .setMessage(R.string.settings_clear_confirm)
                .setPositiveButton("Delete") { _, _ -> clearAllChats() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    // ---------- Data actions ----------

    private fun refreshModelsValue() {
        val models = ModelStore.scan(this)
        val total = models.sumOf { it.length() }
        modelsValue?.text =
            if (models.isEmpty()) getString(R.string.models_none)
            else "${models.size} model${if (models.size == 1) "" else "s"} · ${ModelStore.sizeLabel(total)} on disk"
    }

    private fun manageModels() {
        val models = ModelStore.scan(this)
        if (models.isEmpty()) {
            Toast.makeText(this, R.string.models_none, Toast.LENGTH_SHORT).show()
            return
        }
        val active = prefs.getString(Settings.KEY_ACTIVE_MODEL, null)
        val labels = models.map {
            val mark = if (it.nameWithoutExtension == active) "  [ACTIVE]" else ""
            "${it.name}$mark\n${ModelStore.sizeLabel(it.length())}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_models)
            .setItems(labels) { _, which ->
                val model = models[which]
                if (model.nameWithoutExtension == active) {
                    Toast.makeText(this, R.string.model_active_block, Toast.LENGTH_LONG).show()
                } else {
                    AlertDialog.Builder(this)
                        .setMessage(getString(R.string.model_delete_confirm, model.name, ModelStore.sizeLabel(model.length())))
                        .setPositiveButton("Delete") { _, _ ->
                            if (model.delete()) refreshModelsValue()
                            else Toast.makeText(this, "Could not delete file.", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun exportAllChats() {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                val db = ChatDb(this@SettingsActivity)
                try {
                    val convs = db.listConversations()
                    if (convs.isEmpty()) return@withContext null
                    buildString {
                        for (c in convs.reversed()) {
                            appendLine("==== ${c.title.ifBlank { getString(R.string.conv_untitled) }} ====")
                            for (m in db.messagesFor(c.id)) {
                                appendLine(if (m.role == ChatViewModel.ROLE_USER) "You: ${m.content}" else "ENTITY: ${m.content}")
                                appendLine()
                            }
                        }
                    }.trim()
                } finally {
                    db.close()
                }
            }
            if (text == null) {
                Toast.makeText(this@SettingsActivity, "No conversations to export.", Toast.LENGTH_SHORT).show()
            } else {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                startActivity(Intent.createChooser(send, getString(R.string.settings_export)))
            }
        }
    }

    private fun clearAllChats() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val db = ChatDb(this@SettingsActivity)
                try {
                    db.clearAll()
                } finally {
                    db.close()
                }
                File(filesDir, "kvstate").listFiles()?.forEach { runCatching { it.delete() } }
            }
            prefs.edit().putBoolean(Settings.KEY_CHATS_CHANGED, true).apply()
            Toast.makeText(this@SettingsActivity, "All conversations deleted.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun editSystemPrompt(valueView: TextView) {
        val input = EditText(this).apply {
            setText(Settings.systemPrompt(prefs))
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 4
            maxLines = 10
            gravity = Gravity.TOP
            setSelection(text.length)
        }
        val pad = Ui.dp(this, 20)
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_system_prompt)
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val t = input.text.toString().trim()
                if (t.isEmpty()) {
                    prefs.edit().remove(Settings.KEY_SYSTEM_PROMPT).apply()
                } else {
                    prefs.edit().putString(Settings.KEY_SYSTEM_PROMPT, t).apply()
                }
                valueView.text = Settings.systemPrompt(prefs)
                Toast.makeText(this, "Applied on next model load or new chat.", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(R.string.settings_system_prompt_reset) { _, _ ->
                prefs.edit().remove(Settings.KEY_SYSTEM_PROMPT).apply()
                valueView.text = Settings.systemPrompt(prefs)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------- Row builders (the mono design system, in code) ----------

    private fun dp(v: Int) = Ui.dp(this, v)

    private fun lp(topMargin: Int = 0) =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { this.topMargin = dp(topMargin) }

    private fun section(labelRes: Int): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_box)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = lp(topMargin = if (root.childCount == 1) 14 else 12)
        }
        box.addView(TextView(this).apply {
            text = getString(labelRes).uppercase()
            setTextColor(Ui.fg(this@SettingsActivity))
            textSize = 11f
            setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
            letterSpacing = 0.18f
        })
        root.addView(box)
        return box
    }

    private fun miniLabel(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            setTextColor(Ui.fg(this@SettingsActivity))
            textSize = 9f
            setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
            letterSpacing = 0.18f
            layoutParams = lp(topMargin = 14)
        })
    }

    private fun segRow(
        parent: LinearLayout,
        options: List<String>,
        selected: () -> Int,
        topMargin: Int = 12,
        onSelect: (Int) -> Unit,
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = lp(topMargin = topMargin)
        }
        val cells = options.mapIndexed { i, opt ->
            TextView(this).apply {
                text = opt
                gravity = Gravity.CENTER
                textSize = 12f
                setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
                letterSpacing = 0.1f
                setPadding(0, dp(10), 0, dp(10))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { if (i > 0) marginStart = dp(6) }
            }
        }
        fun restyle() {
            val sel = selected()
            cells.forEachIndexed { i, tv -> Ui.seg(tv, i == sel) }
        }
        cells.forEachIndexed { i, tv ->
            tv.setOnClickListener {
                onSelect(i)
                restyle()
            }
            row.addView(tv)
        }
        restyle()
        parent.addView(row)
    }

    private fun checkRow(
        parent: LinearLayout,
        title: String,
        desc: String?,
        key: String,
        def: Boolean,
        onChange: ((Boolean) -> Unit)? = null,
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            layoutParams = lp(topMargin = 12)
        }
        val boxView = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { topMargin = dp(1) }
        }
        Ui.check(boxView, prefs.getBoolean(key, def))
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(10) }
        }
        col.addView(TextView(this).apply {
            text = title
            setTextColor(Ui.fg(this@SettingsActivity))
            textSize = 12f
            setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
        })
        if (desc != null) {
            col.addView(TextView(this).apply {
                text = desc
                setTextColor(Ui.fg(this@SettingsActivity))
                textSize = 10f
                typeface = android.graphics.Typeface.MONOSPACE
                setLineSpacing(0f, 1.35f)
                layoutParams = lp(topMargin = 2)
            })
        }
        row.addView(boxView)
        row.addView(col)
        row.setOnClickListener {
            val next = !prefs.getBoolean(key, def)
            prefs.edit().putBoolean(key, next).apply()
            Ui.check(boxView, next)
            onChange?.invoke(next)
        }
        parent.addView(row)
    }

    // Bordered tap row with an optional live value line; returns the value view.
    private fun actionRow(
        parent: LinearLayout,
        title: String,
        desc: String?,
        onClick: (TextView) -> Unit,
    ): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.btn_outline)
            setPadding(dp(10), dp(9), dp(10), dp(9))
            layoutParams = lp(topMargin = 12)
        }
        row.addView(TextView(this).apply {
            text = title
            setTextColor(Ui.fg(this@SettingsActivity))
            textSize = 12f
            setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
        })
        val value = TextView(this).apply {
            setTextColor(Ui.fg(this@SettingsActivity))
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.35f)
            layoutParams = lp(topMargin = 2)
            if (desc != null) text = desc
        }
        row.addView(value)
        row.setOnClickListener { onClick(value) }
        parent.addView(row)
        return value
    }

    private fun outlineButton(parent: LinearLayout, text: String, onClick: () -> Unit) {
        parent.addView(TextView(this).apply {
            this.text = text
            setBackgroundResource(R.drawable.btn_outline)
            setTextColor(androidx.core.content.ContextCompat.getColorStateList(this@SettingsActivity, R.color.tc_outline))
            textSize = 11f
            setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(10))
            layoutParams = lp(topMargin = 12)
            setOnClickListener { onClick() }
        })
    }

    private fun slider(
        parent: LinearLayout,
        title: String,
        min: Int,
        max: Int,
        init: Int,
        render: (Int) -> String,
        save: (Int) -> Unit,
    ) {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = lp(topMargin = 12)
        }
        header.addView(TextView(this).apply {
            text = title
            setTextColor(Ui.fg(this@SettingsActivity))
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val valueView = TextView(this).apply {
            text = render(init)
            setTextColor(Ui.fg(this@SettingsActivity))
            textSize = 11f
            setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
        }
        header.addView(valueView)
        parent.addView(header)

        val ink = ColorStateList.valueOf(Ui.fg(this))
        val seek = SeekBar(this).apply {
            this.min = min
            this.max = max
            progress = init
            progressTintList = ink
            progressBackgroundTintList = ink
            thumbTintList = ink
            layoutParams = lp(topMargin = 2)
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                valueView.text = render(p)
                save(p)
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        manualSliders.add(seek)
        parent.addView(seek)
    }

    private fun setManualEnabled(enabled: Boolean) {
        manualBox?.alpha = if (enabled) 1f else 0.4f
        manualSliders.forEach { it.isEnabled = enabled }
    }
}
