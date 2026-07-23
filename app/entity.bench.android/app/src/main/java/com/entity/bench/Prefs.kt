package com.entity.bench

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate

object Prefs {
    const val NAME = "entity_bench"

    const val KEY_THEME = "theme"            // THEME_SYSTEM | THEME_LIGHT | THEME_DARK
    const val KEY_MODEL = "last_model"
    const val KEY_RUNS = "runs"
    const val KEY_MODE = "mode"              // MODE_ABLATION | MODE_SUSTAINED | MODE_SWEEP
    const val KEY_DURATION = "duration_min"
    const val KEY_EFF_ARM = "efficiency_arm"
    const val KEY_PALETTE = "palette"        // 0 monochrome, 1 colour
    const val KEY_KEEP_ON = "keep_screen_on"
    const val KEY_CONTRIBUTE = "contribute_results"
    const val KEY_SENT_RESULTS = "sent_results"   // file names already contributed

    const val DEF_PALETTE = 0                // monochrome stays the default look
    const val DEF_KEEP_ON = true             // a run must not be cut short by the lock screen
    const val DEF_CONTRIBUTE = false         // contribution is opt-in, never a default

    const val THEME_SYSTEM = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2

    const val MODE_ABLATION = 0
    const val MODE_SUSTAINED = 1
    const val MODE_SWEEP = 2

    fun get(ctx: Context): SharedPreferences = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun applyTheme(ctx: Context) {
        AppCompatDelegate.setDefaultNightMode(
            when (get(ctx).getInt(KEY_THEME, THEME_SYSTEM)) {
                THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }
}
