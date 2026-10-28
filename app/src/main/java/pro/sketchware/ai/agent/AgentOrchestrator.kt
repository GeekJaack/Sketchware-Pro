package pro.sketchware.ai.agent

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.gson.JsonObject
import pro.sketchware.ai.config.AgentAutonomyMode
import pro.sketchware.ai.config.AiConfigManager
import pro.sketchware.ai.observe.RuntimeLogHarness
import pro.sketchware.ai.project.ProjectGateway
import pro.sketchware.ai.provider.LlmClient
import pro.sketchware.ai.provider.LlmToolCall
import pro.sketchware.ai.provider.LlmTurnMessage
import pro.sketchware.ai.tools.SketchwareToolRegistry
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object AgentOrchestrator {

    interface Listener {
        fun onChatEntryAdded(scId: String, entry: AgentChatEntry)
        fun onPlanUpdated(scId: String, plan: AgentPlan)
        fun onStatusChanged(scId: String, isRunning: Boolean, statusText: String, step: Int, maxSteps: Int)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val listeners = CopyOnWriteArrayList<Listener>()

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var currentStatusText: String = "Idle"
        private set

    @Volatile
    var currentStep: Int = 0
        private set

    @Volatile
    var currentMaxSteps: Int = 30
        private set

    private val cancelRequested = AtomicBoolean(false)

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun cancelRun() {
        if (isRunning) {
            cancelRequested.set(true)
            currentStatusText = "Membatalkan eksekusi..."
        }
    }

    fun notifyExternalChatEntry(scId: String, entry: AgentChatEntry) {
        dispatchEntry(scId, entry)
    }

    fun startUserTask(
        context: Context,
        scId: String,
        userPrompt: String,
        activeScreenName: String? = null
    ) {
        if (isRunning) return
        val appCtx = context.applicationContext
        RuntimeLogHarness.ensureRegistered(appCtx)
        val config = AiConfigManager(appCtx)
        val unlimited = config.unlimitedSteps
        val maxSteps = if (unlimited) 0 else config.maxStepsPerRun
        currentMaxSteps = maxSteps
        currentStep = 0
        isRunning = true
        cancelRequested.set(false)

        // Start foreground service so long-horizon tasks survive backgrounding
        AgentForegroundService.start(appCtx, scId, "Memulai AI Agent (Unlimited Long-Horizon)...")

        val userEntry = AgentChatEntry(
            id = UUID.randomUUID().toString(),
            type = ChatEntryType.USER,
            title = "Anda (${config.autonomyMode.title})",
            body = userPrompt
        )
        AgentMemoryStore.appendChatEntry(scId, userEntry)
        dispatchEntry(scId, userEntry)
        dispatchStatus(scId, true, "Menganalisis proyek & merencanakan langkah...", 1, maxSteps)

        executor.execute {
            runLoop(appCtx, config, scId, userPrompt, activeScreenName, maxSteps, unlimited)
        }
    }

    private fun runLoop(
        appCtx: Context,
        config: AiConfigManager,
        scId: String,
        userPrompt: String,
        activeScreenName: String?,
        maxSteps: Int,
        unlimited: Boolean
    ) {
        try {
            ProjectGateway.ensureProjectLoaded(scId)
            val mode = config.autonomyMode

            if (mode.allowsWriteTools && config.autoBackupBeforeRun) {
                try {
                    val cpPath = ProjectGateway.createCheckpoint(scId, "before_ai_task")
                    val cpEntry = AgentChatEntry(
                        id = UUID.randomUUID().toString(),
                        type = ChatEntryType.STATUS,
                        title = "Auto-Checkpoint Dibuat",
                        body = "Snapshot proyek tersimpan di: $cpPath"
                    )
                    AgentMemoryStore.appendChatEntry(scId, cpEntry)
                    dispatchEntry(scId, cpEntry)
                } catch (_: Throwable) {
                }
            }

            val llmClient = LlmClient(config)
            val tools = SketchwareToolRegistry.getToolsForMode(mode)

            // Seed conversation history from recent chat entries so follow-up prompts have context
            val turnMessages = mutableListOf<LlmTurnMessage>()
            val recentEntries = AgentMemoryStore.loadChatEntries(scId)
                .filter { it.type == ChatEntryType.USER || it.type == ChatEntryType.ASSISTANT }
                .dropLast(1) // Drop the current userEntry we just added
                .takeLast(6)

            for (e in recentEntries) {
                turnMessages.add(
                    LlmTurnMessage(
                        role = if (e.type == ChatEntryType.USER) "user" else "assistant",
                        content = e.body.take(1500)
                    )
                )
            }
            turnMessages.add(LlmTurnMessage(role = "user", content = userPrompt))

            var step = 1
            var continuationEpoch = 1
            val maxContinuationEpochs = if (unlimited) Int.MAX_VALUE else 4
            var prematureStopNudges = 0
            var compileVerifyNudges = 0
            var wroteToProjectInThisRun = false
            var buildVerifiedInThisRun = false

            fun stepPrefix(s: Int, ep: Int): String {
                return if (unlimited) {
                    if (ep > 1) "Langkah $s · ∞ [Epoch $ep]" else "Langkah $s · ∞"
                } else {
                    val epLbl = if (ep > 1) " [Sesi $ep/$maxContinuationEpochs]" else ""
                    "Langkah $s/$maxSteps$epLbl"
                }
            }

            while ((unlimited || step <= maxSteps) && !cancelRequested.get()) {
                currentStep = step
                val prefix = stepPrefix(step, continuationEpoch)
                dispatchStatus(scId, true, "$prefix: Berpikir (${config.getModelId()})...", step, maxSteps)

                val systemPrompt = buildSystemPrompt(scId, mode, activeScreenName, config.autoBuildAndHeal, config.agentLanguage)
                val compactedMessages = compactTurnMessages(turnMessages, aggressive = false)

                val response = llmClient.generateTurn(
                    systemPrompt = systemPrompt,
                    messages = compactedMessages,
                    tools = tools,
                    shouldCancel = { cancelRequested.get() },
                    transformMessagesOnRetry = { attempt, _ ->
                        compactTurnMessages(turnMessages, aggressive = attempt >= 2)
                    },
                    onRetry = { attempt, maxAttempts, reason ->
                        val retryMsg = "$prefix: $reason — Mencoba ulang ($attempt/$maxAttempts)..."
                        dispatchStatus(scId, true, retryMsg, step, maxSteps)
                        val retryEntry = AgentChatEntry(
                            id = UUID.randomUUID().toString(),
                            type = ChatEntryType.STATUS,
                            title = "Auto-Retry Langkah $step ($attempt/$maxAttempts)",
                            body = "Terdeteksi gangguan sementara: $reason. Memadatkan konteks lama & mencoba kembali secara otomatis..."
                        )
                        AgentMemoryStore.appendChatEntry(scId, retryEntry)
                        dispatchEntry(scId, retryEntry)
                    }
                )

                if (cancelRequested.get()) break

                if (response.text.isNotBlank()) {
                    val assistantEntry = AgentChatEntry(
                        id = UUID.randomUUID().toString(),
                        type = ChatEntryType.ASSISTANT,
                        title = if (unlimited) "AI Agent (Step $step · ∞)" else "AI Agent (Step $step)",
                        body = response.text
                    )
                    AgentMemoryStore.appendChatEntry(scId, assistantEntry)
                    dispatchEntry(scId, assistantEntry)
                }

                if (response.toolCalls.isEmpty()) {
                    // 1. Premature-Stop Harness Guard: if tasks in Long-Horizon Plan are still PENDING or IN_PROGRESS, keep working until finish!
                    val currentPlan = AgentMemoryStore.loadPlan(scId)
                    val unfinished = currentPlan.tasks.filter {
                        it.status == TaskStatus.PENDING || it.status == TaskStatus.IN_PROGRESS
                    }
                    if (mode.allowsWriteTools && unfinished.isNotEmpty() && prematureStopNudges < 6 && !cancelRequested.get()) {
                        prematureStopNudges++
                        if (response.text.isNotBlank()) {
                            turnMessages.add(LlmTurnMessage(role = "assistant", content = response.text))
                        }
                        val unfinishedSummary = unfinished.joinToString(", ") { "${it.id}: ${it.title} [${it.status}]" }
                        turnMessages.add(
                            LlmTurnMessage(
                                role = "user",
                                content = "[HARNESS UNLIMITED LONG-HORIZON GUARD] Masih ada task di Long-Horizon Plan yang belum selesai ($unfinishedSummary). Jangan berhenti dulu—bekerjalah secara otonom hingga tuntas! Panggil tool yang diperlukan untuk menyelesaikan seluruh task, perbarui status plan ke COMPLETED, dan jalankan save_and_verify_compile."
                            )
                        )
                        step++
                        continue
                    }

                    // 2. Auto-Compile Verification Guard: if project was modified and autoBuildAndHeal is on, ensure save_and_verify_compile succeeded!
                    if (mode.allowsWriteTools && config.autoBuildAndHeal && wroteToProjectInThisRun && !buildVerifiedInThisRun && compileVerifyNudges < 3 && !cancelRequested.get()) {
                        compileVerifyNudges++
                        if (response.text.isNotBlank()) {
                            turnMessages.add(LlmTurnMessage(role = "assistant", content = response.text))
                        }
                        turnMessages.add(
                            LlmTurnMessage(
                                role = "user",
                                content = "[HARNESS FINISH VERIFICATION GUARD] Anda telah mengubah proyek pada sesi ini tetapi belum menjalankan verifikasi kompilasi hingga BUILD SUKSES. Panggil `save_and_verify_compile` sekarang dan lakukan self-healing bila ditemukan error sebelum menyelesaikan pekerjaan!"
                            )
                        )
                        step++
                        continue
                    }
                    break
                }

                turnMessages.add(
                    LlmTurnMessage(
                        role = "assistant",
                        content = response.text,
                        toolCalls = response.toolCalls
                    )
                )

                for (tc in response.toolCalls) {
                    if (cancelRequested.get()) break

                    val callEntry = AgentChatEntry(
                        id = UUID.randomUUID().toString(),
                        type = ChatEntryType.TOOL_CALL,
                        title = "Tool Call: ${tc.name}",
                        body = tc.arguments.toString()
                    )
                    AgentMemoryStore.appendChatEntry(scId, callEntry)
                    dispatchEntry(scId, callEntry)
                    dispatchStatus(scId, true, "$prefix: Menjalankan ${tc.name}...", step, maxSteps)

                    val isMutatingTool = SketchwareToolRegistry.isWriteTool(tc.name) ||
                        (tc.name == "reconstruct_apk_project" && tc.arguments.get("action")?.asString.equals("full_clone", ignoreCase = true))

                    val toolOutput = if (!mode.allowsWriteTools && isMutatingTool) {
                        "Error: Tool '${tc.name}' mengubah proyek/berkas dan diblokir pada mode ${mode.title} (read-only). " +
                            "Gunakan tool baca-saja, atau minta pengguna berpindah ke mode tulis (Copilot/Agent/Autopilot)."
                    } else {
                        if (isMutatingTool) {
                            wroteToProjectInThisRun = true
                            buildVerifiedInThisRun = false
                        }
                        SketchwareToolRegistry.executeTool(
                            context = appCtx,
                            scId = scId,
                            toolName = tc.name,
                            args = tc.arguments
                        ) { progressMsg ->
                            dispatchStatus(scId, true, "$prefix: $progressMsg", step, maxSteps)
                        }
                    }

                    if (tc.name == "update_long_horizon_plan") {
                        dispatchPlan(scId, AgentMemoryStore.loadPlan(scId))
                    }
                    if (tc.name == "save_and_verify_compile" && toolOutput.startsWith("BUILD SUKSES!")) {
                        buildVerifiedInThisRun = true
                    }

                    val truncatedUiOutput = if (toolOutput.length > 1800) {
                        toolOutput.take(1800) + "\n... (${toolOutput.length} chars total)"
                    } else {
                        toolOutput
                    }
                    val resultEntry = AgentChatEntry(
                        id = UUID.randomUUID().toString(),
                        type = ChatEntryType.TOOL_RESULT,
                        title = "Result: ${tc.name}",
                        body = truncatedUiOutput
                    )
                    AgentMemoryStore.appendChatEntry(scId, resultEntry)
                    dispatchEntry(scId, resultEntry)

                    turnMessages.add(
                        LlmTurnMessage(
                            role = "tool",
                            content = toolOutput.take(8000),
                            toolCallId = tc.id,
                            toolName = tc.name
                        )
                    )
                }

                // Persist project state after every tool batch
                if (mode.allowsWriteTools) {
                    try {
                        ProjectGateway.saveProjectToDisk(appCtx, scId)
                        ProjectGateway.notifyUiChanged(scId, activeScreenName)
                    } catch (_: Throwable) {
                    }
                }

                step++

                // Sliding-Window Episodic Compaction for Unlimited Long-Horizon Mode:
                // Every ~18 tool steps (turnMessages.size >= 38), roll over older tool turns so the LLM context stays lean & fast forever!
                if (unlimited && turnMessages.size >= 38 && !cancelRequested.get()) {
                    continuationEpoch++
                    val currentPlan = AgentMemoryStore.loadPlan(scId)
                    val unfinished = currentPlan.tasks.filter {
                        it.status == TaskStatus.PENDING || it.status == TaskStatus.IN_PROGRESS
                    }
                    val tailMessages = turnMessages.takeLast(8).toList()
                    turnMessages.clear()
                    turnMessages.add(
                        LlmTurnMessage(
                            role = "user",
                            content = """[SLIDING-WINDOW LONG-HORIZON CHECKPOINT — Epoch $continuationEpoch, Step $step]
Tugas Asli Pengguna: "$userPrompt"
Status Plan Saat Ini: ${if (unfinished.isEmpty()) "Semua task plan selesai, pastikan verifikasi kompilasi sukses." else "${unfinished.size} task belum selesai (${unfinished.joinToString(", ") { "${it.id}: ${it.title}" }})"}
Ringkasan Proyek terbaru, Long-Horizon Plan (plan.json), dan Working Memory (memory.md) telah disegarkan di System Prompt. Lanjutkan eksekusi dari langkah terakhir berikut hingga tuntas!"""
                        )
                    )
                    // Ensure tailMessages starts with an assistant message or user message (never an orphaned tool result)
                    val firstAssistantIdx = tailMessages.indexOfFirst { it.role == "assistant" }
                    if (firstAssistantIdx >= 0) {
                        turnMessages.addAll(tailMessages.subList(firstAssistantIdx, tailMessages.size))
                    }
                } else if (!unlimited && step > maxSteps && !cancelRequested.get() && mode.allowsWriteTools && continuationEpoch < maxContinuationEpochs) {
                    val currentPlan = AgentMemoryStore.loadPlan(scId)
                    val unfinished = currentPlan.tasks.filter {
                        it.status == TaskStatus.PENDING || it.status == TaskStatus.IN_PROGRESS
                    }
                    if (unfinished.isNotEmpty() || (!buildVerifiedInThisRun && config.autoBuildAndHeal && mode == AgentAutonomyMode.AUTOPILOT)) {
                        continuationEpoch++
                        step = 1
                        val handoffEntry = AgentChatEntry(
                            id = UUID.randomUUID().toString(),
                            type = ChatEntryType.STATUS,
                            title = "🔄 Harness Auto-Continuation (Sesi $continuationEpoch/$maxContinuationEpochs)",
                            body = "Meriset jendela konteks (Fresh Episodic Handoff) agar tetap ringan dan otomatis melanjutkan milestone yang tersisa (${unfinished.size} task belum selesai)..."
                        )
                        AgentMemoryStore.appendChatEntry(scId, handoffEntry)
                        dispatchEntry(scId, handoffEntry)

                        turnMessages.clear()
                        turnMessages.add(
                            LlmTurnMessage(
                                role = "user",
                                content = """Lanjutkan pengerjaan tugas pengguna: "$userPrompt".
Periksa Ringkasan Proyek, Long-Horizon Plan (plan.json), dan Working Memory (memory.md) di System Prompt.
Fokus selesaikan task yang masih berstatus IN_PROGRESS atau PENDING (${unfinished.joinToString(", ") { "${it.id}: ${it.title}" }}), lalu jalankan save_and_verify_compile sampai BUILD SUKSES!"""
                            )
                        )
                    }
                }
            }

            if (cancelRequested.get()) {
                val cancelEntry = AgentChatEntry(
                    id = UUID.randomUUID().toString(),
                    type = ChatEntryType.STATUS,
                    title = "Dihentikan",
                    body = "Eksekusi AI Agent dihentikan oleh pengguna."
                )
                AgentMemoryStore.appendChatEntry(scId, cancelEntry)
                dispatchEntry(scId, cancelEntry)
            } else if (!unlimited && step > maxSteps) {
                val limitEntry = AgentChatEntry(
                    id = UUID.randomUUID().toString(),
                    type = ChatEntryType.STATUS,
                    title = "Batas Langkah Tercapai ($maxSteps steps x $continuationEpoch sesi)",
                    body = "Ketik 'Lanjutkan' atau aktifkan 'Unlimited Steps' di Pengaturan AI untuk meneruskan eksekusi tanpa batas langkah."
                )
                AgentMemoryStore.appendChatEntry(scId, limitEntry)
                dispatchEntry(scId, limitEntry)
            }
        } catch (t: Throwable) {
            val errEntry = AgentChatEntry(
                id = UUID.randomUUID().toString(),
                type = ChatEntryType.ERROR,
                title = "Error AI Agent",
                body = "${t.javaClass.simpleName}: ${t.message}\n\nTip: Ketik 'Lanjutkan' untuk meneruskan pekerjaan dari langkah terakhir di Long-Horizon Plan."
            )
            AgentMemoryStore.appendChatEntry(scId, errEntry)
            dispatchEntry(scId, errEntry)
        } finally {
            isRunning = false
            dispatchStatus(scId, false, "Siap", currentStep, maxSteps)
            AgentForegroundService.stop(appCtx)
        }
    }

    /**
     * Memadatkan histori percakapan langkah-langkah lama agar ukuran payload tidak membengkak
     * di pertengahan tugas panjang (penyebab utama SocketTimeoutException), sambil tetap menjaga
     * pasangan assistant(toolCalls) <-> tool(toolResult) 100% valid.
     */
    private fun compactTurnMessages(
        messages: List<LlmTurnMessage>,
        aggressive: Boolean
    ): List<LlmTurnMessage> {
        val recentCount = if (aggressive) 4 else 8
        if (messages.size <= recentCount + 1) {
            return messages
        }

        val cutoffIndex = (messages.size - recentCount).coerceAtLeast(1)
        val oldToolLimit = if (aggressive) 320 else 650
        val oldArgLimit = if (aggressive) 280 else 550

        return messages.mapIndexed { index, msg ->
            if (index == 0 || index >= cutoffIndex) {
                msg
            } else {
                when (msg.role) {
                    "tool" -> {
                        if (msg.content.length > oldToolLimit) {
                            msg.copy(
                                content = msg.content.take(oldToolLimit) +
                                    "\n... [Output langkah lama dipadatkan (${msg.content.length} chars). Status proyek terbaru ada di System Prompt]"
                            )
                        } else {
                            msg
                        }
                    }
                    "assistant" -> {
                        val compactedCalls = msg.toolCalls.map { tc ->
                            val newArgs = JsonObject()
                            for ((k, v) in tc.arguments.entrySet()) {
                                if (v.isJsonPrimitive && v.asJsonPrimitive.isString) {
                                    val s = v.asString
                                    if (s.length > oldArgLimit) {
                                        newArgs.addProperty(k, s.take(oldArgLimit) + "... [dipadatkan ${s.length} chars]")
                                    } else {
                                        newArgs.add(k, v)
                                    }
                                } else {
                                    val raw = v.toString()
                                    if (raw.length > oldArgLimit) {
                                        newArgs.addProperty(k, raw.take(oldArgLimit) + "... [dipadatkan ${raw.length} chars]")
                                    } else {
                                        newArgs.add(k, v)
                                    }
                                }
                            }
                            LlmToolCall(
                                id = tc.id,
                                name = tc.name,
                                arguments = newArgs,
                                thoughtSignature = tc.thoughtSignature
                            )
                        }
                        val compactedText = if (msg.content.length > oldToolLimit) {
                            msg.content.take(oldToolLimit) + "..."
                        } else {
                            msg.content
                        }
                        msg.copy(content = compactedText, toolCalls = compactedCalls)
                    }
                    else -> msg
                }
            }
        }
    }

    private fun buildSystemPrompt(
        scId: String,
        mode: AgentAutonomyMode,
        activeScreenName: String?,
        autoBuildAndHeal: Boolean,
        language: pro.sketchware.ai.config.AgentLanguage
    ): String {
        val projectSummary = try {
            ProjectGateway.getProjectSummary(scId)
        } catch (t: Throwable) {
            "Gagal memuat ringkasan proyek: ${t.message}"
        }
        val plan = AgentMemoryStore.loadPlan(scId)
        val memoryNotes = AgentMemoryStore.loadMemoryNotes(scId)
        val unresolvedCrash = RuntimeLogHarness.getLatestUnresolvedCrash(scId)

        val planSummary = if (plan.tasks.isEmpty()) {
            "(Belum ada Long-Horizon Plan aktif)"
        } else {
            buildString {
                appendLine("Goal: ${plan.goal}")
                for (t in plan.tasks) {
                    appendLine("- [${t.status}] (${t.milestone}) ${t.id}: ${t.title} ${if (t.notes.isNotEmpty()) "- ${t.notes}" else ""}")
                }
            }.trim()
        }

        val crashSection = if (!unresolvedCrash.isNullOrBlank()) {
            """
            === ⚠️ UNRESOLVED RUNTIME CRASH TERDETEKSI DI APLIKASI ===
            Aplikasi hasil kompilasi mengalami Force Close saat dijalankan dengan stack trace berikut:
            $unresolvedCrash
            (Gunakan tool `inspect_runtime_logs` atau `read_event_logic` untuk memperbaiki bug runtime di atas!)
            """.trimIndent()
        } else {
            ""
        }

        val pluginsSection = try {
            pro.sketchware.ai.extensions.PluginManager.buildSystemPromptSection()
        } catch (_: Throwable) {
            ""
        }
        val skillsSection = try {
            pro.sketchware.ai.extensions.SkillManager.buildSystemPromptSection()
        } catch (_: Throwable) {
            ""
        }
        val mcpConnectorsSection = try {
            pro.sketchware.ai.extensions.McpClientHub.buildSystemPromptSection()
        } catch (_: Throwable) {
            ""
        }

        return """
            Anda adalah **Sketchware Pro Autonomous AI Agent**, arsitek & engineer Android senior yang tertanam langsung di dalam IDE Sketchware Pro.
            Anda memiliki kendali penuh atas mesin internal Sketchware Pro (`ViewBean`, `BlockBean`, `EventBean`, `ComponentBean`, `ProjectFileBean`, `ProjectBuilder`).
            ${language.systemPromptDirective()}

            === MODE OTONOMI SAAT INI: ${mode.name} (${mode.title}) ===
            Deskripsi Mode: ${mode.subtitle}
            Layar yang sedang dibuka pengguna: ${activeScreenName ?: "main.xml / MainActivity.java"}

            === RINGKASAN PROYEK SAAT INI ===
            $projectSummary

            === LONG-HORIZON TASK BOARD (plan.json) ===
            $planSummary

            === WORKING MEMORY (memory.md) ===
            ${memoryNotes.ifBlank { "(Kosong)" }}
            $crashSection

            === ATURAN ARSITEKTUR SKETCHWARE PRO (WAJIB DIIKUTI) ===
            1. **Long-Horizon Planning**:
               - Untuk tugas membangun aplikasi dari 0 atau fitur multi-langkah, SELALU panggil `update_long_horizon_plan` terlebih dahulu untuk membuat daftar Milestone & Task serta mencatat ID View/Variabel di `memory_notes`.
               - Perbarui status task (`IN_PROGRESS` -> `COMPLETED`) seiring kemajuan Anda.
            2. **Desain Layout XML (`write_screen_xml`)**:
               - Selalu bungkus layout dalam tag root `<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android" xmlns:app="http://schemas.android.com/apk/res-auto" android:layout_width="match_parent" android:layout_height="match_parent" android:orientation="vertical"> ... </LinearLayout>`.
               - `ViewBeanParser` memiliki `setSkipRoot(true)`, sehingga elemen anak langsung di dalam root tag tersebut akan menjadi top-level ViewBean di kanvas Sketchware!
               - Setiap view WAJIB memiliki `android:id="@+id/nama_id_unik"`, `android:layout_width`, dan `android:layout_height`.
               - Gunakan hanya widget standar Sketchware di dalam XML (`LinearLayout`, `RelativeLayout`, `ScrollView`, `HorizontalScrollView`, `TextView`, `Button`, `EditText`, `ImageView`, `ListView`, `Spinner`, `CheckBox`, `Switch`, `SeekBar`, `ProgressBar`, `WebView`, `CalendarView`).
               - Buat UI yang modern, rapi, proporsional, dengan padding, warna kartu/background, ukuran font yang jelas, dan kontras warna yang nyaman dilihat.
            3. **Variabel, List, Component, dan MoreBlock**:
               - Sebelum menggunakan variabel (`getVar`, `setVarString`, `setVarInt`, `setVarBoolean`, `mapPutValue`, dll.) di dalam blok logika, daftarkan menggunakan `manage_variable` (Harness juga memiliki Auto-Heal yang akan mendaftarkan variabel/import yang terlewat).
               - Sebelum menggunakan List (`addListStr`, `listSetData`, `addListMap`, dll.), daftarkan menggunakan `manage_list`!
               - Sebelum menggunakan Component (`intentSetScreen`, `startActivity`, `fileSetData`, `dialogShow`, `timerAfter`, `requestNetworkStartRequestNetwork`, dll.), Anda WAJIB mendaftarkannya menggunakan `add_component`!
            4. **Menulis Logika Blok (`write_event_logic`)**:
               - Untuk event `onCreate` Activity, gunakan `target_id="onCreate"` dan `event_name="initializeLogic"`.
               - Untuk klik tombol atau view dengan `android:id="@+id/btn_simpan"`, gunakan `target_id="btn_simpan"` dan `event_name="onClick"`.
               - Untuk item klik pada `ListView` (`lv_data`), gunakan `target_id="lv_data"` dan `event_name="onItemClicked"` (argumen posisi tersedia via `[getArg(" _position")]` atau gunakan `addSourceDirectly`).
               - Gunakan blok visual Sketchware standar (`setText`, `getText`, `doToast`, `if`, `ifElse`, `repeat`, `setVarString`, `setVarInt`, `addListStr`, `listSetData`, `listRefresh`, `intentSetScreen`, `startActivity`, `fileSetData`, `fileGetData`, dll.) agar pengguna bisa melihat dan mengedit blok warna-warni di Logic Editor Sketchware!
               - Bila ada logika Java tingkat lanjut (seperti animasi kustom, manipulasi JSON/SQLite/algoritma kompleks, atau styling `GradientDrawable`), kombinasikan blok visual dengan `addSourceDirectly("...kode java...")`!
               - Saat memanggil `intentSetScreen("intentId", "NamaActivity")`, parameter kedua adalah nama kelas Activity tanpa `.java` (misal `"SecondActivity"` atau `"DetailActivity"`).
            5. **Akses Penuh File Perangkat, Project, Resources, Metadata & Local Libraries (`manage_sketchware_files`, `manage_local_libraries`, `manage_project_resources`, `configure_project_metadata`, `search_project_code`, `manage_checkpoints`)**:
               - Anda memiliki akses penuh ke filesystem perangkat & seluruh direktori Sketchware melalui `manage_sketchware_files` (`list`, `read`, `write`, `append`, `mkdir`, `delete`, `copy`, `move`) dengan dukungan ekstensi file apa pun (`.java`, `.kt`, `.xml`, `.json`, `.jar`, `.dex`, `.txt`, dll.) tanpa batasan ekstensi.
               - Gunakan alias `@project/` (`.sketchware/data/<sc_id>`), `@files/` (`.sketchware/data/<sc_id>/files`), `@mysc/` (`.sketchware/mysc/list/<sc_id>`), `@libs/` (`.sketchware/libs/local_libs`), `@system/` (`.sketchware/system`), atau path absolut `/storage/emulated/0/...`.
               - **Maven / JAR / AAR Auto-Installer (`manage_local_libraries`)**: Gunakan `manage_local_libraries(action="install_maven", maven_coordinate="groupId:artifactId:version")` untuk mengunduh library dari Google Maven / Maven Central / JitPack, mengekstrak AAR, mengompilasi `classes.jar` menjadi `classes.dex` dengan D8 secara otomatis, dan langsung mengaktifkannya di proyek! Atau gunakan `action="import_jar"` untuk mengompilasi `.jar`/`.aar` lokal, `action="disable"` (`library_names="all_missing"`) untuk menonaktifkan library yang rusak, dan `action="repair"` untuk memperbaiki folder `res/`.
               - **Ikon VectorDrawable, Gambar, Font, Suara & Resource XML (`manage_project_resources`)**: Anda dapat langsung membuat ikon Android `<vector>` XML dengan `manage_project_resources(action="add_vector_drawable", resource_name="ic_...", content="<vector ...>")` dan langsung memakainya di `ImageView` (`android:src="@drawable/ic_..."`)! Anda juga bisa menulis file `res/` kustom (`action="write_res_xml"`, misal `drawable/bg_card.xml` atau `values/colors.xml`) serta `assets/` (`action="write_asset"`).
               - **Metadata Proyek & Pembersihan (`configure_project_metadata`)**: Gunakan untuk membaca/mengubah nama aplikasi, package name, version code/name, warna tema (`color_primary`, `color_accent`), `min_sdk`/`target_sdk`, custom launcher icon, atau menghapus screen/event yang tidak terpakai (`delete_screen`, `delete_event`).
               - **Pencarian Mendalam & Time-Machine Checkpoint (`search_project_code` & `manage_checkpoints`)**: Gunakan `search_project_code(query="...")` untuk mencari ID view, blok logika, variabel, atau kode Java di seluruh proyek. Gunakan `manage_checkpoints` (`list`, `create`, `restore`) untuk menyimpan titik aman atau melakukan rollback proyek kapan saja.
            6. **Verifikasi Kompilasi & Self-Healing (`save_and_verify_compile` & `inspect_runtime_logs`)**:
               ${if (autoBuildAndHeal && mode.allowsWriteTools) "- Setelah selesai membangun/mengubah UI dan logika, WAJIB panggil `save_and_verify_compile` (gunakan `full_apk_build=true` jika mode AUTOPILOT atau pengguna meminta build APK, atau `full_apk_build=false` untuk verifikasi cepat). Jika ada error kompilasi atau runtime crash, baca baris error-nya, perbaiki sampai `BUILD SUKSES!`." else "- Simpan perubahan dengan rapi dan jelaskan hasilnya kepada pengguna."}
            7. **Browsing Web, Terminal Linux/Debian & Kebebasan Akses Keluar (`web_search`, `web_fetch`, `run_shell`, `terminal_*`, `manage_linux_env`)**:
               - Anda adalah coding agent penuh seperti Claude Code / Antigravity / OpenCode dengan kebebasan akses keluar penuh untuk menjembatani kesenjangan proyek.
               - **Browsing & Dokumentasi (`web_search` & `web_fetch`)**: Gunakan `web_search(query="...")` untuk mencari dokumentasi API Android/Java/Kotlin, contoh kode, koordinat Maven/JAR, atau solusi error, lalu gunakan `web_fetch(url="...")` untuk membaca halaman dokumentasi, file raw GitHub, atau JSON API secara langsung.
               - **Shell & Linux Userland (`run_shell` & `manage_linux_env`)**: Anda memiliki shell tanpa allowlist dengan akses internet keluar. Distro target adalah `debian` (dengan `apt-get`, `curl`, `wget`, `git`, `python3`, `pip`, `unzip`), diunduh otomatis saat pertama dibutuhkan; bila perangkat offline atau unduhan gagal, perintah otomatis berjalan di `alpine` bawaan (pakai `apk add`, bukan `apt-get`) dan jawabannya memuat catatan `[fallback]`/`[hint]` yang harus Anda ikuti. Gunakan `run_shell` untuk mengunduh library `.jar`/`.dex` dari Maven Central/GitHub ke `@libs/`, menjalankan skrip Python/generator aset, `git clone`, atau memasang paket (`apt-get install -y ...`, `pip install ...`).
               - Bila `manage_linux_env(action="status")` menunjukkan toolchain Debian belum lengkap, cukup panggil `manage_linux_env(action="bootstrap", distro="debian")` untuk mengunduh/mengonfigurasi Debian + DNS + APT IPv4 + paket dasar secara otomatis.
               - Untuk sesi interaktif/REPL/proses panjang, gunakan `terminal_start(engine="linux", distro="debian", pty=true)` lalu `terminal_send` / `terminal_read` (atau `run_shell` dengan `session_id`). Direktori kerja proyek otomatis di-bind ke dalam distro.
               - Gunakan `terminal_read(wait_for_pattern="...", idle_ms=...)` untuk menunggu prompt/hasil tanpa polling berulang; bila hasilnya memuat baris `[prompt] ...`, sesi sedang menunggu input (y/n, password, REPL) sehingga kirim jawaban lewat `terminal_send`. Hentikan sesi dengan `terminal_stop` (alias `terminal_kill`).
               - Guard keamanan memblokir perintah destruktif (hapus rekursif di luar `.sketchware`, `mkfs`, `dd of=/dev/...`). Bila diblokir, perbaiki target ke dalam folder proyek; jangan pernah mengisi `confirm_destructive=true` kecuali pengguna secara eksplisit memintanya.
               - Selalu baca exit code & stderr; bila ada dependensi atau class yang kurang di proyek, cari/unduh atau bangun secara otonom daripada menyerah.
            8. **Ekosistem Skills (`SKILL.md`), Plugins, & Konektor MCP (`use_skill`, `manage_plugins`, `manage_mcp_connectors`, `call_mcp_tool`)**:
               - Bila tugas relevan dengan salah satu Skill di bawah, panggil `use_skill(action="read", skill_id="...")` sebelum menulis kode. Anda juga dapat membuat skill baru (`action="create"`) atau mengimpor dari URL (`action="install_url"`).
               - Anda dapat menghubungkan diri ke server MCP eksternal (HTTP/Streamable HTTP maupun proses Stdio di dalam Debian Linux) menggunakan `manage_mcp_connectors` dan memanggil tool-nya secara langsung (`mcp__<server>__<tool>` atau `call_mcp_tool`).
            9. **DeerFlow 2.0 Sub-Agent Delegation & Workspace Artifacts (`delegate_subagent` & `manage_artifacts`)**:
               - Untuk analisis arsitektur mendalam, audit blok logika lintas layar, atau investigasi error kompilasi tanpa membebani konteks utama, panggil `delegate_subagent(role="researcher"|"ui_architect"|"block_auditor"|"compile_debugger", task="...", target_screens="main,...")`.
               - Gunakan `manage_artifacts(action="write", file_name="...", content="...")` untuk menyimpan dokumen spesifikasi, laporan audit, atau deliverable sehingga pengguna dapat langsung membukanya dari panel **📦 Artifacts** di bagian atas layar chat.
            10. **🧬 REA (Reverse Engineer Anything) & Full APK Project Cloner (`reconstruct_apk_project` & MCP `rea_mcp`)**:
               - Ketika pengguna melampirkan file `.apk` (melalui tombol `+ Add Context -> Tambahkan File APK`) atau meminta clone/reverse-engineer APK:
                 1) Segera panggil `reconstruct_apk_project(action="full_clone", apk_path="...")` untuk mendekode `AndroidManifest.xml` & seluruh binary `res/layout/*.xml`, mengimpor drawables/assets/fonts/permissions, membuat layar Activity & CustomView di proyek Sketchware `#$scId`, serta menghasilkan `rea_clone_blueprint.md` di Artifacts.
                 2) Panggil `reconstruct_apk_project(action="analyze_dex", apk_path="...", query="...")` dan `reconstruct_apk_project(action="decode_xml", apk_path="...", xml_entry="...")` (atau tool MCP `mcp__rea_mcp__*`) untuk membedah struktur class DEX, method, field, konstanta, SQL, dan endpoint API dari APK sumber.
                 3) Jangan berhenti hanya pada layout XML! Lanjutkan secara otonom mengimplementasikan seluruh logika bisnis setiap Activity (`write_event_logic`, `add_component`, `write_custom_java_file`), hubungkan navigasi antar layar, dan jalankan `save_and_verify_compile` hingga proyek hasil clone terkompilasi 100% sukses!
            11. **♾️ Doktrin Paten: Principal Software Engineer, App Developer & Penguasa Terminal (Unlimited Long-Horizon Hingga Finish)**:
               - Anda memegang kendali penuh sebagai Principal Software Engineer & Android App Developer dengan eksekusi **Unlimited Long-Horizon Steps (`∞`)**.
               - Jangan pernah berhenti setengah jalan atau menyerahkan pekerjaan yang belum selesai kepada pengguna. Kuasai terminal (`run_shell`, `terminal_start`, `terminal_send`, Python 3, Bash, curl, git, sqlite3, unzip) dan seluruh tool Sketchware untuk menyelesaikan setiap masalah secara mandiri sampai seluruh task di `plan.json` berstatus `COMPLETED` dan `save_and_verify_compile` menghasilkan `BUILD SUKSES!`.

            $pluginsSection

            $skillsSection

            $mcpConnectorsSection
        """.trimIndent()
    }

    fun recordExternalAgentAction(
        scId: String,
        agentName: String,
        toolName: String,
        summary: String,
        output: String,
        isError: Boolean
    ) {
        if (scId.isBlank()) return
        val entry = AgentChatEntry(
            id = UUID.randomUUID().toString(),
            type = if (isError) ChatEntryType.ERROR else ChatEntryType.TOOL_RESULT,
            title = "🌐 [$agentName] $toolName — $summary",
            body = output.take(1500)
        )
        AgentMemoryStore.appendChatEntry(scId, entry)
        dispatchEntry(scId, entry)
        if (toolName == "update_long_horizon_plan") {
            dispatchPlan(scId, AgentMemoryStore.loadPlan(scId))
        }
    }

    private fun dispatchEntry(scId: String, entry: AgentChatEntry) {
        mainHandler.post {
            for (l in listeners) {
                l.onChatEntryAdded(scId, entry)
            }
        }
    }

    private fun dispatchPlan(scId: String, plan: AgentPlan) {
        mainHandler.post {
            for (l in listeners) {
                l.onPlanUpdated(scId, plan)
            }
        }
    }

    private fun dispatchStatus(scId: String, running: Boolean, status: String, step: Int, maxSteps: Int) {
        currentStatusText = status
        AgentForegroundService.updateNotification(status)
        mainHandler.post {
            for (l in listeners) {
                l.onStatusChanged(scId, running, status, step, maxSteps)
            }
        }
    }
}
