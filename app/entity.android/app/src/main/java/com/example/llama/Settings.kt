package com.example.llama

import android.content.SharedPreferences

// Single source of truth for the tunable inference config, shared between the
// settings screen and MainActivity so pref keys never drift apart.
object Settings {
    const val PREFS = "entity"

    const val KEY_AUTO = "cfg_auto"
    const val KEY_TEMP = "cfg_temp"       // stored x100
    const val KEY_TOPK = "cfg_topk"
    const val KEY_TOPP = "cfg_topp"       // stored x100
    const val KEY_MAXTOK = "cfg_maxtok"
    const val KEY_CTX = "cfg_ctx"
    const val KEY_THREADS = "cfg_threads"
    const val KEY_ACTIVE_CTX = "active_ctx"   // context of the currently loaded model
    const val KEY_SYSTEM_PROMPT = "cfg_system_prompt"
    const val KEY_ANIM = "cfg_anim"
    const val KEY_EFFICIENCY = "cfg_efficiency"
    const val KEY_FIRST_RUN = "cfg_first_run_done"

    const val DEF_AUTO = true
    const val DEF_TEMP = 30
    const val DEF_TOPK = 40
    const val DEF_TOPP = 95
    const val DEF_MAXTOK = 1024
    const val DEF_CTX = 4096
    const val DEF_THREADS = 4
    const val DEF_ANIM = true
    const val DEF_EFFICIENCY = false
    const val DEF_FIRST_RUN = false

    const val DEF_SYSTEM_PROMPT =
        "You are ENTITY, a helpful AI assistant running fully offline on the user's phone. " +
            "Answer questions directly and clearly in natural language. " +
            "Do not roleplay, narrate actions, or make robotic sound effects."

    val CTX_STEPS = intArrayOf(1024, 2048, 4096, 8192)

    data class Values(
        val auto: Boolean,
        val temp: Float,
        val topK: Int,
        val topP: Float,
        val maxTokens: Int,
        val ctx: Int,
        val threads: Int,
        val efficiency: Boolean,
    )

    fun load(prefs: SharedPreferences) = Values(
        auto = prefs.getBoolean(KEY_AUTO, DEF_AUTO),
        temp = prefs.getInt(KEY_TEMP, DEF_TEMP) / 100f,
        topK = prefs.getInt(KEY_TOPK, DEF_TOPK),
        topP = prefs.getInt(KEY_TOPP, DEF_TOPP) / 100f,
        maxTokens = prefs.getInt(KEY_MAXTOK, DEF_MAXTOK),
        ctx = prefs.getInt(KEY_CTX, DEF_CTX),
        threads = prefs.getInt(KEY_THREADS, DEF_THREADS),
        efficiency = prefs.getBoolean(KEY_EFFICIENCY, DEF_EFFICIENCY),
    )

    fun systemPrompt(prefs: SharedPreferences): String {
        val stored = prefs.getString(KEY_SYSTEM_PROMPT, DEF_SYSTEM_PROMPT) ?: DEF_SYSTEM_PROMPT
        return stored.ifBlank { DEF_SYSTEM_PROMPT }
    }
}
