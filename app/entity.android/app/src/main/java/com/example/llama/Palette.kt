package com.example.llama

import android.app.Activity
import android.content.Context
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt

/**
 * Which set of colours the app is wearing.
 *
 * MONOCHROME is the original ENTITY look: neutrals only, hierarchy from weight, case and
 * inversion. COLOUR keeps exactly the same layout, spacing and luminance discipline and
 * only changes hue - tinted surfaces, one accent on the primary action, and separate
 * danger/success tones for consequence.
 *
 * Nothing in the app reads a colour resource directly; everything reads a theme attribute
 * (see res/values/attrs.xml), so switching palettes is switching one theme.
 */
object Palette {

    const val MONOCHROME = 0
    const val COLOUR = 1

    fun current(ctx: Context): Int =
        ctx.getSharedPreferences(Settings.PREFS, Context.MODE_PRIVATE)
            .getInt(Settings.KEY_PALETTE, Settings.DEF_PALETTE)

    fun themeFor(palette: Int): Int =
        if (palette == COLOUR) R.style.Theme_Entity_Chroma else R.style.Theme_Entity

    /**
     * Must run before setContentView, so every view inflates from the chosen palette.
     * Activities call this first thing in onCreate.
     */
    fun apply(activity: Activity) {
        activity.setTheme(themeFor(current(activity)))
    }

    /** Resolve a palette attribute to an actual colour for code-built views. */
    @ColorInt
    fun color(ctx: Context, @AttrRes attr: Int): Int {
        val tv = TypedValue()
        ctx.theme.resolveAttribute(attr, tv, true)
        return if (tv.resourceId != 0) androidx.core.content.ContextCompat.getColor(ctx, tv.resourceId)
        else tv.data
    }
}
