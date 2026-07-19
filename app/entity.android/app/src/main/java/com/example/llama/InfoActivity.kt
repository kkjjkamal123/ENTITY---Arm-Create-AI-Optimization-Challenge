package com.example.llama

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class InfoActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_info)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.info_text).text = CONTENT
        findViewById<TextView>(R.id.info_version).text =
            "ENTITY v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · arm64 · no network"
    }

    companion object {
        private val CONTENT = """
            ENTITY runs large language models fully offline on this phone, tuned for its
            Arm CPU. Below is everything it does to run fast and light on a 6 GB device.

            BIG-CORE AFFINITY
            Inference is pinned to the phone's performance cluster using sched_setaffinity.
            The cores are picked by reading each core's live max frequency, so it targets the
            fast cluster on any device and keeps work off the slow efficiency cores.

            RUNTIME-SELECTED CPU BACKEND
            Seven Arm CPU backend variants ship with Arm KleidiAI kernels, and the best one the
            CPU actually supports (SME, SVE, i8mm, dotprod) is loaded at startup.
            arm64 only; no unused x86 code.

            ADAPTIVE CONTEXT
            The context window is chosen from the model size and free RAM, so any runnable
            model fits without running out of memory.

            AUTO (OPTIMIZED) MODE
            One switch applies all of the above automatically. Turn it off to tune temperature,
            top-k, top-p, max tokens, context size and thread count by hand.

            THERMAL-AWARE GUARD
            Under thermal pressure it eases off slightly between tokens to hold a steadier
            sustained speed instead of hitting a hard throttle mid-answer.

            KV SESSION CACHE
            Each conversation's decoded state is saved and restored across switches and app
            restarts, so returning to a long chat does not re-decode its whole history.

            LIVE METRICS
            Tokens, tokens/sec, time-to-first-token, temperature, power draw (W), app CPU and
            free memory — a stats bar and an overlayable graph, each series toggleable.

            WHY Q4_0 WINS ON THIS CPU
            The 4-bit dotprod kernel path is better optimized on Arm than a "smaller" 3-bit
            format, so Q4_0 is often faster despite using more bits per weight.

            FULLY OFFLINE
            No network, no cloud. Everything runs on-device.
        """.trimIndent()
    }
}
