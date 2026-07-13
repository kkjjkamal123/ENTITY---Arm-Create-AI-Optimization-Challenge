package com.example.llama

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.format.DateUtils
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.gguf.FileType
import com.arm.aichat.gguf.GgufMetadata
import com.arm.aichat.gguf.GgufMetadataReader
import com.arm.aichat.isModelLoaded
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

class MainActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var statsBar: TextView
    private lateinit var graph: MetricsGraphView
    private lateinit var messagesRv: RecyclerView
    private lateinit var userInputEt: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var loadContainer: View
    private lateinit var loadLabel: TextView
    private lateinit var loadProgress: ProgressBar
    private lateinit var chipTemp: TextView
    private lateinit var chipMem: TextView
    private lateinit var emptyState: View

    private lateinit var engine: InferenceEngine
    private lateinit var prefs: android.content.SharedPreferences

    private val vm: ChatViewModel by viewModels()
    private lateinit var messageAdapter: MessageAdapter

    private var isModelReady = false
    private var isLoadingModel = false
    private var optimizeDialogShown = false
    private var modelInfoText: String? = null

    // View-only render throttles (safe to reset across config changes).
    private var lastRenderMs = 0L
    private var lastChipMs = 0L

    // Rolling window to smooth the noisy instantaneous battery-current reading.
    private val wattsWindow = ArrayDeque<Double>()

    private data class Snap(val temp: Double, val watts: Double, val gb: Double)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences(Settings.PREFS, Context.MODE_PRIVATE)
        Anim.setUserEnabled(prefs.getBoolean(Settings.KEY_ANIM, Settings.DEF_ANIM))
        AppCompatDelegate.setDefaultNightMode(nightMode(prefs.getInt(KEY_THEME, 0)))

        setContentView(R.layout.activity_main)

        // Keep the launcher icon in sync with the theme when set to Auto.
        IconStyle.apply(this, prefs.getInt(IconStyle.KEY, IconStyle.AUTO))

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        statsBar = findViewById(R.id.stats_bar)
        graph = findViewById(R.id.graph)
        for ((_, key) in statItems) graph.setSeriesEnabled(key, prefs.getBoolean(key, true))
        graph.visibility = if (prefs.getBoolean(KEY_SHOW_GRAPH, false)) View.VISIBLE else View.GONE

        messageAdapter = MessageAdapter(
            vm.messages,
            onCopy = { copyToClipboard(it) },
            onRegenerate = { vm.regenerateLastAnswer() }
        )
        messagesRv = findViewById(R.id.messages)
        messagesRv.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        messagesRv.adapter = messageAdapter
        messagesRv.itemAnimator = null

        userInputEt = findViewById(R.id.user_input)
        sendButton = findViewById(R.id.fab)
        loadContainer = findViewById(R.id.load_container)
        loadLabel = findViewById(R.id.load_label)
        loadProgress = findViewById(R.id.load_progress)
        chipTemp = findViewById(R.id.chip_temp)
        chipMem = findViewById(R.id.chip_mem)
        emptyState = findViewById(R.id.empty_state)

        sendButton.setOnClickListener {
            when {
                vm.genState.value != ChatViewModel.GenPhase.IDLE -> vm.stop()
                isModelReady -> handleUserInput()
                else -> showModelPicker()
            }
        }

        lifecycleScope.launch {
            engine = withContext(Dispatchers.Default) { AiChat.getInferenceEngine(applicationContext) }
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                engine.state.collect { onEngineState(it) }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.listVersion.collect { onListChanged() } }
                launch { vm.genState.collect { onGenPhase(it) } }
                launch { vm.stats.collect { onStats(it) } }
            }
        }

        updateEmptyState()
        refreshStatsBar()
        vm.restoreLatest()
    }

    override fun onResume() {
        super.onResume()
        Anim.setUserEnabled(prefs.getBoolean(Settings.KEY_ANIM, Settings.DEF_ANIM))
        // Sampler changes from Settings apply live; ctx/threads apply on next load.
        if (isModelReady && ::engine.isInitialized) {
            val v = Settings.load(prefs)
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { engine.applySampler(v.temp, v.topK, v.topP) }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_model_info).isVisible = modelInfoText != null
        menu.findItem(R.id.action_show_stats).isChecked = prefs.getBoolean(KEY_SHOW_STATS, false)
        menu.findItem(R.id.action_show_graph).isChecked = prefs.getBoolean(KEY_SHOW_GRAPH, false)
        for ((id, key) in statItems) {
            menu.findItem(id).isChecked = prefs.getBoolean(key, true)
        }
        menu.findItem(themeItems[prefs.getInt(KEY_THEME, 0)]).isChecked = true
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_new_chat -> newChat()
            R.id.action_conversations -> showConversations()
            R.id.action_select_model -> showModelPicker()
            R.id.action_model_info -> showModelInfo()
            R.id.action_benchmark -> openBenchmark()
            R.id.action_show_stats -> toggle(item, KEY_SHOW_STATS)
            R.id.action_show_graph -> toggleGraph(item)
            in statItems.keys -> statItems[item.itemId]?.let { toggleStat(item, it) }
            in themeItems -> applyTheme(themeItems.indexOf(item.itemId))
            R.id.action_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            R.id.action_about -> startActivity(Intent(this, InfoActivity::class.java))
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun toggle(item: MenuItem, key: String) {
        val value = !prefs.getBoolean(key, false)
        prefs.edit().putBoolean(key, value).apply()
        item.isChecked = value
        refreshStatsBar()
    }

    private fun toggleStat(item: MenuItem, key: String) {
        val value = !prefs.getBoolean(key, true)
        prefs.edit().putBoolean(key, value).apply()
        item.isChecked = value
        graph.setSeriesEnabled(key, value)
        refreshStatsBar()
    }

    private fun toggleGraph(item: MenuItem) {
        val value = !prefs.getBoolean(KEY_SHOW_GRAPH, false)
        prefs.edit().putBoolean(KEY_SHOW_GRAPH, value).apply()
        item.isChecked = value
        graph.visibility = if (value) View.VISIBLE else View.GONE
    }

    private fun applyTheme(mode: Int) {
        prefs.edit().putInt(KEY_THEME, mode).apply()
        AppCompatDelegate.setDefaultNightMode(nightMode(mode))
    }

    private fun onEngineState(state: InferenceEngine.State) {
        val loaded = state.isModelLoaded
        isModelReady = loaded
        if (loaded && modelInfoText == null && !isLoadingModel) {
            modelInfoText = prefs.getString(KEY_ACTIVE_INFO, null)
            prefs.getString(KEY_ACTIVE_MODEL, null)?.let { supportActionBar?.subtitle = it }
            invalidateOptionsMenu()
        }
        if (!isLoadingModel && vm.genState.value == ChatViewModel.GenPhase.IDLE) {
            setSendMode(false)
            userInputEt.isEnabled = loaded
            userInputEt.hint = getString(if (loaded) R.string.hint_type_message else R.string.hint_pick_model)
        }
        // First launch: once the native library is up (so its CPU info is real), offer
        // the device-tuned settings. The dialog itself marks first-run done either way.
        if (state !is InferenceEngine.State.Uninitialized &&
            state !is InferenceEngine.State.Initializing &&
            !optimizeDialogShown && DeviceOptimizer.isFirstRun(prefs)
        ) {
            optimizeDialogShown = true
            DeviceOptimizer.show(this, engine.cpuInfo())
        }
    }

    private fun onListChanged() {
        messageAdapter.notifyDataSetChanged()
        if (vm.messages.isNotEmpty()) messagesRv.scrollToPosition(vm.messages.size - 1)
        updateEmptyState()
    }

    private fun onGenPhase(phase: ChatViewModel.GenPhase) {
        val generating = phase != ChatViewModel.GenPhase.IDLE
        setSendMode(generating)
        userInputEt.isEnabled = isModelReady && !generating
        when (phase) {
            ChatViewModel.GenPhase.PRIMING -> showLoad(getString(R.string.priming_context))
            else -> if (!isLoadingModel) hideLoad()
        }
        if (phase == ChatViewModel.GenPhase.IDLE) {
            val last = vm.messages.size - 1
            if (last >= 0 && !vm.messages[last].isUser) {
                messageAdapter.notifyItemChanged(last, MessageAdapter.PAYLOAD_DONE)
                messagesRv.scrollToPosition(last)
            }
        }
    }

    private fun onStats(s: ChatViewModel.GenStats) {
        if (vm.genState.value != ChatViewModel.GenPhase.GENERATING) return
        val now = SystemClock.elapsedRealtime()

        val last = vm.messages.size - 1
        if (last >= 0 && !vm.messages[last].isUser && now - lastRenderMs >= RENDER_INTERVAL_MS) {
            lastRenderMs = now
            messageAdapter.notifyItemChanged(last, MessageAdapter.PAYLOAD_TEXT)
            messagesRv.scrollToPosition(last)
        }

        val statsVisible = prefs.getBoolean(KEY_SHOW_STATS, false)
        val graphVisible = graph.visibility == View.VISIBLE
        val chipsDue = now - lastChipMs >= CHIP_INTERVAL_MS
        if (!statsVisible && !graphVisible && !chipsDue) return
        val snap = snapMetrics()
        if (chipsDue) { updateChips(snap); lastChipMs = now }
        if (statsVisible) updateStatsBar(snap, s)
        if (graphVisible) {
            graph.addSample(
                s.tokens.toFloat(), speed(s).toFloat(), ttftMs(s).toFloat(),
                snap.temp.toFloat(), snap.watts.toFloat(), snap.gb.toFloat()
            )
        }
    }

    private fun showModelPicker() {
        if (!::engine.isInitialized) {
            Toast.makeText(this, "Engine is still starting…", Toast.LENGTH_SHORT).show()
            return
        }

        val models = scanModels()

        // Empty state: a real Import button (a dialog can't show a message AND a list).
        if (models.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Add a model")
                .setMessage("No models yet. Import a .gguf file from your storage — ENTITY copies it in for you. No file-manager copying needed.")
                .setPositiveButton("Import from device…") { _, _ -> getContent.launch(arrayOf("*/*")) }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        val labels = models.map { it.name } + "Import from device…"
        AlertDialog.Builder(this)
            .setTitle("Select a model")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < models.size) loadModel(models[which]) else getContent.launch(arrayOf("*/*"))
            }
            .show()
    }

    private fun showConversations() {
        lifecycleScope.launch {
            val convs = withContext(Dispatchers.IO) { vm.listConversations() }
            if (convs.isEmpty()) {
                Toast.makeText(this@MainActivity, "No conversations yet.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val now = System.currentTimeMillis()
            val labels = convs.map {
                val title = it.title.ifBlank { "Untitled" }
                val rel = DateUtils.getRelativeTimeSpanString(it.updatedAt, now, DateUtils.MINUTE_IN_MILLIS)
                "$title\n$rel"
            }.toTypedArray()
            val adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, labels)
            val dialog = AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.menu_conversations)
                .setAdapter(adapter) { _, which -> vm.switchTo(convs[which].id) }
                .setPositiveButton("New chat") { _, _ -> newChat() }
                .setNegativeButton("Close", null)
                .create()
            dialog.show()
            dialog.listView.setOnItemLongClickListener { _, _, which, _ ->
                dialog.dismiss()
                showConversationActions(convs[which])
                true
            }
        }
    }

    private fun showConversationActions(conv: ConversationRow) {
        AlertDialog.Builder(this)
            .setTitle(conv.title.ifBlank { "Untitled" })
            .setItems(arrayOf("Rename", "Delete")) { _, which ->
                if (which == 0) renameConversation(conv) else confirmDeleteConversation(conv)
            }
            .show()
    }

    private fun renameConversation(conv: ConversationRow) {
        val input = EditText(this).apply {
            setText(conv.title)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename conversation")
            .setView(dialogInputContainer(input))
            .setPositiveButton("Save") { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) vm.renameConversation(conv.id, t)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteConversation(conv: ConversationRow) {
        AlertDialog.Builder(this)
            .setTitle("Delete conversation?")
            .setMessage(conv.title.ifBlank { "Untitled" })
            .setPositiveButton("Delete") { _, _ -> vm.deleteConversation(conv.id) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun dialogInputContainer(input: EditText): View {
        val pad = (20 * resources.displayMetrics.density).toInt()
        return FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
    }

    private fun modelDirs() =
        listOfNotNull(getExternalFilesDir("models"), File(filesDir, "models"))
            .onEach { if (!it.exists()) it.mkdirs() }

    private fun scanModels(): List<File> =
        modelDirs()
            .flatMap { it.listFiles { f -> f.extension == "gguf" }?.toList() ?: emptyList() }
            .distinctBy { it.name }
            .sortedBy { it.name }

    private val getContent = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { importAndLoad(it) } }

    private fun importAndLoad(uri: Uri) {
        setLoadingUi()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val target = File(modelDirs().first(), pickedFileName(uri))
                // reuse an already-imported copy; otherwise stream it into private storage
                if (!target.exists() || target.length() == 0L) {
                    val total = pickedFileSize(uri)
                    val input = contentResolver.openInputStream(uri)
                        ?: error("Can't read that file. Pick the .gguf again from your storage.")
                    input.use { copyWithProgress(it, target, total) }
                }
                prepareModel(target)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isLoadingModel = false
                    userInputEt.isEnabled = isModelReady
                    userInputEt.hint = getString(if (isModelReady) R.string.hint_type_message else R.string.hint_pick_model)
                    sendButton.isEnabled = true
                    Toast.makeText(this@MainActivity, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Resolve the real display name of a picked document so the model keeps its
    // own name (e.g. "Llama-3.2-3B-Instruct-Q4_0.gguf") instead of a timestamp.
    private fun pickedFileName(uri: Uri): String {
        var name: String? = null
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
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

    private fun loadModel(model: File) {
        setLoadingUi()
        lifecycleScope.launch(Dispatchers.IO) { prepareModel(model) }
    }

    private suspend fun prepareModel(model: File) {
        try {
            withContext(Dispatchers.Main) {
                isLoadingModel = true
                showLoad("Loading ${model.nameWithoutExtension}…")
            }

            val state = engine.state.value
            if (state is InferenceEngine.State.ModelReady || state is InferenceEngine.State.Error) {
                withContext(Dispatchers.Main) {
                    isModelReady = false
                    modelInfoText = null
                }
                runCatching { engine.cleanUp() }
            }

            val v = Settings.load(prefs)
            val ctx = if (v.auto) adaptiveContext(model) else v.ctx
            val threads = when {
                v.efficiency -> 2      // power-saving: cap at 2 threads regardless of auto/manual
                v.auto -> 0
                else -> v.threads
            }
            // Remember the active context so the benchmark can restore it afterwards.
            prefs.edit().putInt(Settings.KEY_ACTIVE_CTX, ctx).apply()
            engine.applyConfig(ctx, threads, v.temp, v.topK, v.topP)

            engine.loadModel(model.path)
            engine.setSystemPrompt(Settings.systemPrompt(prefs))
            // The fresh KV holds only the system prompt; the on-screen history
            // (restored conversation) must be re-primed before the next send.
            vm.onModelReset()

            // Read the header for the model-info card (cheap: metadata only, not weights).
            val meta = runCatching {
                FileInputStream(model).use { GgufMetadataReader.create().readStructuredMetadata(it) }
            }.getOrNull()
            val info = buildModelInfo(model, ctx, threads, meta)

            withContext(Dispatchers.Main) {
                isLoadingModel = false
                isModelReady = true
                modelInfoText = info
                prefs.edit()
                    .putString(KEY_ACTIVE_MODEL, model.nameWithoutExtension)
                    .putString(KEY_ACTIVE_INFO, info)
                    .apply()
                supportActionBar?.subtitle = model.nameWithoutExtension
                userInputEt.isEnabled = true
                userInputEt.hint = getString(R.string.hint_type_message)
                sendButton.isEnabled = true
                hideLoad()
                invalidateOptionsMenu()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                isLoadingModel = false
                hideLoad()
                userInputEt.isEnabled = isModelReady
                userInputEt.hint = getString(if (isModelReady) R.string.hint_type_message else R.string.hint_pick_model)
                sendButton.isEnabled = true
                Toast.makeText(this@MainActivity, "Failed to load: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Adaptive context: fit the window to the model size and free RAM so any
    // runnable model loads without exhausting the 6 GB budget.
    private fun adaptiveContext(model: File): Int {
        val sizeGb = model.length() / 1_000_000_000.0
        val freeGb = availableGb()
        return when {
            sizeGb < 1.6 -> if (freeGb > 3.0) 8192 else 4096   // ~1B class
            else -> if (freeGb > 2.2) 4096 else 2048           // ~3B class
        }
    }

    private fun setLoadingUi() {
        isLoadingModel = true
        userInputEt.isEnabled = false
        userInputEt.hint = "Loading model…"
        sendButton.isEnabled = false
        showLoad("Loading model…")
    }

    private fun showLoad(text: String) {
        loadContainer.visibility = View.VISIBLE
        loadLabel.text = text
        loadProgress.isIndeterminate = true
    }

    private fun setLoadProgress(text: String, pct: Int) {
        loadContainer.visibility = View.VISIBLE
        loadLabel.text = "$text  $pct%"
        loadProgress.isIndeterminate = false
        loadProgress.progress = pct
    }

    private fun hideLoad() {
        loadContainer.visibility = View.GONE
    }

    // Stream a picked file into private storage, reporting copy % when the size is known.
    private fun copyWithProgress(input: InputStream, target: File, total: Long) {
        FileOutputStream(target).use { out ->
            val buf = ByteArray(1 shl 16)
            var copied = 0L
            var lastPct = -1
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                copied += n
                if (total > 0) {
                    val pct = ((copied * 100) / total).toInt().coerceIn(0, 100)
                    if (pct != lastPct) {
                        lastPct = pct
                        runOnUiThread { setLoadProgress("Importing model…", pct) }
                    }
                }
            }
        }
    }

    private fun pickedFileSize(uri: Uri): Long {
        var size = -1L
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.SIZE)
                    if (i >= 0 && !c.isNull(i)) size = c.getLong(i)
                }
            }
        }
        return size
    }

    private fun openBenchmark() {
        if (!isModelReady) {
            Toast.makeText(this, "Load a model first to benchmark it.", Toast.LENGTH_SHORT).show()
            return
        }
        if (vm.genState.value != ChatViewModel.GenPhase.IDLE) {
            Toast.makeText(this, "Stop generation before benchmarking.", Toast.LENGTH_SHORT).show()
            return
        }
        // The synthetic bench decodes over the live context; whatever KV state
        // the conversation had is gone afterwards, so force a re-prime.
        vm.onModelReset()
        startActivity(Intent(this, BenchmarkActivity::class.java).apply {
            putExtra(BenchmarkActivity.EXTRA_MODEL, supportActionBar?.subtitle?.toString())
        })
    }

    private fun showModelInfo() {
        val info = modelInfoText ?: return
        AlertDialog.Builder(this)
            .setTitle(supportActionBar?.subtitle ?: "Model info")
            .setMessage(info)
            .setPositiveButton("Close", null)
            .show()
    }

    // Human-readable model card from the GGUF header + the runtime config we chose.
    private fun buildModelInfo(model: File, ctxUsed: Int, threads: Int, meta: GgufMetadata?): String {
        val bytes = model.length()
        val sizeStr =
            if (bytes >= 1_000_000_000L) String.format("%.2f GB", bytes / 1e9)
            else String.format("%.0f MB", bytes / 1e6)
        val params = meta?.basic?.sizeLabel
        val quant = meta?.architecture?.fileType?.let { FileType.fromCode(it).label }
        val arch = meta?.architecture?.architecture ?: meta?.basic?.name
        val trainedCtx = meta?.dimensions?.contextLength
        val layers = meta?.dimensions?.blockCount
        val embed = meta?.dimensions?.embeddingSize
        val vocab = meta?.architecture?.vocabSize
        val threadStr = if (threads <= 0) "auto" else threads.toString()

        return buildString {
            appendLine("File: ${model.name}")
            appendLine("Size on disk: $sizeStr")
            params?.let { appendLine("Parameters: $it") }
            quant?.let { appendLine("Quantization: $it") }
            arch?.let { appendLine("Architecture: $it") }
            trainedCtx?.let { appendLine("Trained context: $it tokens") }
            appendLine("Running context: $ctxUsed tokens (auto-fit to free RAM)")
            layers?.let { appendLine("Layers: $it") }
            embed?.let { appendLine("Embedding size: $it") }
            vocab?.let { appendLine("Vocab: $it") }
            appendLine("Threads: $threadStr")
            // Real detection: the backend variant ggml dlopen'd differs per SoC.
            val cores = DeviceOptimizer.fastCoreCount(DeviceOptimizer.maxFreqsKhz())
            val features = runCatching { DeviceOptimizer.cpuFeatures(engine.cpuInfo()) }
                .getOrDefault(emptyList())
            append("Compute: CPU · $cores perf core${if (cores == 1) "" else "s"}")
            if (features.isNotEmpty()) append(" · ${features.joinToString(", ")}")
            append(" · KleidiAI")
        }.trim()
    }

    private fun handleUserInput() {
        val userMsg = userInputEt.text.toString().trim()
        if (userMsg.isEmpty()) return

        userInputEt.text = null
        userInputEt.isEnabled = false
        hideKeyboard()

        lastRenderMs = 0L
        graph.clear()
        vm.send(userMsg)
    }

    private fun setSendMode(generating: Boolean) {
        sendButton.setImageResource(if (generating) R.drawable.ic_stop_24 else R.drawable.outline_send_24)
        sendButton.isEnabled = true
    }

    private fun newChat() {
        vm.newConversation()
        lastRenderMs = 0L
        graph.clear()
        refreshStatsBar()
    }

    private fun refreshStatsBar() {
        val snap = snapMetrics()
        updateChips(snap)
        updateStatsBar(snap, vm.stats.value)
    }

    private fun updateChips(snap: Snap) {
        chipTemp.text = if (snap.temp > 0) "%.0f°C".format(snap.temp) else "—°C"
        chipMem.text = "%.1f GB free".format(snap.gb)
    }

    private fun updateStatsBar(snap: Snap, s: ChatViewModel.GenStats) {
        if (!prefs.getBoolean(KEY_SHOW_STATS, false)) {
            statsBar.visibility = View.GONE
            return
        }
        val parts = mutableListOf<String>()
        if (prefs.getBoolean(KEY_TOKENS, true)) parts.add("${s.tokens} tok")
        if (prefs.getBoolean(KEY_SPEED, true)) parts.add("%.1f tok/s".format(speed(s)))
        if (prefs.getBoolean(KEY_TTFT, true)) parts.add("TTFT ${ttftMs(s)}ms")
        if (prefs.getBoolean(KEY_TEMP, true)) parts.add("%.1f°C".format(snap.temp))
        if (prefs.getBoolean(KEY_POWER, true)) parts.add("%.2fW".format(snap.watts))
        if (prefs.getBoolean(KEY_MEMORY, true)) parts.add("%.1fGB free".format(snap.gb))
        statsBar.text = parts.joinToString("  ·  ")
        statsBar.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun speed(s: ChatViewModel.GenStats): Double {
        if (s.firstTokenMs == 0L) return 0.0
        val elapsed = (s.lastTokenMs.coerceAtLeast(s.firstTokenMs) - s.firstTokenMs) / 1000.0
        return if (elapsed > 0) s.tokens / elapsed else 0.0
    }

    private fun ttftMs(s: ChatViewModel.GenStats): Long =
        if (s.firstTokenMs == 0L) 0L else s.firstTokenMs - s.startMs

    // One battery read per tick, shared by the stats bar and the graph.
    private fun snapMetrics(): Snap {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val temp = if (tenths < 0) 0.0 else tenths / 10.0
        val voltageMv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val current = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val rawCurrent = if (current == Int.MIN_VALUE) 0L else current.toLong()
        val rawWatts = PowerMath.watts(rawCurrent, voltageMv)
        wattsWindow.addLast(rawWatts)
        while (wattsWindow.size > WATTS_WINDOW) wattsWindow.removeFirst()
        val watts = wattsWindow.average()

        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val gb = info.availMem / (1024.0 * 1024.0 * 1024.0)
        return Snap(temp, watts, gb)
    }

    private fun availableGb(): Double {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem / (1024.0 * 1024.0 * 1024.0)
    }

    private fun updateEmptyState() {
        emptyState.visibility = if (vm.messages.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun nightMode(mode: Int) = when (mode) {
        1 -> AppCompatDelegate.MODE_NIGHT_NO
        2 -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(userInputEt.windowToken, 0)
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("message", text))
        Toast.makeText(this, R.string.copied_confirmation, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        if (isFinishing && ::engine.isInitialized) engine.destroy()
        super.onDestroy()
    }

    companion object {
        private const val RENDER_INTERVAL_MS = 45L
        private const val CHIP_INTERVAL_MS = 1000L
        private const val WATTS_WINDOW = 5

        private const val KEY_SHOW_STATS = "show_stats"
        private const val KEY_SHOW_GRAPH = "show_graph"
        private const val KEY_TOKENS = "stat_tokens"
        private const val KEY_SPEED = "stat_speed"
        private const val KEY_TTFT = "stat_ttft"
        private const val KEY_TEMP = "stat_temp"
        private const val KEY_POWER = "stat_power"
        private const val KEY_MEMORY = "stat_memory"
        private const val KEY_THEME = "theme"
        private const val KEY_ACTIVE_MODEL = "active_model"
        private const val KEY_ACTIVE_INFO = "active_model_info"

        private val statItems = mapOf(
            R.id.stat_tokens to KEY_TOKENS,
            R.id.stat_speed to KEY_SPEED,
            R.id.stat_ttft to KEY_TTFT,
            R.id.stat_temp to KEY_TEMP,
            R.id.stat_power to KEY_POWER,
            R.id.stat_memory to KEY_MEMORY
        )

        // index 0 = system, 1 = light, 2 = dark
        private val themeItems = listOf(R.id.theme_system, R.id.theme_light, R.id.theme_dark)
    }
}
