package com.example.llama

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

class EntityApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Theme before any activity inflates, so there is never a flash of the wrong mode.
        val prefs = getSharedPreferences(Settings.PREFS, Context.MODE_PRIVATE)
        AppCompatDelegate.setDefaultNightMode(
            Settings.nightMode(prefs.getInt(Settings.KEY_THEME, Settings.DEF_THEME))
        )
    }
}
