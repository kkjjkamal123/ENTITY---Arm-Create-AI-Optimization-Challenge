package com.example.llama

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import androidx.appcompat.app.AppCompatDelegate
import java.io.File

// Single source of truth for every user-tunable preference, shared between the
// settings screen and MainActivity so pref keys never drift apart.
object Settings {
    const val PREFS = "entity"

    // Inference
    const val KEY_AUTO = "cfg_auto"
    const val KEY_TEMP = "cfg_temp"       // stored x100
    const val KEY_TOPK = "cfg_topk"
    const val KEY_TOPP = "cfg_topp"       // stored x100
    const val KEY_MAXTOK = "cfg_maxtok"
    const val KEY_CTX = "cfg_ctx"
    const val KEY_THREADS = "cfg_threads"
    const val KEY_ACTIVE_CTX = "active_ctx"   // context of the currently loaded model
    const val KEY_SYSTEM_PROMPT = "cfg_system_prompt"
    const val KEY_EFFICIENCY = "cfg_efficiency"
    const val KEY_FIRST_RUN = "cfg_first_run_done"
    const val KEY_PLACEMENT = "cfg_placement"  // 0 auto, 1 always pin, 2 never pin

    // Interface
    const val KEY_THEME = "theme"             // 0 system, 1 light, 2 dark
    const val KEY_PALETTE = "palette"         // 0 monochrome, 1 colour
    const val KEY_ANIM = "cfg_anim"
    const val KEY_TEXT_SIZE = "cfg_text_size" // 0 small, 1 medium, 2 large
    const val KEY_HAPTICS = "cfg_haptics"
    const val KEY_KEEP_ON = "cfg_keep_screen_on"

    // Live metrics
    const val KEY_SHOW_STATS = "show_stats"
    const val KEY_SHOW_GRAPH = "show_graph"
    const val KEY_GRAPH_FILL = "graph_fill"
    const val KEY_GRAPH_SMOOTH = "graph_smooth"
    const val KEY_STAT_TOKENS = "stat_tokens"
    const val KEY_STAT_SPEED = "stat_speed"
    const val KEY_STAT_TTFT = "stat_ttft"
    const val KEY_STAT_TEMP = "stat_temp"
    const val KEY_STAT_POWER = "stat_power"
    const val KEY_STAT_CPU = "stat_cpu"
    const val KEY_STAT_MEMORY = "stat_memory"

    // Active model (display + info card)
    const val KEY_ACTIVE_MODEL = "active_model"
    const val KEY_ACTIVE_INFO = "active_model_info"

    // Set by SettingsActivity after clearing chats so MainActivity reloads its list.
    const val KEY_CHATS_CHANGED = "chats_changed_externally"

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
    const val DEF_THEME = 0
    const val DEF_PALETTE = 0   // monochrome stays the default look
    const val DEF_TEXT_SIZE = 1
    const val DEF_HAPTICS = true
    const val DEF_KEEP_ON = true

    // Core placement. Pinning generation to the performance cores is a speed lever with
    // a power cost, and which way it lands is a property of the phone: across the
    // contributed dataset it ranges from -8.5% to +29.3% on decode and is slightly
    // NEGATIVE on tokens per watt in the median. Android's own guidance is that forcing
    // affinity stops the platform reacting to load and thermal throttling, so this is a
    // real choice rather than a strictly-better default - hence the setting.
    const val PLACEMENT_AUTO = 0    // pin, ENTITY's shipped default
    const val PLACEMENT_PINNED = 1  // always pin
    const val PLACEMENT_SYSTEM = 2  // never pin, leave placement to the scheduler
    const val DEF_PLACEMENT = PLACEMENT_AUTO

    // Whether inference should pin to the performance cluster under the current setting.
    fun pinCores(prefs: SharedPreferences): Boolean =
        prefs.getInt(KEY_PLACEMENT, DEF_PLACEMENT) != PLACEMENT_SYSTEM

    const val DEF_SYSTEM_PROMPT =
        "You are ENTITY, a helpful AI assistant running fully offline on the user's phone. " +
            "Answer questions directly and clearly in natural language. " +
            "Do not roleplay, narrate actions, or make robotic sound effects."

    val CTX_STEPS = intArrayOf(1024, 2048, 4096, 8192)
    val TEXT_SIZES_SP = floatArrayOf(13f, 15f, 17f)

    // The seven graph/stats series, in display order: pref key -> label resource.
    val STAT_KEYS = listOf(
        KEY_STAT_TOKENS to R.string.stat_tokens,
        KEY_STAT_SPEED to R.string.stat_speed,
        KEY_STAT_TTFT to R.string.stat_ttft,
        KEY_STAT_TEMP to R.string.stat_temp,
        KEY_STAT_POWER to R.string.stat_power,
        KEY_STAT_CPU to R.string.stat_cpu,
        KEY_STAT_MEMORY to R.string.stat_memory,
    )

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

    fun textSizeSp(prefs: SharedPreferences): Float =
        TEXT_SIZES_SP[prefs.getInt(KEY_TEXT_SIZE, DEF_TEXT_SIZE).coerceIn(0, 2)]

    fun nightMode(mode: Int) = when (mode) {
        1 -> AppCompatDelegate.MODE_NIGHT_NO
        2 -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }
}

// Where imported .gguf files live. Shared by the chat screen (picker/import)
// and the settings screen (manage/delete).
object ModelStore {
    fun dirs(ctx: Context): List<File> =
        listOfNotNull(ctx.getExternalFilesDir("models"), File(ctx.filesDir, "models"))
            .onEach { if (!it.exists()) it.mkdirs() }

    fun scan(ctx: Context): List<File> =
        dirs(ctx)
            .flatMap { it.listFiles { f -> f.extension == "gguf" }?.toList() ?: emptyList() }
            .distinctBy { it.name }
            .sortedBy { it.name }

    fun sizeLabel(bytes: Long): String =
        if (bytes >= 1_000_000_000L) String.format("%.2f GB", bytes / 1e9)
        else String.format("%.0f MB", bytes / 1e6)

    /** Display name of a picked document, forced to end in .gguf so scan() will find it. */
    fun safeName(cr: ContentResolver, uri: Uri): String {
        var name: String? = null
        runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) name = c.getString(i)
                }
            }
        }
        var clean = (name ?: "model-${System.currentTimeMillis()}").substringAfterLast('/').trim()
        if (!clean.endsWith(".gguf", ignoreCase = true)) clean += ".gguf"
        return clean
    }

    /** Size of a picked document, or -1 when the provider does not report one. */
    fun sizeOf(cr: ContentResolver, uri: Uri): Long {
        var size = -1L
        runCatching {
            cr.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.SIZE)
                    if (i >= 0 && !c.isNull(i)) size = c.getLong(i)
                }
            }
        }
        return size
    }
}
