package com.example.llama

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Keeps content out from under the status bar, the navigation bar and any display cutout.
 *
 * From targetSdk 35 Android draws every app edge-to-edge and stops honouring
 * `android:fitsSystemWindows` on ordinary containers - the attribute only ever worked on
 * a few inset-aware layouts such as DrawerLayout and CoordinatorLayout. The plain
 * LinearLayouts this app is built from therefore received no padding at all, so rules ran
 * the full width of the panel and text sat hard against the rounded corners of the screen.
 *
 * Padding is applied to the container rather than to each child, so a scrolling list still
 * scrolls *under* the bars while its content never rests beneath them.
 */
object Insets {

    /**
     * @param includeIme also pad for the on-screen keyboard. Only for screens with a text
     *   field pinned to the bottom - on the others the keyboard never covers anything.
     * @param top pass false when a view sits below something that already consumed the
     *   status-bar inset, so the gap is not counted twice.
     */
    fun pad(
        view: View,
        top: Boolean = true,
        bottom: Boolean = true,
        sides: Boolean = true,
        includeIme: Boolean = false,
    ) {
        val basePadLeft = view.paddingLeft
        val basePadTop = view.paddingTop
        val basePadRight = view.paddingRight
        val basePadBottom = view.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            var mask = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            if (includeIme) mask = mask or WindowInsetsCompat.Type.ime()
            val i = windowInsets.getInsets(mask)
            // Add to whatever padding the layout already declared instead of replacing it,
            // or every screen would lose its own 16dp gutter the moment insets arrived.
            v.setPadding(
                basePadLeft + if (sides) i.left else 0,
                basePadTop + if (top) i.top else 0,
                basePadRight + if (sides) i.right else 0,
                basePadBottom + if (bottom) i.bottom else 0,
            )
            windowInsets
        }
        ViewCompat.requestApplyInsets(view)
    }
}
