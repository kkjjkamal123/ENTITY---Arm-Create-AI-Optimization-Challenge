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
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

class MainActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var modelSub: TextView
    private lateinit var modelNameDrawer: TextView
    private lateinit var btnModelInfo: TextView
    private lateinit var drawerVersion: TextView
    private lateinit var statsBar: TextView
    private lateinit var graph: MetricsGraphView
    private lateinit var messagesRv: RecyclerView
    private lateinit var convRv: RecyclerView
    private lateinit var convEmpty: TextView
    private lateinit var userInputEt: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var loadContainer: View
    private lateinit var loadLabel: TextView
    private lateinit var loadProgress: ProgressBar
    private lateinit var chipTemp: TextView
    private lateinit var chipMem: TextView
    private lateinit var emptyState: View
    private lateinit var emptyHint: TextView

    private lateinit var engine: InferenceEngine
    private lateinit var prefs: android.content.SharedPreferences

    private val vm: ChatViewModel by viewModels()
    private lateinit var messageAdapter: MessageAdapter
    private val convAdapter = ConvAdapter()

    private var isModelReady = false
    private var isLoadingModel = false
    private var optimizeDialogShown = false
    private var modelInfoText: String? = null
    private var lastPhase = ChatViewModel.GenPhase.IDLE

    // View-only render throttles (safe to reset across config changes).
    private var lastRenderMs = 0L
    private var lastChipMs = 0L
    private var lastSampleMs = 0L
    private var lastProcessCpuMs = 0L
    private var lastProcessCpuWallMs = 0L

    // Rolling window to smooth the noisy instantaneous battery-current reading.
    private val wattsWindow = ArrayDeque<Double>()

    // Process CPU can exceed 100% when native worker threads occupy multiple cores.
    private data class Snap(val temp: Double, val watts: Double, val cpuPercent: Double, val gb: Double)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences(Settings.PREFS, Context.MODE_PRIVATE)
        Anim.setUserEnabled(prefs.getBoolean(Settings.KEY_ANIM, Settings.DEF_ANIM))

        setContentView(R.layout.activity_main)

        // Keep the launcher icon in sync with the theme when set to Auto.
        IconStyle.apply(this, prefs.getInt(IconStyle.KEY, IconStyle.AUTO))

        drawerLayout = findViewById(R.id.drawer_layout)
        // The window already paints the bars mono; DrawerLayout's default
        // colorPrimaryDark status-bar fill would draw an ink band over it.
        drawerLayout.setStatusBarBackground(null)
        modelSub = findViewById(R.id.model_sub)
        modelNameDrawer = findViewById(R.id.model_name_drawer)
        btnModelInfo = findViewById(R.id.btn_model_info)
        drawerVersion = findViewById(R.id.drawer_version)
        statsBar = findViewById(R.id.stats_bar)
        graph = findViewById(R.id.graph)
        userInputEt = findViewById(R.id.user_input)
        sendButton = findViewById(R.id.btn_send)
        loadContainer = findViewById(R.id.load_container)
        loadLabel = findViewById(R.id.load_label)
        loadProgress = findViewById(R.id.load_progress)
        chipTemp = findViewById(R.id.chip_temp)
        chipMem = findViewById(R.id.chip_mem)
        emptyState = findViewById(R.id.empty_state)
        emptyHint = findViewById(R.id.empty_hint)

        drawerVersion.text = "v${BuildConfig.VERSION_NAME} · ${getString(R.string.home_sub)}"

        messageAdapter = MessageAdapter(
            vm.messages,
            onCopy = { copyToClipboard(it) },
            onRegenerate = { vm.regenerateLastAnswer() }
        )
        messageAdapter.textSizeSp = Settings.textSizeSp(prefs)
        messagesRv = findViewById(R.id.messages)
        messagesRv.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        messagesRv.adapter = messageAdapter
        messagesRv.itemAnimator = null

        convRv = findViewById(R.id.conv_list)
        convEmpty = findViewById(R.id.conv_empty)
        convRv.layoutManager = LinearLayoutManager(this)
        convRv.adapter = convAdapter

        sendButton.setOnClickListener {
            when {
                vm.genState.value != ChatViewModel.GenPhase.IDLE -> vm.stop()
                isModelReady -> handleUserInput()
                else -> showModelPicker()
            }
        }

        wireHeaderAndDrawer()
        applyMetricsPrefs()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

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

        // Header stays at NO MODEL until the engine confirms a loaded model
        // (onEngineState restores the name from prefs on activity recreation).
        updateEmptyState()
        refreshStatsBar()
        vm.restoreLatest()
    }

    private fun wireHeaderAndDrawer() {
        findViewById<TextView>(R.id.btn_menu).setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }
        findViewById<TextView>(R.id.btn_new_top).setOnClickListener { newChat() }
        findViewById<View>(R.id.header_title).setOnClickListener { showModelPicker() }

        findViewById<TextView>(R.id.btn_drawer_new).setOnClickListener {
            newChat()
            drawerLayout.closeDrawer(GravityCompat.START)
        }
        findViewById<TextView>(R.id.btn_conv_edit).setOnClickListener { showSelectConversations() }
        findViewById<View>(R.id.row_model).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            showModelPicker()
        }
        btnModelInfo.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            showModelInfo()
        }
        findViewById<TextView>(R.id.btn_bench).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            openBenchmark()
        }
        findViewById<TextView>(R.id.btn_share).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            shareChat()
        }
        findViewById<TextView>(R.id.btn_settings).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<TextView>(R.id.btn_about).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, InfoActivity::class.java))
        }

        drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) = refreshConversations()
        })
    }

    // Metrics prefs are edited in Settings; re-apply whenever we come back.
    private fun applyMetricsPrefs() {
        for ((key, _) in Settings.STAT_KEYS) graph.setSeriesEnabled(key, prefs.getBoolean(key, true))
        graph.setStyle(
            prefs.getBoolean(Settings.KEY_GRAPH_FILL, false),
            prefs.getBoolean(Settings.KEY_GRAPH_SMOOTH, false)
        )
        graph.visibility =
            if (prefs.getBoolean(Settings.KEY_SHOW_GRAPH, false)) View.VISIBLE else View.GONE
    }

    override fun onResume() {
        super.onResume()
        Anim.setUserEnabled(prefs.getBoolean(Settings.KEY_ANIM, Settings.DEF_ANIM))
        applyMetricsPrefs()
        refreshStatsBar()
        val size = Settings.textSizeSp(prefs)
        if (messageAdapter.textSizeSp != size) {
            messageAdapter.textSizeSp = size
            messageAdapter.notifyDataSetChanged()
        }
        if (prefs.getBoolean(Settings.KEY_CHATS_CHANGED, false)) {
            prefs.edit().putBoolean(Settings.KEY_CHATS_CHANGED, false).apply()
            vm.reloadFromDb()
        }
        // Sampler changes from Settings apply live; ctx/threads apply on next load.
        if (isModelReady && ::engine.isInitialized) {
            val v = Settings.load(prefs)
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { engine.applySampler(v.temp, v.topK, v.topP) }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Persist the active conversation's KV so the next launch continues it without
        // re-decoding the history. Best-effort: a hard kill just falls back to re-priming.
        if (isModelReady) vm.saveActiveState()
    }

    private fun shareChat() {
        if (vm.messages.isEmpty()) {
            Toast.makeText(this, "Nothing to share yet.", Toast.LENGTH_SHORT).show()
            return
        }
        val text = buildString {
            for (m in vm.messages) {
                append(if (m.isUser) "You: " else "ENTITY: ")
                append(m.content.trim())
                append("\n\n")
            }
        }.trim()
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, getString(R.string.drawer_share)))
    }

    private fun onEngineState(state: InferenceEngine.State) {
        val loaded = state.isModelLoaded
        isModelReady = loaded
        if (loaded && modelInfoText == null && !isLoadingModel) {
            modelInfoText = prefs.getString(Settings.KEY_ACTIVE_INFO, null)
            setModelName(prefs.getString(Settings.KEY_ACTIVE_MODEL, null))
        }
        if (!isLoadingModel && vm.genState.value == ChatViewModel.GenPhase.IDLE) {
            setSendMode(false)
            userInputEt.isEnabled = loaded
            userInputEt.hint = getString(if (loaded) R.string.hint_type_message else R.string.hint_pick_model)
        }
        updateEmptyState()
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

    private fun setModelName(name: String?) {
        if (name.isNullOrBlank()) {
            modelSub.text = getString(R.string.no_model)
            modelNameDrawer.text = getString(R.string.no_model)
            btnModelInfo.visibility = View.GONE
        } else {
            modelSub.text = name
            modelNameDrawer.text = name
            btnModelInfo.visibility = if (modelInfoText != null) View.VISIBLE else View.GONE
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
        keepScreenOn(generating && prefs.getBoolean(Settings.KEY_KEEP_ON, Settings.DEF_KEEP_ON))
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
            if (lastPhase == ChatViewModel.GenPhase.GENERATING) {
                haptic(HapticFeedbackConstants.CONTEXT_CLICK)
                // Sampled stats can lag by up to SAMPLE_INTERVAL_MS; settle on exact finals.
                refreshStatsBar()
            }
        }
        lastPhase = phase
    }

    private fun keepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun haptic(constant: Int) {
        if (prefs.getBoolean(Settings.KEY_HAPTICS, Settings.DEF_HAPTICS)) {
            sendButton.performHapticFeedback(constant)
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

        // Metrics run on a fixed clock, never per token: snapMetrics() costs three
        // binder IPCs and addSample() a full graph redraw, and per-token they steal
        // big-core time from the pinned decode threads (measured 18 -> 14 tok/s
        // with the graph visible).
        if (now - lastSampleMs < SAMPLE_INTERVAL_MS) return
        val statsVisible = prefs.getBoolean(Settings.KEY_SHOW_STATS, false)
        val graphVisible = graph.visibility == View.VISIBLE
        val chipsDue = now - lastChipMs >= CHIP_INTERVAL_MS
        if (!statsVisible && !graphVisible && !chipsDue) return
        lastSampleMs = now
        val snap = snapMetrics()
        if (chipsDue) { updateChips(snap); lastChipMs = now }
        if (statsVisible) updateStatsBar(snap, s)
        if (graphVisible) {
            graph.addSample(
                s.tokens.toFloat(), speed(s).toFloat(), ttftMs(s).toFloat(),
                snap.temp.toFloat(), snap.watts.toFloat(), snap.cpuPercent.toFloat(), snap.gb.toFloat()
            )
        }
    }

    // ---------- Conversations (drawer) ----------

    private fun refreshConversations() {
        lifecycleScope.launch {
            val convs = withContext(Dispatchers.IO) { vm.listConversations() }
            convAdapter.submit(convs, vm.activeConversationId)
            convEmpty.visibility = if (convs.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun showSelectConversations() {
        lifecycleScope.launch {
            val convs = withContext(Dispatchers.IO) { vm.listConversations() }
            if (convs.isEmpty()) {
                Toast.makeText(this@MainActivity, "No conversations yet.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val labels = convs.map { it.title.ifBlank { getString(R.string.conv_untitled) } }.toTypedArray()
            val checked = BooleanArray(convs.size)
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Select conversations")
                .setMultiChoiceItems(labels, checked) { _, i, v -> checked[i] = v }
                .setPositiveButton("Delete") { _, _ ->
                    val picked = convs.filterIndexed { i, _ -> checked[i] }
                    if (picked.isNotEmpty()) confirmDeleteMultiple(picked)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun confirmDeleteMultiple(convs: List<ConversationRow>) {
        val what = if (convs.size == 1) convs[0].title.ifBlank { getString(R.string.conv_untitled) }
                   else "${convs.size} conversations"
        AlertDialog.Builder(this)
            .setTitle("Delete $what?")
            .setPositiveButton("Delete") { _, _ ->
                convs.forEach { vm.deleteConversation(it.id) }
                refreshConversations()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showConversationActions(conv: ConversationRow) {
        AlertDialog.Builder(this)
            .setTitle(conv.title.ifBlank { getString(R.string.conv_untitled) })
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
                if (t.isNotEmpty()) {
                    vm.renameConversation(conv.id, t)
                    refreshConversationsSoon()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteConversation(conv: ConversationRow) {
        AlertDialog.Builder(this)
            .setTitle("Delete conversation?")
            .setMessage(conv.title.ifBlank { getString(R.string.conv_untitled) })
            .setPositiveButton("Delete") { _, _ ->
                vm.deleteConversation(conv.id)
                refreshConversationsSoon()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // Rename/delete run through the VM's coroutines; give them a beat to land.
    private fun refreshConversationsSoon() {
        convRv.postDelayed({ refreshConversations() }, 150)
    }

    private fun dialogInputContainer(input: EditText): View {
        val pad = Ui.dp(this, 20)
        return FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
    }

    private inner class ConvAdapter : RecyclerView.Adapter<ConvAdapter.VH>() {
        private var items: List<ConversationRow> = emptyList()
        private var activeId = 0L

        fun submit(convs: List<ConversationRow>, active: Long) {
            items = convs
            activeId = active
            notifyDataSetChanged()
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.conv_title)
            val time: TextView = view.findViewById(R.id.conv_time)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_conversation, parent, false)
            return VH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val c = items[position]
            val active = c.id == activeId
            holder.title.text = c.title.ifBlank { getString(R.string.conv_untitled) }
            holder.time.text = DateUtils.getRelativeTimeSpanString(
                c.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
            )
            // Active conversation = solid inversion, the design's selection idiom.
            holder.itemView.setBackgroundResource(if (active) R.drawable.bg_fill else R.drawable.btn_outline)
            val textColor = if (active) Ui.bg(this@MainActivity) else Ui.fg(this@MainActivity)
            holder.title.setTextColor(textColor)
            holder.time.setTextColor(textColor)
            holder.itemView.setOnClickListener {
                vm.switchTo(c.id)
                drawerLayout.closeDrawer(GravityCompat.START)
            }
            holder.itemView.setOnLongClickListener {
                showConversationActions(c)
                true
            }
        }
    }

    // ---------- Model handling ----------

    private fun showModelPicker() {
        if (!::engine.isInitialized) {
            Toast.makeText(this, "Engine is still starting…", Toast.LENGTH_SHORT).show()
            return
        }

        val models = ModelStore.scan(this)

        // Empty state: a real Import button (a dialog can't show a message AND a list).
        if (models.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.model_add_title)
                .setMessage(R.string.model_add_body)
                .setPositiveButton(R.string.model_import) { _, _ -> getContent.launch(arrayOf("*/*")) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }

        val labels = models.map { "${it.name}\n${ModelStore.sizeLabel(it.length())}" } +
            getString(R.string.model_import)
        AlertDialog.Builder(this)
            .setTitle(R.string.model_picker_title)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < models.size) loadModel(models[which]) else getContent.launch(arrayOf("*/*"))
            }
            .show()
    }

    private val getContent = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { importAndLoad(it) } }

    private fun importAndLoad(uri: Uri) {
        setLoadingUi()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val target = File(ModelStore.dirs(this@MainActivity).first(), pickedFileName(uri))
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
                    hideLoad()
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
                    .putString(Settings.KEY_ACTIVE_MODEL, model.nameWithoutExtension)
                    .putString(Settings.KEY_ACTIVE_INFO, info)
                    .apply()
                setModelName(model.nameWithoutExtension)
                userInputEt.isEnabled = true
                userInputEt.hint = getString(R.string.hint_type_message)
                sendButton.isEnabled = true
                hideLoad()
                updateEmptyState()
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
            putExtra(BenchmarkActivity.EXTRA_MODEL, prefs.getString(Settings.KEY_ACTIVE_MODEL, null))
        })
    }

    private fun showModelInfo() {
        val info = modelInfoText ?: return
        val view = layoutInflater.inflate(R.layout.dialog_model_info, null)
        // The advisor verdict is embedded in the info string by buildModelInfo;
        // surface it as a status pill instead of burying it in the text.
        view.findViewById<TextView>(R.id.model_info_pill).apply {
            when {
                info.contains("KleidiAI active") -> {
                    setText(R.string.kleidiai_active)
                    setBackgroundResource(R.drawable.bg_fill)
                    setTextColor(Ui.bg(this@MainActivity))
                }
                info.contains("KleidiAI NOT used") -> {
                    setText(R.string.kleidiai_not_used)
                    setBackgroundResource(R.drawable.bg_dashed)
                    setTextColor(Ui.fg(this@MainActivity))
                }
                else -> visibility = View.GONE
            }
        }
        view.findViewById<TextView>(R.id.model_info_text).text = info
        AlertDialog.Builder(this)
            .setTitle(prefs.getString(Settings.KEY_ACTIVE_MODEL, null) ?: "Model info")
            .setView(view)
            .setPositiveButton("Close", null)
            .show()
    }

    // Human-readable model card from the GGUF header + the runtime config we chose.
    private fun buildModelInfo(model: File, ctxUsed: Int, threads: Int, meta: GgufMetadata?): String {
        val params = meta?.basic?.sizeLabel
        val fileType = meta?.architecture?.fileType?.let { FileType.fromCode(it) }
        val quant = fileType?.label
        val arch = meta?.architecture?.architecture ?: meta?.basic?.name
        val trainedCtx = meta?.dimensions?.contextLength
        val layers = meta?.dimensions?.blockCount
        val embed = meta?.dimensions?.embeddingSize
        val vocab = meta?.architecture?.vocabSize
        val threadStr = if (threads <= 0) "auto" else threads.toString()

        return buildString {
            appendLine("File: ${model.name}")
            appendLine("Size on disk: ${ModelStore.sizeLabel(model.length())}")
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

            // Only claim KleidiAI when the loaded quantization can actually reach it.
            // KleidiAI ships kernels for Q4_0 and Q8_0 only; anything else runs on generic
            // ggml no matter which backend variant was loaded. Saying "KleidiAI" regardless
            // would be telling the user their model is Arm-accelerated when it is not.
            when {
                fileType == null -> Unit
                fileType.kleidiAiAccelerated -> append(" · KleidiAI active")
                else -> {
                    append(" · KleidiAI NOT used")
                    appendLine()
                    appendLine()
                    appendLine(
                        "Arm's KleidiAI kernels only cover Q4_0 and Q8_0. This model is " +
                            "${fileType.label}, so matmuls fall back to generic kernels and the " +
                            "CPU's i8mm/dotprod paths sit idle."
                    )
                    append(
                        "A Q4_0 build of the same model processes prompts far faster on this " +
                            "phone (measured: 116 vs 43 tok/s on a Dimensity 7300, cutting " +
                            "time-to-first-token on a 512-token prompt from ~12s to ~4.4s). " +
                            "Decode speed is bandwidth-bound and stays about the same."
                    )
                }
            }
        }.trim()
    }

    // ---------- Chat ----------

    private fun handleUserInput() {
        val userMsg = userInputEt.text.toString().trim()
        if (userMsg.isEmpty()) return

        userInputEt.text = null
        userInputEt.isEnabled = false
        hideKeyboard()
        haptic(HapticFeedbackConstants.CONFIRM)

        lastRenderMs = 0L
        graph.clear()
        vm.send(userMsg)
    }

    private fun setSendMode(generating: Boolean) {
        sendButton.setImageResource(if (generating) R.drawable.ic_stop_24 else R.drawable.outline_send_24)
        sendButton.contentDescription = getString(if (generating) R.string.stop else R.string.send)
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
        chipMem.text = "%.1fGB free".format(snap.gb)
    }

    private fun updateStatsBar(snap: Snap, s: ChatViewModel.GenStats) {
        if (!prefs.getBoolean(Settings.KEY_SHOW_STATS, false)) {
            statsBar.visibility = View.GONE
            return
        }
        val parts = mutableListOf<String>()
        if (prefs.getBoolean(Settings.KEY_STAT_TOKENS, true)) parts.add("${s.tokens}tok")
        if (prefs.getBoolean(Settings.KEY_STAT_SPEED, true)) parts.add("%.1ft/s".format(speed(s)))
        if (prefs.getBoolean(Settings.KEY_STAT_TTFT, true)) parts.add("TTFT ${ttftMs(s)}ms")
        if (prefs.getBoolean(Settings.KEY_STAT_TEMP, true)) parts.add("%.1f°C".format(snap.temp))
        if (prefs.getBoolean(Settings.KEY_STAT_POWER, true)) parts.add("%.2fW".format(snap.watts))
        if (prefs.getBoolean(Settings.KEY_STAT_CPU, true)) parts.add("CPU %.0f%%".format(snap.cpuPercent))
        if (prefs.getBoolean(Settings.KEY_STAT_MEMORY, true)) parts.add("%.1fGB".format(snap.gb))
        statsBar.text = parts.joinToString(" · ")
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

        val now = SystemClock.elapsedRealtime()
        val cpuMs = android.os.Process.getElapsedCpuTime()
        val elapsedMs = now - lastProcessCpuWallMs
        val cpuPercent = if (lastProcessCpuWallMs > 0L && elapsedMs > 0L) {
            (cpuMs - lastProcessCpuMs).coerceAtLeast(0L) * 100.0 / elapsedMs
        } else {
            0.0
        }
        lastProcessCpuWallMs = now
        lastProcessCpuMs = cpuMs
        return Snap(temp, watts, cpuPercent, gb)
    }

    private fun availableGb(): Double {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem / (1024.0 * 1024.0 * 1024.0)
    }

    private fun updateEmptyState() {
        emptyState.visibility = if (vm.messages.isEmpty()) View.VISIBLE else View.GONE
        emptyHint.setText(if (isModelReady) R.string.empty_hint_ready else R.string.empty_hint_no_model)
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
        private const val SAMPLE_INTERVAL_MS = 500L
        private const val WATTS_WINDOW = 5
    }
}
