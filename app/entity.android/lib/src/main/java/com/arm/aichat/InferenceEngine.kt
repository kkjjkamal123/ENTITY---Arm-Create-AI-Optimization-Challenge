package com.arm.aichat

import com.arm.aichat.InferenceEngine.State
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A single persisted conversation turn. [role] is "user" or "assistant".
 */
data class ChatTurn(val role: String, val text: String)

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
