package com.arm.aichat

import com.arm.aichat.InferenceEngine.State
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A single persisted conversation turn. [role] is "user" or "assistant".
 */
data class ChatTurn(val role: String, val text: String)

/**
 * What one generated answer actually cost.
 *
 * [prefillMs] and [decodeMs] are the time spent inside the native calls only, so neither
 * includes markdown rendering or any other UI work on the collecting side - a slow list
 * redraw must not be able to show up as a slow model. That makes the two rates comparable
 * with the benchmark screen's pp/tg figures, which are measured the same way.
 *
 * The split matters because the two halves are bound by different things: prefill is a
 * compute-bound GEMM over the whole prompt at once, decode is bandwidth-bound and reads
 * every weight once per token. A quantization that helps one usually hurts the other.
 */
data class TurnStats(
    val promptTokens: Int,
    val generatedTokens: Int,
    val prefillMs: Long,
    val decodeMs: Long,
    val contextUsed: Int,
    val contextSize: Int,
) {
    /** Prompt processing rate, tokens/second. 0 when the prompt was already cached. */
    val prefillToksPerS: Double
        get() = if (prefillMs <= 0L) 0.0 else promptTokens * 1000.0 / prefillMs

    /** Generation rate, tokens/second - the number a user perceives as "speed". */
    val decodeToksPerS: Double
        get() = if (decodeMs <= 0L) 0.0 else generatedTokens * 1000.0 / decodeMs

    val totalTokens: Int get() = promptTokens + generatedTokens
}

/**
 * Interface defining the core LLM inference operations.
 */
interface InferenceEngine {
    /**
     * Current state of the inference engine
     */
    val state: StateFlow<State>

    /**
     * Load a model from the given path.
     *
     * @throws UnsupportedArchitectureException if model architecture not supported
     */
    suspend fun loadModel(pathToModel: String)

    /**
     * Sends a system prompt to the loaded model
     */
    suspend fun setSystemPrompt(systemPrompt: String)

    /**
     * Sends a user prompt to the loaded model and returns a Flow of generated tokens.
     */
    fun sendUserPrompt(message: String, predictLength: Int = DEFAULT_PREDICT_LENGTH): Flow<String>

    /**
     * Token accounting for the most recently completed [sendUserPrompt], or null before one
     * has run. Valid once the flow completes; read it earlier and the decode half is still
     * accumulating. Overwritten by the next turn, so a caller that wants to keep it must
     * copy it out.
     */
    fun lastTurnStats(): TurnStats?

    /**
     * Runs a benchmark with the specified parameters.
     */
    suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int = 1): String

    /**
     * Sets context size, thread count and sampler params used on the next load.
     * nThreads == 0 means auto. Call before [loadModel].
     *
     * [pinCores] false runs inference with no core affinity and no pinned thread
     * pool, leaving placement to the scheduler. Only the benchmark's threads-only
     * ablation arm uses it; every shipped path keeps the default.
     */
    suspend fun applyConfig(
        nCtx: Int,
        nThreads: Int,
        temp: Float,
        topK: Int,
        topP: Float,
        pinCores: Boolean = true,
        /**
         * [adpf] true opens an Android performance hint session over the decode thread and
         * reports each step's real duration, so the platform can raise clocks and place
         * threads against a deadline instead of reacting to load after the fact. It is the
         * only route an unprivileged app has into those kernel subsystems. Silently
         * inactive on a device whose vendor did not implement the HAL.
         */
        adpf: Boolean = true,
    )

    /**
     * Updates the live sampler (temperature / top-k / top-p) without reloading.
     */
    suspend fun applySampler(temp: Float, topK: Int, topP: Float)

    /**
     * Clears the conversation context and re-applies the system prompt, so the
     * next question starts fresh without a full model reload.
     */
    suspend fun newConversation(systemPrompt: String)

    /**
     * Rebuilds the engine's conversation state from persisted [history] turns
     * without generating, so the next [sendUserPrompt] continues seamlessly.
     * If the history exceeds the context, the system prompt plus the most
     * recent turns that fit are kept. Call between [newConversation] (or
     * [setSystemPrompt]) and [sendUserPrompt].
     */
    suspend fun primeHistory(history: List<ChatTurn>)

    /**
     * Persists the active conversation's KV state to [path] so a later
     * [restoreState] can continue it without re-decoding the whole history.
     * Returns false (no-op) unless a model is loaded. Never throws.
     */
    suspend fun saveState(path: String, systemPrompt: String): Boolean

    /**
     * Restores KV state from [path] in place of re-decoding [history]. Returns true
     * only when the saved state matches the current model and system prompt and fits
     * the context; otherwise returns false and the caller should fall back to
     * [newConversation] + [primeHistory]. Never throws.
     */
    suspend fun restoreState(path: String, systemPrompt: String, history: List<ChatTurn>): Boolean

    /**
     * llama's system-info string: the CPU features / backend variant actually in
     * use on this device. Empty until the native library has finished loading.
     */
    fun cpuInfo(): String

    /**
     * Unloads the currently loaded model.
     */
    fun cleanUp()

    /**
     * Cleans up resources when the engine is no longer needed.
     */
    fun destroy()

    /**
     * States of the inference engine
     */
    sealed class State {
        object Uninitialized : State()
        object Initializing : State()
        object Initialized : State()

        object LoadingModel : State()
        object UnloadingModel : State()
        object ModelReady : State()

        object Benchmarking : State()
        object ProcessingSystemPrompt : State()
        object ProcessingUserPrompt : State()

        object Generating : State()

        data class Error(val exception: Exception) : State()
    }

    companion object {
        const val DEFAULT_PREDICT_LENGTH = 1024
    }
}

val State.isUninterruptible
    get() = this is State.Initializing ||
        this is State.LoadingModel ||
        this is State.UnloadingModel ||
        this is State.Benchmarking ||
        this is State.ProcessingSystemPrompt ||
        this is State.ProcessingUserPrompt

val State.isModelLoaded: Boolean
    get() = this is State.ModelReady ||
        this is State.Benchmarking ||
        this is State.ProcessingSystemPrompt ||
        this is State.ProcessingUserPrompt ||
        this is State.Generating

class UnsupportedArchitectureException : Exception()
