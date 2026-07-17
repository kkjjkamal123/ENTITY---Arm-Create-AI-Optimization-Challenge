package com.example.llama

import android.app.Application
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arm.aichat.AiChat
import com.arm.aichat.ChatTurn
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

// Per-token thermal throttle. Pure int->delay mapping so it stays JVM-testable
// (no Android classes here; callers pass currentThermalStatus in as an int).
// Status ints: NONE=0 LIGHT=1 MODERATE=2 SEVERE=3 CRITICAL=4 EMERGENCY=5 SHUTDOWN=6.
object ThermalGuard {
    fun delayMs(status: Int, efficiency: Boolean): Long {
        val base = when {
            status >= 3 -> 12L  // SEVERE and above
            status == 2 -> 6L   // MODERATE
            else -> 0L          // NONE / LIGHT
        }
        return if (efficiency) base * 2 else base
    }
}

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    enum class GenPhase { IDLE, PRIMING, GENERATING }

    data class GenStats(
        val tokens: Int = 0,
        val startMs: Long = 0L,
        val firstTokenMs: Long = 0L,
        val lastTokenMs: Long = 0L,
    )

    private val prefs = app.getSharedPreferences(Settings.PREFS, Context.MODE_PRIVATE)
    private val db = ChatDb(app)
    private val engine: InferenceEngine by lazy { AiChat.getInferenceEngine(app) }

    private val _messages = mutableListOf<Message>()
    val messages: List<Message> get() = _messages

    private val _genState = MutableStateFlow(GenPhase.IDLE)
    val genState: StateFlow<GenPhase> = _genState.asStateFlow()

    private val _stats = MutableStateFlow(GenStats())
    val stats: StateFlow<GenStats> = _stats.asStateFlow()

    // Bumped on every structural change to [messages]; StateFlow replay
    // guarantees a late collector (activity recreation) still refreshes.
    private val _listVersion = MutableStateFlow(0)
    val listVersion: StateFlow<Int> = _listVersion.asStateFlow()

    private var conversationId = 0L

    // Conversation whose turns the live engine KV currently reflects; null when
    // the engine context does not match the on-screen history (fresh load,
    // switch, restore, interrupted generation).
    private var primedConversationId: Long? = null

    private var generationJob: Job? = null
    private var initialized = false

    private fun bump() {
        _listVersion.value++
    }

    // KV state files, one per conversation, in the app's private files dir. The file
    // lets the ACTIVE conversation's KV survive a switch or an app restart, so the next
    // turn continues without re-decoding the whole history. Stale/mismatched files are
    // rejected by the native header check (model / system prompt / context fit) and the
    // caller silently falls back to re-priming.
    private fun stateDir() = File(getApplication<Application>().filesDir, "kvstate").apply { mkdirs() }
    private fun stateFile(id: Long) = File(stateDir(), "state_$id.bin")

    // Persist the live engine KV for the active conversation, if the engine currently
    // reflects it. Called before switching away and on app stop; best-effort, never throws.
    private suspend fun persistActiveState() {
        val id = conversationId
        if (id == 0L || primedConversationId != id || _messages.isEmpty()) return
        runCatching { engine.saveState(stateFile(id).path, Settings.systemPrompt(prefs)) }
    }

    // Best-effort save for lifecycle stop; the coroutine may not finish before a kill,
    // in which case restore falls back to re-priming.
    fun saveActiveState() {
        viewModelScope.launch { persistActiveState() }
    }

    fun restoreLatest() {
        if (initialized) return
        initialized = true
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) {
                db.latestConversationId() ?: db.createConversation(System.currentTimeMillis())
            }
            loadInto(id)
        }
    }

    fun switchTo(id: Long) {
        if (id == conversationId) return
        viewModelScope.launch {
            generationJob?.cancelAndJoin()
            persistActiveState()
            loadInto(id)
        }
    }

    private suspend fun loadInto(id: Long) {
        conversationId = id
        val stored = withContext(Dispatchers.IO) { db.messagesFor(id) }
        _messages.clear()
        stored.forEach {
            _messages.add(Message(UUID.randomUUID().toString(), it.content, it.role == ROLE_USER))
        }
        primedConversationId = null
        bump()
    }

    fun newConversation() {
        viewModelScope.launch {
            generationJob?.cancelAndJoin()
            persistActiveState()
            val id = withContext(Dispatchers.IO) { db.createConversation(System.currentTimeMillis()) }
            conversationId = id
            _messages.clear()
            primedConversationId = null
            bump()
            if (engine.state.value is InferenceEngine.State.ModelReady) {
                _genState.value = GenPhase.PRIMING
                val ok = runCatching { engine.newConversation(Settings.systemPrompt(prefs)) }.isSuccess
                if (ok) primedConversationId = id
                _genState.value = GenPhase.IDLE
            }
        }
    }

    // The engine's KV no longer matches this conversation (model reload,
    // benchmark run); force a re-prime before the next send.
    fun onModelReset() {
        primedConversationId = null
    }

    fun send(userText: String) {
        if (generationJob?.isActive == true) return
        viewModelScope.launch {
            if (conversationId == 0L) {
                conversationId = withContext(Dispatchers.IO) { db.createConversation(System.currentTimeMillis()) }
            }
            val convId = conversationId
            val priorTurns = _messages.map {
                ChatTurn(if (it.isUser) ROLE_USER else ROLE_ASSISTANT, it.content)
            }
            _messages.add(Message(UUID.randomUUID().toString(), userText, true))
            _messages.add(Message(UUID.randomUUID().toString(), "", false))
            bump()
            withContext(Dispatchers.IO) {
                db.insertMessage(convId, ROLE_USER, userText, System.currentTimeMillis())
                db.setTitleIfEmpty(convId, title(userText))
            }
            startGeneration(userText, priorTurns)
        }
    }

    fun regenerateLastAnswer() {
        if (generationJob?.isActive == true) return
        if (_messages.isEmpty()) return
        viewModelScope.launch {
            val convId = conversationId
            if (!_messages.last().isUser) {
                _messages.removeAt(_messages.size - 1)
                withContext(Dispatchers.IO) { db.deleteLastAssistantMessage(convId) }
                bump()
            }
            if (_messages.isEmpty() || !_messages.last().isUser) return@launch
            val userText = _messages.last().content
            val priorTurns = _messages.dropLast(1).map {
                ChatTurn(if (it.isUser) ROLE_USER else ROLE_ASSISTANT, it.content)
            }
            _messages.add(Message(UUID.randomUUID().toString(), "", false))
            bump()
            primedConversationId = null
            startGeneration(userText, priorTurns)
        }
    }

    fun stop() {
        generationJob?.cancel()
    }

    fun listConversations(): List<ConversationRow> = db.listConversations()

    fun renameConversation(id: Long, title: String) {
        viewModelScope.launch { withContext(Dispatchers.IO) { db.renameConversation(id, title) } }
    }

    fun deleteConversation(id: Long) {
        viewModelScope.launch {
            if (id == conversationId) generationJob?.cancelAndJoin()
            withContext(Dispatchers.IO) {
                db.deleteConversation(id)
                runCatching { stateFile(id).delete() }
            }
            if (id == conversationId) {
                val next = withContext(Dispatchers.IO) { db.latestConversationId() }
                if (next != null) loadInto(next) else newConversation()
            }
        }
    }

    private fun startGeneration(promptToSend: String, priorTurns: List<ChatTurn>) {
        val convId = conversationId
        val assistantIndex = _messages.size - 1
        val sb = StringBuilder()
        val v = Settings.load(prefs)
        val systemPrompt = Settings.systemPrompt(prefs)
        _stats.value = GenStats(startMs = SystemClock.elapsedRealtime())
        generationJob = viewModelScope.launch {
            var completed = false
            try {
                if (primedConversationId != convId) {
                    _genState.value = GenPhase.PRIMING
                    // Try restoring the saved KV state first (skips decoding the whole
                    // history); fall back to a full re-prime if there is no valid state.
                    val restored = priorTurns.isNotEmpty() &&
                        stateFile(convId).exists() &&
                        engine.restoreState(stateFile(convId).path, systemPrompt, priorTurns)
                    if (!restored) {
                        engine.newConversation(systemPrompt)
                        if (priorTurns.isNotEmpty()) engine.primeHistory(priorTurns)
                    }
                    primedConversationId = convId
                }
                _genState.value = GenPhase.GENERATING
                engine.sendUserPrompt(promptToSend, v.maxTokens)
                    .onCompletion { cause ->
                        completed = true
                        withContext(NonCancellable + Dispatchers.IO) {
                            runCatching {
                                db.insertMessage(convId, ROLE_ASSISTANT, sb.toString(), System.currentTimeMillis())
                            }
                        }
                        if (cause != null) primedConversationId = null
                        _genState.value = GenPhase.IDLE
                    }
                    .collect { token ->
                        val s = _stats.value
                        val now = SystemClock.elapsedRealtime()
                        val first = if (s.firstTokenMs == 0L) now else s.firstTokenMs
                        sb.append(token)
                        if (conversationId == convId && assistantIndex in _messages.indices) {
                            _messages[assistantIndex] = _messages[assistantIndex].copy(content = sb.toString())
                        }
                        _stats.value = s.copy(tokens = s.tokens + 1, firstTokenMs = first, lastTokenMs = now)
                        if (v.auto && (s.tokens and 7) == 0) {
                            val d = ThermalGuard.delayMs(thermalStatus(), v.efficiency)
                            if (d > 0) delay(d)
                        }
                    }
            } catch (e: Exception) {
                if (!completed) {
                    if (conversationId == convId && assistantIndex in _messages.indices &&
                        !_messages[assistantIndex].isUser && _messages[assistantIndex].content.isEmpty()
                    ) {
                        _messages.removeAt(assistantIndex)
                        bump()
                    }
                    primedConversationId = null
                }
                _genState.value = GenPhase.IDLE
                if (e is CancellationException) throw e
            }
        }
    }

    private fun title(text: String): String {
        val t = text.trim().replace('\n', ' ')
        return if (t.length <= TITLE_LEN) t else t.take(TITLE_LEN).trimEnd() + "…"
    }

    // currentThermalStatus is a binder call; cache it ~1s so the token loop
    // isn't paying for it every 8 tokens.
    private var cachedThermal = 0
    private var cachedThermalAt = 0L

    private fun thermalStatus(): Int {
        val now = SystemClock.elapsedRealtime()
        if (now - cachedThermalAt >= THERMAL_POLL_MS) {
            val pm = getApplication<Application>().getSystemService(Context.POWER_SERVICE) as PowerManager
            cachedThermal = pm.currentThermalStatus
            cachedThermalAt = now
        }
        return cachedThermal
    }

    override fun onCleared() {
        db.close()
    }

    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        private const val TITLE_LEN = 40
        private const val THERMAL_POLL_MS = 1000L
    }
}
