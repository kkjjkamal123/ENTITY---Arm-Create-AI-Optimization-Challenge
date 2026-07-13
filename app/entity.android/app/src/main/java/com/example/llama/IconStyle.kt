package com.example.llama

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

// Switches the launcher icon between the black-background and white-background
// logos via two activity-aliases. Exactly one alias is ever enabled, so the app
// never disappears from the launcher.
object IconStyle {
    const val KEY = "icon_style"
    const val AUTO = 0
    const val BLACK = 1
    const val WHITE = 2

    private const val KEY_APPLIED = "icon_applied"   // 0 = black, 1 = white
    // Aliases resolve against the code namespace, NOT the applicationId.
    private const val CLASS_PKG = "com.example.llama"
    private const val BLACK_ALIAS = ".MainBlack"
    private const val WHITE_ALIAS = ".MainWhite"

    // Auto follows the phone theme: dark → black-bg icon, light → white-bg icon.
    private fun wantsWhite(ctx: Context, mode: Int): Boolean = when (mode) {
        WHITE -> true
        BLACK -> false
        else -> !isNight(ctx)
    }

    private fun isNight(ctx: Context) =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    fun apply(ctx: Context, mode: Int) {
        val target = if (wantsWhite(ctx, mode)) 1 else 0
        val prefs = ctx.getSharedPreferences(Settings.PREFS, Context.MODE_PRIVATE)
        // Manifest ships with the black alias enabled, so treat that as the baseline.
        if (prefs.getInt(KEY_APPLIED, 0) == target) return

        // Never let an icon-switch failure crash the app (this runs on launch).
        try {
            val pm = ctx.packageManager
            // Always enable the target before disabling the other one.
            if (target == 1) {
                setEnabled(pm, ctx, WHITE_ALIAS, true)
                setEnabled(pm, ctx, BLACK_ALIAS, false)
            } else {
                setEnabled(pm, ctx, BLACK_ALIAS, true)
                setEnabled(pm, ctx, WHITE_ALIAS, false)
            }
            prefs.edit().putInt(KEY_APPLIED, target).apply()
        } catch (e: Exception) {
            android.util.Log.w("IconStyle", "icon switch failed", e)
        }
    }

    private fun setEnabled(pm: PackageManager, ctx: Context, alias: String, on: Boolean) {
        pm.setComponentEnabledSetting(
            ComponentName(ctx.packageName, CLASS_PKG + alias),
            if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }
}
