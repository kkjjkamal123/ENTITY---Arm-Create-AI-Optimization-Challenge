package com.entity.bench

import android.app.Application

class BenchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Theme before any activity inflates, so there is never a flash of the wrong mode.
        Prefs.applyTheme(this)
    }
}
