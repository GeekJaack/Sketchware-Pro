package pro.sketchware.ai.agent

import android.content.Context
import android.os.Environment
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import pro.sketchware.SketchApplication
import pro.sketchware.utility.FileUtil
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Persists Long-Horizon Agent state (`plan.json`, `memory.md`, `chat.json`, `journal.jsonl`)
 * per-session inside the app's private files directory (`files/agent/<sc_id>/sessions/<session_id>/`)
 * so chat transcripts, tool outputs and working memory are isolated per conversation and not
 * world-readable on shared external storage.
 *
 * Legacy single-session builds that stored `chat.json`, `plan.json`, `memory.md` directly under
 * `files/agent/<sc_id>/` are automatically migrated into the initial session (`Percakapan 1` or
 * auto-titled from the first user prompt) on first access.
 */
object AgentMemoryStore {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val compactGson: Gson = GsonBuilder().disableHtmlEscaping().create()

    @Volatile
    private var appContext: Context? = null

    /** Optional test override directory so JVM unit tests can run without Android Context. */
    @Volatile
    internal var testRootDir: File? = null
        set(value) {
            field = value
            migratedIds.clear()
            migratedMultiSessionIds.clear()
        }

    private val migratedIds = ConcurrentHashMap.newKeySet<String>()
    private val migratedMultiSessionIds = ConcurrentHashMap.newKeySet<String>()

    /**
     * Thread-local session context override used by [AgentOrchestrator] during background runs so
     * tools (e.g. `update_long_horizon_plan`) calling `loadPlan(scId)` / `savePlan(scId)` always
     * read and write the running session's state even if the user switches the UI to view another
     * session while the agent is working.
     */
    private val executionSessionOverride = ThreadLocal<Pair<String, String>?>()

    fun <T> withExecutionSession(scId: String, sessionId: String, block: () -> T): T {
        val prev = executionSessionOverride.get()
        executionSessionOverride.set(scId to sessionId)
        return try {
            block()
        } finally {
            if (prev == null) executionSessionOverride.remove() else executionSessionOverride.set(prev)
        }
    }

    @JvmStatic
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Returns the private agent directory for [scId], creating it if needed and migrating any
     * legacy external-storage contents and flat single-session files into multi-session storage.
     */
    @JvmStatic
    fun getAgentDir(scId: String): File {
        val safeId = scId.ifBlank { "global" }
        val overrideRoot = testRootDir
        val dir = if (overrideRoot != null) {
            File(overrideRoot, "agent/$safeId")
        } else {
            val ctx = appContext ?: try { SketchApplication.getContext() } catch (_: Throwable) { null }
            if (ctx != null) {
                File(ctx.filesDir, "agent/$safeId")
            } else {
                File(Environment.getExternalStorageDirectory(), ".sketchware/agent/$safeId")
            }
        }
        if (!dir.exists()) dir.mkdirs()
        if (overrideRoot == null) {
            migrateLegacyExternalDir(safeId, dir)
        }
        ensureMultiSessionMigrated(safeId, dir)
        return dir
    }

    private fun migrateLegacyExternalDir(safeId: String, newDir: File) {
        if (!migratedIds.add(safeId)) return
        try {
            val legacy = File(Environment.getExternalStorageDirectory(), ".sketchware/agent/$safeId")
            if (!legacy.exists() || legacy.canonicalPath == newDir.canonicalPath) return
            legacy.listFiles()?.forEach { src ->
                val dst = File(newDir, src.name)
                if (!dst.exists()) {
                    if (src.isDirectory) {
                        src.copyRecursively(dst, overwrite = false)
                    } else {
                        src.copyTo(dst, overwrite = false)
                    }
                }
            }
            legacy.deleteRecursively()
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------------------------------
    // Multi-Session Management & Automatic Legacy Migration
    // ------------------------------------------------------------------------------------------

    private fun sessionsIndexFile(agentDir: File) = File(agentDir, "sessions_index.json")
    private fun activeSessionFile(agentDir: File) = File(agentDir, "active_session.txt")
    private fun sessionsRootDir(agentDir: File) = File(agentDir, "sessions")

    @Synchronized
    private fun ensureMultiSessionMigrated(safeId: String, agentDir: File) {
        val indexFile = sessionsIndexFile(agentDir)
        if (indexFile.exists() && migratedMultiSessionIds.contains(safeId)) return

        val sessionsDir = sessionsRootDir(agentDir)
        if (!sessionsDir.exists()) sessionsDir.mkdirs()

        val legacyChat = File(agentDir, "chat.json")
        val legacyPlan = File(agentDir, "plan.json")
        val legacyMem = File(agentDir, "memory.md")
        val legacyJournal = File(agentDir, "journal.jsonl")

        val existingSessions = readSessionsIndexInternal(agentDir)
        if (existingSessions.isEmpty()) {
            val hasLegacyData = legacyChat.exists() || legacyPlan.exists() || legacyMem.exists()
            val initialSessionId = "s_" + UUID.randomUUID().toString().replace("-", "").take(12)
            val sDir = File(sessionsDir, initialSessionId)
            sDir.mkdirs()

            var migratedEntries = mutableListOf<AgentChatEntry>()
            if (legacyChat.exists()) {
                try {
                    val type = object : TypeToken<MutableList<AgentChatEntry>>() {}.type
                    migratedEntries = gson.fromJson<MutableList<AgentChatEntry>>(legacyChat.readText(), type) ?: mutableListOf()
                } catch (_: Throwable) {
                }
                try {
                    legacyChat.copyTo(File(sDir, "chat.json"), overwrite = true)
                    legacyChat.delete()
                } catch (_: Throwable) {
                }
            }
            if (legacyPlan.exists()) {
                try {
                    legacyPlan.copyTo(File(sDir, "plan.json"), overwrite = true)
                    legacyPlan.delete()
                } catch (_: Throwable) {
                }
            }
            if (legacyMem.exists()) {
                try {
                    legacyMem.copyTo(File(sDir, "memory.md"), overwrite = true)
                    legacyMem.delete()
                } catch (_: Throwable) {
                }
            }
            if (legacyJournal.exists()) {
                try {
                    legacyJournal.copyTo(File(sDir, "journal.jsonl"), overwrite = true)
                    legacyJournal.delete()
                } catch (_: Throwable) {
                }
            }

            val firstUser = migratedEntries.firstOrNull { it.type == ChatEntryType.USER }
            val lastEntry = migratedEntries.lastOrNull()
            val derivedTitle = if (firstUser != null) {
                deriveSessionTitleFromPrompt(firstUser.body, fallback = "Percakapan 1")
            } else if (hasLegacyData) {
                "Percakapan 1"
            } else {
                "Percakapan Baru"
            }
            val now = System.currentTimeMillis()
            val createdAt = migratedEntries.firstOrNull()?.timestamp ?: now
            val updatedAt = lastEntry?.timestamp ?: now
            val preview = lastEntry?.body?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.take(100) ?: ""

            val meta = AgentSessionMeta(
                id = initialSessionId,
                scId = safeId,
                title = derivedTitle,
                pinned = false,
                createdAt = createdAt,
                updatedAt = updatedAt,
                messageCount = migratedEntries.size,
                lastPreview = preview
            )
            writeSessionsIndexInternal(agentDir, listOf(meta))
            activeSessionFile(agentDir).writeText(initialSessionId)
        } else if (!activeSessionFile(agentDir).exists()) {
            activeSessionFile(agentDir).writeText(existingSessions.first().id)
        }

        migratedMultiSessionIds.add(safeId)
    }

    private fun readSessionsIndexInternal(agentDir: File): MutableList<AgentSessionMeta> {
        val file = sessionsIndexFile(agentDir)
        if (!file.exists()) return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<AgentSessionMeta>>() {}.type
            gson.fromJson<MutableList<AgentSessionMeta>>(file.readText(), type) ?: mutableListOf()
        } catch (_: Throwable) {
            mutableListOf()
        }
    }

    private fun writeSessionsIndexInternal(agentDir: File, list: List<AgentSessionMeta>) {
        sessionsIndexFile(agentDir).writeText(gson.toJson(list))
    }

    /**
     * Resolves which session ID should be used when none is explicitly provided:
     * checks [executionSessionOverride] first (for background runs), then the project's active session.
     */
    fun resolveSessionId(scId: String, explicitSessionId: String? = null): String {
        if (!explicitSessionId.isNullOrBlank()) return explicitSessionId
        val safeId = scId.ifBlank { "global" }
        val override = executionSessionOverride.get()
        if (override != null && override.first == safeId && override.second.isNotBlank()) {
            return override.second
        }
        return getActiveSessionId(scId)
    }

    @Synchronized
    fun getActiveSessionId(scId: String): String {
        val agentDir = getAgentDir(scId)
        val sessions = readSessionsIndexInternal(agentDir)
        val activeFile = activeSessionFile(agentDir)
        val currentId = if (activeFile.exists()) activeFile.readText().trim() else ""
        if (currentId.isNotEmpty() && sessions.any { it.id == currentId }) {
            return currentId
        }
        if (sessions.isNotEmpty()) {
            val fallback = sortSessions(sessions).first().id
            activeFile.writeText(fallback)
            return fallback
        }
        val created = createSession(scId, title = "Percakapan Baru", setActive = true)
        return created.id
    }

    @Synchronized
    fun setActiveSessionId(scId: String, sessionId: String): Boolean {
        val agentDir = getAgentDir(scId)
        val sessions = readSessionsIndexInternal(agentDir)
        if (sessions.none { it.id == sessionId }) return false
        activeSessionFile(agentDir).writeText(sessionId)
        return true
    }

    @Synchronized
    fun getSessionMeta(scId: String, sessionId: String = getActiveSessionId(scId)): AgentSessionMeta? {
        val agentDir = getAgentDir(scId)
        return readSessionsIndexInternal(agentDir).firstOrNull { it.id == sessionId }
    }

    @Synchronized
    fun listSessions(scId: String): List<AgentSessionMeta> {
        val agentDir = getAgentDir(scId)
        return sortSessions(readSessionsIndexInternal(agentDir))
    }

    private fun sortSessions(list: List<AgentSessionMeta>): List<AgentSessionMeta> {
        return list.sortedWith(
            compareByDescending<AgentSessionMeta> { it.pinned }
                .thenByDescending { it.updatedAt }
        )
    }

    fun getSessionDir(scId: String, sessionId: String = resolveSessionId(scId)): File {
        val agentDir = getAgentDir(scId)
        val dir = File(sessionsRootDir(agentDir), sessionId)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    @Synchronized
    fun createSession(
        scId: String,
        title: String = "Percakapan Baru",
        setActive: Boolean = true
    ): AgentSessionMeta {
        val safeId = scId.ifBlank { "global" }
        val agentDir = getAgentDir(safeId)
        val sessions = readSessionsIndexInternal(agentDir)
        val newId = "s_" + UUID.randomUUID().toString().replace("-", "").take(12)
        val now = System.currentTimeMillis()
        val meta = AgentSessionMeta(
            id = newId,
            scId = safeId,
            title = title.trim().ifBlank { "Percakapan Baru" },
            pinned = false,
            createdAt = now,
            updatedAt = now,
            messageCount = 0,
            lastPreview = ""
        )
        File(sessionsRootDir(agentDir), newId).mkdirs()
        sessions.add(0, meta)
        writeSessionsIndexInternal(agentDir, sortSessions(sessions))
        if (setActive) {
            activeSessionFile(agentDir).writeText(newId)
        }
        return meta
    }

    @Synchronized
    fun renameSession(scId: String, sessionId: String, newTitle: String): AgentSessionMeta? {
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty()) return getSessionMeta(scId, sessionId)
        val agentDir = getAgentDir(scId)
        val sessions = readSessionsIndexInternal(agentDir)
        val idx = sessions.indexOfFirst { it.id == sessionId }
        if (idx < 0) return null
        val updated = sessions[idx].copy(
            title = trimmed.take(80),
            updatedAt = System.currentTimeMillis()
        )
        sessions[idx] = updated
        writeSessionsIndexInternal(agentDir, sortSessions(sessions))
        return updated
    }

    @Synchronized
    fun setSessionPinned(scId: String, sessionId: String, pinned: Boolean): AgentSessionMeta? {
        val agentDir = getAgentDir(scId)
        val sessions = readSessionsIndexInternal(agentDir)
        val idx = sessions.indexOfFirst { it.id == sessionId }
        if (idx < 0) return null
        val updated = sessions[idx].copy(pinned = pinned)
        sessions[idx] = updated
        writeSessionsIndexInternal(agentDir, sortSessions(sessions))
        return updated
    }

    @Synchronized
    fun deleteSession(scId: String, sessionId: String): String {
        val agentDir = getAgentDir(scId)
        val sessions = readSessionsIndexInternal(agentDir)
        sessions.removeAll { it.id == sessionId }
        try {
            File(sessionsRootDir(agentDir), sessionId).deleteRecursively()
        } catch (_: Throwable) {
        }
        if (sessions.isEmpty()) {
            val fresh = createSession(scId, title = "Percakapan Baru", setActive = true)
            return fresh.id
        }
        val sorted = sortSessions(sessions)
        writeSessionsIndexInternal(agentDir, sorted)
        val activeFile = activeSessionFile(agentDir)
        val currentActive = if (activeFile.exists()) activeFile.readText().trim() else ""
        if (currentActive == sessionId || sorted.none { it.id == currentActive }) {
            val nextId = sorted.first().id
            activeFile.writeText(nextId)
            return nextId
        }
        return currentActive
    }

    /**
     * Forks (duplicates) a session up to [upToEntryId] (or copies all entries if [upToEntryId] is null).
     * Copies `plan.json` and `memory.md` as well so the forked session has full working memory.
     */
    @Synchronized
    fun forkSession(
        scId: String,
        sourceSessionId: String,
        upToEntryId: String? = null,
        customTitle: String? = null,
        setActive: Boolean = true
    ): AgentSessionMeta {
        val safeId = scId.ifBlank { "global" }
        val agentDir = getAgentDir(safeId)
        val sourceMeta = getSessionMeta(safeId, sourceSessionId)
        val sourceEntries = loadChatEntries(safeId, sourceSessionId)
        val cutEntries = if (upToEntryId != null) {
            val idx = sourceEntries.indexOfFirst { it.id == upToEntryId }
            if (idx >= 0) sourceEntries.subList(0, idx + 1).toList() else sourceEntries.toList()
        } else {
            sourceEntries.toList()
        }

        val now = System.currentTimeMillis()
        val newId = "s_" + UUID.randomUUID().toString().replace("-", "").take(12)
        val newDir = File(sessionsRootDir(agentDir), newId)
        newDir.mkdirs()

        // Write forked chat entries
        File(newDir, "chat.json").writeText(gson.toJson(cutEntries))
        val journalFile = File(newDir, "journal.jsonl")
        for (e in cutEntries) {
            journalFile.appendText(compactGson.toJson(e) + "\n")
        }

        // Copy plan.json and memory.md if they exist
        val srcDir = getSessionDir(safeId, sourceSessionId)
        val srcPlan = File(srcDir, "plan.json")
        if (srcPlan.exists()) {
            srcPlan.copyTo(File(newDir, "plan.json"), overwrite = true)
        }
        val srcMem = File(srcDir, "memory.md")
        if (srcMem.exists()) {
            srcMem.copyTo(File(newDir, "memory.md"), overwrite = true)
        }

        val baseTitle = sourceMeta?.title ?: "Percakapan"
        val title = customTitle?.trim()?.takeIf { it.isNotEmpty() } ?: "$baseTitle (Cabang)"
        val lastPreview = cutEntries.lastOrNull()?.body
            ?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.take(100) ?: ""

        val meta = AgentSessionMeta(
            id = newId,
            scId = safeId,
            title = title.take(80),
            pinned = false,
            createdAt = now,
            updatedAt = now,
            messageCount = cutEntries.size,
            lastPreview = lastPreview,
            forkedFromSessionId = sourceSessionId
        )
        val sessions = readSessionsIndexInternal(agentDir)
        sessions.add(0, meta)
        writeSessionsIndexInternal(agentDir, sortSessions(sessions))
        if (setActive) {
            activeSessionFile(agentDir).writeText(newId)
        }
        return meta
    }

    /**
     * Truncates a session's chat history starting at [fromEntryId] (if [inclusive] is true,
     * [fromEntryId] and all subsequent entries are removed; if false, entries strictly after
     * [fromEntryId] are removed). Returns the removed entries in order.
     */
    @Synchronized
    fun truncateSessionFromEntry(
        scId: String,
        sessionId: String = resolveSessionId(scId),
        fromEntryId: String,
        inclusive: Boolean = true
    ): List<AgentChatEntry> {
        val entries = loadChatEntries(scId, sessionId)
        val idx = entries.indexOfFirst { it.id == fromEntryId }
        if (idx < 0) return emptyList()
        val cutIndex = if (inclusive) idx else idx + 1
        if (cutIndex >= entries.size) return emptyList()

        val removed = entries.subList(cutIndex, entries.size).toList()
        val remaining = entries.subList(0, cutIndex).toList()
        val sDir = getSessionDir(scId, sessionId)
        File(sDir, "chat.json").writeText(gson.toJson(remaining))
        syncSessionStats(scId, sessionId, remaining)
        return removed
    }

    /**
     * Updates a specific [AgentChatEntry] in-place (e.g., to attach a `checkpointPath` to a USER entry).
     */
    @Synchronized
    fun updateChatEntry(
        scId: String,
        sessionId: String = resolveSessionId(scId),
        entryId: String,
        transform: (AgentChatEntry) -> AgentChatEntry
    ): AgentChatEntry? {
        val entries = loadChatEntries(scId, sessionId)
        val idx = entries.indexOfFirst { it.id == entryId }
        if (idx < 0) return null
        val updated = transform(entries[idx])
        entries[idx] = updated
        val sDir = getSessionDir(scId, sessionId)
        File(sDir, "chat.json").writeText(gson.toJson(entries))
        return updated
    }

    fun deriveSessionTitleFromPrompt(rawPrompt: String, fallback: String = "Percakapan Baru"): String {
        val clean = rawPrompt
            .substringBefore("\n\n---Perhatian Agent:")
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: return fallback
        return if (clean.length <= 48) clean else clean.take(45).trimEnd() + "…"
    }

    fun isDefaultSessionTitle(title: String): Boolean {
        val lower = title.trim().lowercase(Locale.ROOT)
        return lower.isEmpty() ||
            lower == "percakapan baru" ||
            lower == "new chat" ||
            lower.matches(Regex("^percakapan\\s+\\d+$"))
    }

    // ------------------------------------------------------------------------------------------
    // Per-Session Plan, Memory & Chat Storage (Full History + Paged Loading)
    // ------------------------------------------------------------------------------------------

    fun loadPlan(scId: String, sessionId: String? = null): AgentPlan {
        val resolvedId = resolveSessionId(scId, sessionId)
        val file = File(getSessionDir(scId, resolvedId), "plan.json")
        if (!file.exists()) return AgentPlan()
        return try {
            gson.fromJson(file.readText(), AgentPlan::class.java) ?: AgentPlan()
        } catch (_: Exception) {
            AgentPlan()
        }
    }

    fun savePlan(scId: String, plan: AgentPlan, sessionId: String? = null) {
        val resolvedId = resolveSessionId(scId, sessionId)
        val file = File(getSessionDir(scId, resolvedId), "plan.json")
        file.writeText(gson.toJson(plan))
    }

    fun loadMemoryNotes(scId: String, sessionId: String? = null): String {
        val resolvedId = resolveSessionId(scId, sessionId)
        val file = File(getSessionDir(scId, resolvedId), "memory.md")
        if (!file.exists()) return ""
        return try {
            file.readText()
        } catch (_: Exception) {
            ""
        }
    }

    fun saveMemoryNotes(scId: String, notes: String, append: Boolean = false, sessionId: String? = null) {
        val resolvedId = resolveSessionId(scId, sessionId)
        val file = File(getSessionDir(scId, resolvedId), "memory.md")
        val updated = if (append && file.exists()) {
            val current = file.readText().trim()
            if (current.isEmpty()) notes.trim() else "$current\n\n${notes.trim()}"
        } else {
            notes.trim()
        }
        file.writeText(updated)
    }

    /**
     * Loads the full list of chat entries for [sessionId] (or the active/running session).
     * Note: The previous 150-entry hard truncation has been removed; all entries are preserved.
     */
    @Synchronized
    fun loadChatEntries(scId: String, sessionId: String? = null): MutableList<AgentChatEntry> {
        val resolvedId = resolveSessionId(scId, sessionId)
        val file = File(getSessionDir(scId, resolvedId), "chat.json")
        if (!file.exists()) return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<AgentChatEntry>>() {}.type
            gson.fromJson<MutableList<AgentChatEntry>>(file.readText(), type) ?: mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    /**
     * Loads a page of chat entries from the end of the conversation for smooth UI pagination.
     *
     * @param loadedCount Number of latest entries already loaded in the UI (0 on initial load).
     * @param pageSize Maximum number of entries to load in this page (default 50).
     */
    @Synchronized
    fun loadChatEntriesPage(
        scId: String,
        sessionId: String = resolveSessionId(scId),
        loadedCount: Int = 0,
        pageSize: Int = 50
    ): AgentChatPage {
        val all = loadChatEntries(scId, sessionId)
        val total = all.size
        val endExclusive = (total - loadedCount).coerceAtLeast(0)
        val startInclusive = (endExclusive - pageSize).coerceAtLeast(0)
        val slice = if (startInclusive < endExclusive) {
            all.subList(startInclusive, endExclusive).toList()
        } else {
            emptyList()
        }
        return AgentChatPage(
            entries = slice,
            totalCount = total,
            startIndex = startInclusive,
            hasOlder = startInclusive > 0
        )
    }

    /**
     * Appends an [AgentChatEntry] to [sessionId] without truncating old entries, and updates
     * the session metadata (title auto-generation on first user prompt, messageCount, lastPreview, updatedAt).
     */
    @Synchronized
    fun appendChatEntry(scId: String, entry: AgentChatEntry, sessionId: String? = null) {
        val resolvedId = resolveSessionId(scId, sessionId)
        val sDir = getSessionDir(scId, resolvedId)
        val list = loadChatEntries(scId, resolvedId)
        list.add(entry)
        File(sDir, "chat.json").writeText(gson.toJson(list))

        // Append to per-session journal.jsonl
        try {
            File(sDir, "journal.jsonl").appendText(compactGson.toJson(entry) + "\n")
        } catch (_: Exception) {
        }

        syncSessionStats(scId, resolvedId, list, latestAdded = entry)
    }

    @Synchronized
    private fun syncSessionStats(
        scId: String,
        sessionId: String,
        entries: List<AgentChatEntry>,
        latestAdded: AgentChatEntry? = null
    ) {
        val agentDir = getAgentDir(scId)
        val sessions = readSessionsIndexInternal(agentDir)
        val idx = sessions.indexOfFirst { it.id == sessionId }
        val last = latestAdded ?: entries.lastOrNull()
        val preview = entries.lastOrNull {
            it.type == ChatEntryType.USER || it.type == ChatEntryType.ASSISTANT || it.type == ChatEntryType.ERROR
        }?.body
            ?.substringBefore("\n\n---Perhatian Agent:")
            ?.lineSequence()
            ?.firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.take(100)
            ?: last?.title?.take(80)
            ?: ""

        if (idx >= 0) {
            val current = sessions[idx]
            val autoTitle = if (latestAdded?.type == ChatEntryType.USER && isDefaultSessionTitle(current.title)) {
                deriveSessionTitleFromPrompt(latestAdded.body, fallback = current.title)
            } else {
                current.title
            }
            sessions[idx] = current.copy(
                title = autoTitle,
                updatedAt = last?.timestamp ?: System.currentTimeMillis(),
                messageCount = entries.size,
                lastPreview = preview
            )
            writeSessionsIndexInternal(agentDir, sortSessions(sessions))
        }
    }

    /**
     * Full-text search across all sessions in [scId]. Matches session titles and message bodies.
     */
    @Synchronized
    fun searchSessions(scId: String, rawQuery: String, maxResults: Int = 60): List<AgentSessionSearchHit> {
        val query = rawQuery.trim()
        if (query.isEmpty()) return emptyList()
        val hits = mutableListOf<AgentSessionSearchHit>()
        val sessions = listSessions(scId)

        for (meta in sessions) {
            if (meta.title.contains(query, ignoreCase = true)) {
                hits.add(
                    AgentSessionSearchHit(
                        sessionId = meta.id,
                        sessionTitle = meta.title,
                        entryId = null,
                        entryType = null,
                        snippet = meta.lastPreview.ifBlank { "Judul sesi cocok: ${meta.title}" },
                        timestamp = meta.updatedAt
                    )
                )
                if (hits.size >= maxResults) return hits
            }
            val entries = loadChatEntries(scId, meta.id)
            for (e in entries.asReversed()) {
                val cleanBody = e.body.substringBefore("\n\n---Perhatian Agent:")
                val matchIdx = cleanBody.indexOf(query, ignoreCase = true)
                val titleMatch = e.title.contains(query, ignoreCase = true)
                if (matchIdx >= 0 || titleMatch) {
                    val snippet = if (matchIdx >= 0) {
                        val start = (matchIdx - 36).coerceAtLeast(0)
                        val end = (matchIdx + query.length + 64).coerceAtMost(cleanBody.length)
                        val prefix = if (start > 0) "…" else ""
                        val suffix = if (end < cleanBody.length) "…" else ""
                        prefix + cleanBody.substring(start, end).replace('\n', ' ').trim() + suffix
                    } else {
                        "${e.title}: ${cleanBody.take(90).replace('\n', ' ')}"
                    }
                    hits.add(
                        AgentSessionSearchHit(
                            sessionId = meta.id,
                            sessionTitle = meta.title,
                            entryId = e.id,
                            entryType = e.type,
                            snippet = snippet,
                            timestamp = e.timestamp
                        )
                    )
                    if (hits.size >= maxResults) return hits
                }
            }
        }
        return hits
    }

    /**
     * Formats an entire session into a clean, structured Markdown document for export/sharing.
     */
    @Synchronized
    fun exportSessionMarkdown(scId: String, sessionId: String = resolveSessionId(scId)): String {
        val meta = getSessionMeta(scId, sessionId)
        val entries = loadChatEntries(scId, sessionId)
        val plan = loadPlan(scId, sessionId)
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        return buildString {
            appendLine("# ${meta?.title ?: "Percakapan AI Agent"}")
            appendLine()
            appendLine("- **Proyek Sketchware:** `#$scId`")
            appendLine("- **ID Sesi:** `${meta?.id ?: sessionId}`")
            if (meta != null) {
                appendLine("- **Dibuat:** ${df.format(Date(meta.createdAt))}")
                appendLine("- **Diperbarui:** ${df.format(Date(meta.updatedAt))}")
            }
            appendLine("- **Total Entri:** ${entries.size}")
            appendLine()

            if (plan.goal.isNotBlank() || plan.tasks.isNotEmpty()) {
                appendLine("## Long-Horizon Plan")
                if (plan.goal.isNotBlank()) {
                    appendLine("**Goal:** ${plan.goal}")
                    appendLine()
                }
                for (t in plan.tasks) {
                    val box = if (t.status == TaskStatus.COMPLETED) "[x]" else "[ ]"
                    appendLine("- $box **[${t.status}]** (${t.milestone}) `${t.id}`: ${t.title}")
                }
                appendLine()
            }

            appendLine("## Transkrip Percakapan")
            appendLine()
            for (e in entries) {
                val timeStr = df.format(Date(e.timestamp))
                when (e.type) {
                    ChatEntryType.USER -> {
                        val cleanBody = e.body.substringBefore("\n\n---Perhatian Agent:").trim()
                        appendLine("### 👤 Anda ($timeStr)")
                        if (!e.checkpointPath.isNullOrBlank()) {
                            appendLine("> 📌 Checkpoint: `${e.checkpointPath}`")
                            appendLine()
                        }
                        appendLine(cleanBody)
                        appendLine()
                    }
                    ChatEntryType.ASSISTANT -> {
                        appendLine("### 🦌 Sketchware Agent — ${e.title} ($timeStr)")
                        appendLine(e.body.trim())
                        appendLine()
                    }
                    ChatEntryType.TOOL_CALL -> {
                        appendLine("<details><summary>🔧 <b>${e.title}</b> ($timeStr)</summary>")
                        appendLine()
                        appendLine("```json")
                        appendLine(e.body.trim())
                        appendLine("```")
                        appendLine("</details>")
                        appendLine()
                    }
                    ChatEntryType.TOOL_RESULT -> {
                        appendLine("<details><summary>📤 <b>${e.title}</b> ($timeStr)</summary>")
                        appendLine()
                        appendLine("```text")
                        appendLine(e.body.trim())
                        appendLine("```")
                        appendLine("</details>")
                        appendLine()
                    }
                    ChatEntryType.STATUS -> {
                        appendLine("> ℹ️ **${e.title}**: ${e.body.trim()}")
                        appendLine()
                    }
                    ChatEntryType.ERROR -> {
                        appendLine("> ⚠️ **${e.title}**: ${e.body.trim()}")
                        appendLine()
                    }
                }
            }
        }
    }

    /**
     * Clears the specified session's chat and plan (or active session if omitted).
     */
    @Synchronized
    fun clearSession(scId: String, sessionId: String? = null) {
        val resolvedId = resolveSessionId(scId, sessionId)
        val dir = getSessionDir(scId, resolvedId)
        File(dir, "chat.json").delete()
        File(dir, "plan.json").delete()
        File(dir, "journal.jsonl").delete()
        syncSessionStats(scId, resolvedId, emptyList())
    }
}
