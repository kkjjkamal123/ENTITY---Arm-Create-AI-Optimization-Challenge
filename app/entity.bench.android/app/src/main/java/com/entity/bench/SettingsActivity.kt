package com.entity.bench

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

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
        styleTheme()
    }

    private fun styleTheme() {
        val current = Prefs.get(this).getInt(Prefs.KEY_THEME, Prefs.THEME_SYSTEM)
        Ui.seg(this, findViewById(R.id.theme_system), current == Prefs.THEME_SYSTEM)
        Ui.seg(this, findViewById(R.id.theme_light), current == Prefs.THEME_LIGHT)
        Ui.seg(this, findViewById(R.id.theme_dark), current == Prefs.THEME_DARK)
    }
}
