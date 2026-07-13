package com.example.llama

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

class InfoActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_info)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        findViewById<TextView>(R.id.info_text).text = CONTENT
    }

    companion object {
        private val CONTENT = """
            ENTITY runs large language models fully offline on this phone, tuned for its
            Arm CPU. Below is everything it does to run fast and light on a 6 GB device.

            ● Big-core affinity
            Inference is pinned to the phone's performance cluster using sched_setaffinity.
            The cores are picked by reading each core's live max frequency, so it targets the
            fast cluster on any device and keeps work off the slow efficiency cores.

            ● Runtime-selected CPU backend
            Seven Arm CPU backend variants ship with Arm KleidiAI kernels, and the best one the
            CPU actually supports (SME, SVE, i8mm, dotprod) is loaded at startup.
            arm64 only; no unused x86 code.

            ● Adaptive context
            The context window is chosen from the model size and free RAM, so any runnable
            model fits without running out of memory.

            ● Auto (optimized) mode
            One switch applies all of the above automatically. Turn it off to tune temperature,
            top-k, top-p, max tokens, context size and thread count by hand.

            ● Thermal-aware guard
            Under thermal pressure it eases off slightly between tokens to hold a steadier
            sustained speed instead of hitting a hard throttle mid-answer.

            ● Live metrics
            Tokens, tokens/sec, time-to-first-token, temperature, power draw (W) and free
            memory — shown as a stats bar and an overlayable graph, each series toggleable.

            ● Why Q4_0 wins on this CPU
            The 4-bit dotprod kernel path is better optimized on Arm than a "smaller" 3-bit
            format, so Q4_0 is often faster despite using more bits per weight.

            ● Fully offline
            No network, no cloud. Everything runs on-device.
        """.trimIndent()
    }
}
