package com.example.llama

import android.content.Context
import android.view.View
import android.view.animation.DecelerateInterpolator

// Central gate for every animation call site. Returns "off" when the user
// disables animations in Settings or when the system animator duration scale is
// zero (Remove-animations accessibility setting), so nothing bypasses either.
object Anim {

    private const val ENTER_MS = 220L
    private const val ENTER_DY_DP = 12f

    private val decel = DecelerateInterpolator(1.4f)

    @Volatile private var userEnabled = true

    fun setUserEnabled(on: Boolean) {
        userEnabled = on
    }

    fun enabled(context: Context): Boolean {
        if (!userEnabled) return false
        val scale = android.provider.Settings.Global.getFloat(
            context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        )
        return scale > 0f
    }

    fun enter(view: View) {
        if (!enabled(view.context)) {
            clear(view)
            return
        }
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = ENTER_DY_DP * view.resources.displayMetrics.density
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(ENTER_MS)
            .setInterpolator(decel)
            .start()
    }

    fun clear(view: View) {
        view.animate().cancel()
        if (view.alpha != 1f) view.alpha = 1f
        if (view.translationY != 0f) view.translationY = 0f
    }
}
