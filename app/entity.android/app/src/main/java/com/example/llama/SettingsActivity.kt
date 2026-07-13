package com.example.llama

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.arm.aichat.AiChat
import com.google.android.material.appbar.MaterialToolbar

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var manualGroup: LinearLayout
    private val seeks = mutableListOf<SeekBar>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = getSharedPreferences(Settings.PREFS, Context.MODE_PRIVATE)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        manualGroup = findViewById(R.id.manual_group)

        bind(R.id.seek_temp, R.id.val_temp,
            init = prefs.getInt(Settings.KEY_TEMP, Settings.DEF_TEMP),
            render = { "%.2f".format(it / 100f) },
            save = { prefs.edit().putInt(Settings.KEY_TEMP, it).apply() })

        bind(R.id.seek_topk, R.id.val_topk,
            init = prefs.getInt(Settings.KEY_TOPK, Settings.DEF_TOPK),
            render = { if (it == 0) "off" else it.toString() },
            save = { prefs.edit().putInt(Settings.KEY_TOPK, it).apply() })

        bind(R.id.seek_topp, R.id.val_topp,
            init = prefs.getInt(Settings.KEY_TOPP, Settings.DEF_TOPP),
            render = { "%.2f".format(it / 100f) },
            save = { prefs.edit().putInt(Settings.KEY_TOPP, it).apply() })

        bind(R.id.seek_maxtok, R.id.val_maxtok,
            init = prefs.getInt(Settings.KEY_MAXTOK, Settings.DEF_MAXTOK) - 64,
            render = { (it + 64).toString() },
            save = { prefs.edit().putInt(Settings.KEY_MAXTOK, it + 64).apply() })

        bind(R.id.seek_ctx, R.id.val_ctx,
            init = Settings.CTX_STEPS.indexOf(prefs.getInt(Settings.KEY_CTX, Settings.DEF_CTX)).coerceAtLeast(0),
            render = { Settings.CTX_STEPS[it].toString() },
            save = { prefs.edit().putInt(Settings.KEY_CTX, Settings.CTX_STEPS[it]).apply() })

        bind(R.id.seek_threads, R.id.val_threads,
            init = prefs.getInt(Settings.KEY_THREADS, Settings.DEF_THREADS),
            render = { it.toString() },
            save = { prefs.edit().putInt(Settings.KEY_THREADS, it).apply() })

        val auto = findViewById<SwitchCompat>(R.id.switch_auto)
        auto.isChecked = prefs.getBoolean(Settings.KEY_AUTO, Settings.DEF_AUTO)
        setManualEnabled(!auto.isChecked)
        auto.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(Settings.KEY_AUTO, checked).apply()
            setManualEnabled(!checked)
        }

        val anim = findViewById<SwitchCompat>(R.id.switch_anim)
        anim.isChecked = prefs.getBoolean(Settings.KEY_ANIM, Settings.DEF_ANIM)
        anim.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(Settings.KEY_ANIM, checked).apply()
            Anim.setUserEnabled(checked)
        }

        val eff = findViewById<SwitchCompat>(R.id.switch_efficiency)
        eff.isChecked = prefs.getBoolean(Settings.KEY_EFFICIENCY, Settings.DEF_EFFICIENCY)
        eff.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(Settings.KEY_EFFICIENCY, checked).apply()
        }

        // Re-open the first-run suggestion on demand. recreate() so the sliders and the
        // Auto switch redraw with whatever the dialog just wrote.
        findViewById<LinearLayout>(R.id.row_optimize).setOnClickListener {
            val info = runCatching { AiChat.getInferenceEngine(applicationContext).cpuInfo() }.getOrDefault("")
            DeviceOptimizer.show(this, info) { recreate() }
        }

        setupSystemPromptEditor()
        setupIconChooser()
    }

    private fun setupSystemPromptEditor() {
        val row = findViewById<LinearLayout>(R.id.row_system_prompt)
        val value = findViewById<TextView>(R.id.val_system_prompt)
        value.text = Settings.systemPrompt(prefs)
        row.setOnClickListener {
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
            val pad = (20 * resources.displayMetrics.density).toInt()
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
                    value.text = Settings.systemPrompt(prefs)
                    Toast.makeText(this, "Applied on next model load or new chat.", Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton(R.string.settings_system_prompt_reset) { _, _ ->
                    prefs.edit().remove(Settings.KEY_SYSTEM_PROMPT).apply()
                    value.text = Settings.systemPrompt(prefs)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun setupIconChooser() {
        val row = findViewById<LinearLayout>(R.id.row_icon)
        val value = findViewById<TextView>(R.id.val_icon)
        val labels = arrayOf(
            getString(R.string.icon_auto),
            getString(R.string.icon_black),
            getString(R.string.icon_white),
        )
        value.text = labels[prefs.getInt(IconStyle.KEY, IconStyle.AUTO)]
        row.setOnClickListener {
            val current = prefs.getInt(IconStyle.KEY, IconStyle.AUTO)
            AlertDialog.Builder(this)
                .setTitle(R.string.settings_icon)
                .setSingleChoiceItems(labels, current) { dialog, which ->
                    prefs.edit().putInt(IconStyle.KEY, which).apply()
                    value.text = labels[which]
                    IconStyle.apply(this, which)
                    dialog.dismiss()
                    Toast.makeText(this, "Icon updated — it may take a moment to refresh on your home screen.", Toast.LENGTH_LONG).show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun bind(seekId: Int, valId: Int, init: Int, render: (Int) -> String, save: (Int) -> Unit) {
        val seek = findViewById<SeekBar>(seekId)
        val label = findViewById<TextView>(valId)
        seeks.add(seek)
        seek.progress = init
        label.text = render(init)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                label.text = render(p)
                save(p)
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun setManualEnabled(enabled: Boolean) {
        manualGroup.alpha = if (enabled) 1f else 0.4f
        seeks.forEach { it.isEnabled = enabled }
    }
}
