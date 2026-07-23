package com.entity.bench

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BenchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Theme before any activity inflates, so there is never a flash of the wrong mode.
        Prefs.applyTheme(this)
        // A benchmark is usually run unplugged and away from a network, so contributions
        // queue on the phone and go out whenever the app next starts with a connection.
        if (ResultUploader.enabled(this)) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                runCatching { ResultUploader.flushQueue(this@BenchApp) }
            }
        }
    }
}
