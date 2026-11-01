package pro.sketchware.ai.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import pro.sketchware.R
import pro.sketchware.ai.agent.AgentChatEntry
import pro.sketchware.ai.agent.AgentMemoryStore
import pro.sketchware.ai.agent.AgentOrchestrator
import pro.sketchware.ai.agent.AgentSessionMeta
import pro.sketchware.ai.agent.ChatEntryType
import pro.sketchware.ai.project.ProjectGateway
import pro.sketchware.utility.SketchwareUtil
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Modular UI controller for Phase 1 Multi-Session Management & Chat UX:
 *  - Grouped Session List in Sidebar (`📌 Disematkan`, `Hari ini`, `Kemarin`, `7 hari terakhir`, `Lebih lama`)
 *  - Live `● Berjalan` indicator badge for the session currently executing in [AgentOrchestrator]
 *  - Session Context Menu (Rename, Pin/Unpin, Duplicate/Fork, Export & Share Markdown, Delete)
 *  - Full-Text Search across all project sessions (`searchSessions`) with direct jump to session/entry
 *  - Message-level Fork (`Cabangkan dari sini`), Edit & Resubmit, Regenerate, and Undo to Turn
 *    with optional `before_ai_task` project checkpoint restore + safety backup.
 */
object SessionUiHelper {

    interface Host {
        val activity: Activity
        val scId: String
        val activeScreenName: String?
        val currentSessionId: String
        val deer: DeerPalette
        val tx: DeerText
        fun onSwitchSession(sessionId: String, scrollToEntryId: String? = null)
        fun onSessionsMutated()
        fun onEditAndResubmit(entry: AgentChatEntry, newPrompt: String, restoreCheckpoint: Boolean)
        fun onRegenerate(entry: AgentChatEntry, restoreCheckpoint: Boolean)
        fun onUndoPerformed(targetEntry: AgentChatEntry, restoredCheckpointId: String?)
    }

    private fun dp(v: Int) = SketchwareUtil.dpToPx(v.toFloat())
    private fun dp(v: Float) = SketchwareUtil.dpToPx(v)

    private fun rounded(color: Int, radiusDp: Float, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(color)
            if (stroke != null) setStroke(maxOf(1, dp(1f)), stroke)
        }

    private fun ripple( deer: DeerPalette, content: Drawable?): Drawable = RippleDrawable(
        ColorStateList.valueOf(ColorUtils.setAlphaComponent(deer.foreground, 30)),
        content,
        (content as? GradientDrawable)?.constantState?.newDrawable()?.mutate()?.also { (it as GradientDrawable).setColor(Color.WHITE) }
            ?: ColorDrawable(Color.WHITE)
    )

    private fun tintedIcon(context: Context, res: Int, tint: Int, sizeDp: Int) = ImageView(context).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(tint)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    // ------------------------------------------------------------------------------------------
    // 1. Sidebar Grouped Session List Rendering
    // ------------------------------------------------------------------------------------------

    fun renderSidebarSessionList(host: Host, container: LinearLayout, closeSidebar: () -> Unit) {
        val ctx = host.activity
        val deer = host.deer
        val tx = host.tx
        container.removeAllViews()

        val sessions = AgentMemoryStore.listSessions(host.scId)
        if (sessions.isEmpty()) {
            container.addView(TextView(ctx).apply {
                text = tx.noRecent
                textSize = 12f
                setTextColor(deer.mutedForeground)
                setPadding(dp(10), dp(6), dp(10), dp(6))
            })
            return
        }

        val grouped = groupSessionsByDate(sessions, tx)
        for ((groupLabel, groupItems) in grouped) {
            container.addView(TextView(ctx).apply {
                text = groupLabel
                textSize = 11f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(deer.mutedForeground)
                setPadding(dp(10), dp(8), dp(10), dp(3))
            })
            for (meta in groupItems) {
                container.addView(
                    buildSidebarSessionRow(host, meta, closeSidebar),
                    LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(2) }
                )
            }
        }
    }

    private fun groupSessionsByDate(
        sessions: List<AgentSessionMeta>,
        tx: DeerText
    ): List<Pair<String, List<AgentSessionMeta>>> {
        val pinned = sessions.filter { it.pinned }
        val unpinned = sessions.filter { !it.pinned }

        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val startOfToday = cal.timeInMillis
        val startOfYesterday = startOfToday - 24L * 3600_000L
        val startOf7Days = startOfToday - 7L * 24L * 3600_000L

        val today = mutableListOf<AgentSessionMeta>()
        val yesterday = mutableListOf<AgentSessionMeta>()
        val last7 = mutableListOf<AgentSessionMeta>()
        val older = mutableListOf<AgentSessionMeta>()

        for (s in unpinned) {
            when {
                s.updatedAt >= startOfToday -> today.add(s)
                s.updatedAt >= startOfYesterday -> yesterday.add(s)
                s.updatedAt >= startOf7Days -> last7.add(s)
                else -> older.add(s)
            }
        }

        val out = mutableListOf<Pair<String, List<AgentSessionMeta>>>()
        if (pinned.isNotEmpty()) out.add(tx.pinnedGroup to pinned)
        if (today.isNotEmpty()) out.add(tx.todayGroup to today)
        if (yesterday.isNotEmpty()) out.add(tx.yesterdayGroup to yesterday)
        if (last7.isNotEmpty()) out.add(tx.last7DaysGroup to last7)
        if (older.isNotEmpty()) out.add(tx.olderGroup to older)
        return out
    }

    private fun buildSidebarSessionRow(
        host: Host,
        meta: AgentSessionMeta,
        closeSidebar: () -> Unit
    ): View {
        val ctx = host.activity
        val deer = host.deer
        val tx = host.tx
        val isSelected = meta.id == host.currentSessionId
        val isRunningHere = AgentOrchestrator.isSessionRunning(host.scId, meta.id)

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(7), dp(6), dp(7))
            background = ripple(
                deer,
                rounded(
                    if (isSelected) deer.sidebarAccent else Color.TRANSPARENT,
                    10f,
                    if (isSelected) deer.border else null
                )
            )
            isClickable = true
            setOnClickListener {
                closeSidebar()
                if (meta.id != host.currentSessionId) {
                    host.onSwitchSession(meta.id)
                }
            }
            setOnLongClickListener {
                showSessionMenu(host, this, meta)
                true
            }
        }

        row.addView(
            tintedIcon(
                ctx,
                if (meta.pinned) R.drawable.ic_mtrl_star else R.drawable.ic_mtrl_chat,
                if (meta.pinned) deer.gold else if (isSelected) deer.foreground else deer.mutedForeground,
                16
            )
        )

        val textCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(ctx).apply {
            text = meta.title
            textSize = 13f
            setTextColor(deer.foreground)
            if (isSelected) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))

        if (isRunningHere) {
            titleRow.addView(TextView(ctx).apply {
                text = tx.runningBadge
                textSize = 10f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(deer.success)
                setPadding(dp(6), dp(1), dp(6), dp(1))
                background = rounded(ColorUtils.setAlphaComponent(deer.success, 28), 8f)
            }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        }
        textCol.addView(titleRow)

        if (meta.lastPreview.isNotBlank()) {
            textCol.addView(TextView(ctx).apply {
                text = meta.lastPreview
                textSize = 11f
                setTextColor(deer.mutedForeground)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(1) })
        }

        row.addView(textCol, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(10) })

        val moreBtn = FrameLayout(ctx).apply {
            isClickable = true
            background = ripple(deer, null)
            addView(
                tintedIcon(ctx, R.drawable.ic_mtrl_more_vertical, deer.mutedForeground, 16),
                FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER)
            )
            setOnClickListener { showSessionMenu(host, this, meta) }
        }
        row.addView(moreBtn, LinearLayout.LayoutParams(dp(28), dp(28)))
        return row
    }

    fun showSessionMenu(host: Host, anchor: View, meta: AgentSessionMeta) {
        val ctx = host.activity
        val tx = host.tx
        PopupMenu(ctx, anchor).apply {
            menu.add(0, 1, 0, "✏️ ${tx.renameSession}")
            menu.add(0, 2, 1, if (meta.pinned) "📌 ${tx.unpinSession}" else "📌 ${tx.pinSession}")
            menu.add(0, 3, 2, "🌿 ${tx.forkSession}")
            menu.add(0, 4, 3, "📤 ${tx.exportMarkdown}")
            menu.add(0, 5, 4, "🗑️ ${tx.deleteSession}")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> showRenameSessionDialog(host, meta)
                    2 -> {
                        AgentMemoryStore.setSessionPinned(host.scId, meta.id, !meta.pinned)
                        host.onSessionsMutated()
                    }
                    3 -> {
                        val forked = AgentMemoryStore.forkSession(host.scId, meta.id, upToEntryId = null, setActive = true)
                        SketchwareUtil.toast("Sesi dicabangkan: ${forked.title}")
                        host.onSwitchSession(forked.id)
                    }
                    4 -> exportAndShareSessionMarkdown(host, meta.id)
                    5 -> confirmDeleteSession(host, meta)
                }
                true
            }
        }.show()
    }

    fun showRenameSessionDialog(host: Host, meta: AgentSessionMeta) {
        val ctx = host.activity
        val input = EditText(ctx).apply {
            setText(meta.title)
            setSelection(text.length)
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(host.tx.renameSession)
            .setView(input)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Simpan") { _, _ ->
                val newTitle = input.text?.toString()?.trim().orEmpty()
                if (newTitle.isNotEmpty()) {
                    AgentMemoryStore.renameSession(host.scId, meta.id, newTitle)
                    host.onSessionsMutated()
                }
            }
            .show()
    }

    private fun confirmDeleteSession(host: Host, meta: AgentSessionMeta) {
        val ctx = host.activity
        if (AgentOrchestrator.isSessionRunning(host.scId, meta.id)) {
            SketchwareUtil.toast("Tidak dapat menghapus sesi yang sedang berjalan. Hentikan agent terlebih dahulu.")
            return
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("${host.tx.deleteSession}?")
            .setMessage("Sesi '${meta.title}' beserta log chat dan plan-nya akan dihapus permanen.")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Hapus") { _, _ ->
                val nextSessionId = AgentMemoryStore.deleteSession(host.scId, meta.id)
                if (meta.id == host.currentSessionId) {
                    host.onSwitchSession(nextSessionId)
                } else {
                    host.onSessionsMutated()
                }
            }
            .show()
    }

    // ------------------------------------------------------------------------------------------
    // 2. Full-Text Search Across All Sessions
    // ------------------------------------------------------------------------------------------

    fun showSearchSessionsSheet(host: Host, closeSidebar: () -> Unit) {
        val ctx = host.activity
        val deer = host.deer
        val tx = host.tx
        val sheet = BottomSheetDialog(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(deer.popover)
            setPadding(dp(14), dp(16), dp(14), dp(24))
            minimumHeight = dp(420)
        }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), dp(10))
        }
        header.addView(tintedIcon(ctx, R.drawable.ic_mtrl_search, deer.primary, 20))
        header.addView(TextView(ctx).apply {
            text = tx.searchSessions
            textSize = 16.5f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(deer.foreground)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(10) })
        root.addView(header)

        val searchInput = EditText(ctx).apply {
            hint = "Ketik kata kunci (judul sesi, nama layar, kode, error)…"
            setHintTextColor(deer.mutedForeground)
            setTextColor(deer.foreground)
            textSize = 14f
            setSingleLine(true)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(deer.card, 12f, deer.border)
        }
        root.addView(searchInput, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        val resultsScroll = NestedScrollView(ctx)
        val resultsList = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        resultsScroll.addView(resultsList, ViewGroup.LayoutParams(-1, -2))
        root.addView(resultsScroll, LinearLayout.LayoutParams(-1, dp(360)))

        val df = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())

        fun updateResults(query: String) {
            resultsList.removeAllViews()
            val q = query.trim()
            if (q.isEmpty()) {
                // Show all sessions when query is empty
                val sessions = AgentMemoryStore.listSessions(host.scId)
                for (m in sessions) {
                    val card = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        background = ripple(deer, rounded(deer.card, 12f, deer.border))
                        isClickable = true
                        setOnClickListener {
                            sheet.dismiss()
                            closeSidebar()
                            host.onSwitchSession(m.id)
                        }
                    }
                    card.addView(TextView(ctx).apply {
                        text = "${if (m.pinned) "📌 " else ""}${m.title} (${m.messageCount} entri)"
                        textSize = 13.5f
                        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                        setTextColor(deer.foreground)
                    })
                    if (m.lastPreview.isNotBlank()) {
                        card.addView(TextView(ctx).apply {
                            text = m.lastPreview
                            textSize = 12f
                            setTextColor(deer.mutedForeground)
                            maxLines = 2
                            ellipsize = TextUtils.TruncateAt.END
                        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
                    }
                    resultsList.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
                }
                return
            }

            val hits = AgentMemoryStore.searchSessions(host.scId, q, maxResults = 60)
            if (hits.isEmpty()) {
                resultsList.addView(TextView(ctx).apply {
                    text = "Tidak ditemukan hasil untuk \"$q\"."
                    textSize = 13f
                    setTextColor(deer.mutedForeground)
                    setPadding(dp(8), dp(16), dp(8), dp(16))
                })
                return
            }

            for (hit in hits) {
                val card = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = ripple(deer, rounded(deer.card, 12f, deer.border))
                    isClickable = true
                    setOnClickListener {
                        sheet.dismiss()
                        closeSidebar()
                        host.onSwitchSession(hit.sessionId, scrollToEntryId = hit.entryId)
                    }
                }
                val topRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                topRow.addView(TextView(ctx).apply {
                    text = hit.sessionTitle
                    textSize = 13f
                    setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(deer.primary)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, -2, 1f))
                topRow.addView(TextView(ctx).apply {
                    text = df.format(Date(hit.timestamp))
                    textSize = 10.5f
                    setTextColor(deer.mutedForeground)
                }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
                card.addView(topRow)

                card.addView(TextView(ctx).apply {
                    text = hit.snippet
                    textSize = 12.5f
                    setTextColor(deer.foreground)
                    maxLines = 3
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

                resultsList.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            }
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateResults(s?.toString().orEmpty())
            }
        })

        updateResults("")
        sheet.setContentView(root)
        sheet.show()
    }

    // ------------------------------------------------------------------------------------------
    // 3. Export & Share Markdown
    // ------------------------------------------------------------------------------------------

    fun exportAndShareSessionMarkdown(host: Host, sessionId: String = host.currentSessionId) {
        val ctx = host.activity
        val md = AgentMemoryStore.exportSessionMarkdown(host.scId, sessionId)
        val meta = AgentMemoryStore.getSessionMeta(host.scId, sessionId)
        val safeTitle = (meta?.title ?: "session")
            .replace(Regex("[^a-zA-Z0-9_-]+"), "_")
            .trim('_')
            .take(40)
            .ifBlank { "session" }

        // Also save into project artifacts so user can open it from Artifacts drawer anytime
        val fileName = "chat_${safeTitle}_${sessionId.takeLast(6)}.md"
        try {
            val artifactsDir = File(AgentMemoryStore.getAgentDir(host.scId), "artifacts").apply { mkdirs() }
            File(artifactsDir, fileName).writeText(md)
        } catch (_: Throwable) {
        }

        val options = arrayOf(
            "📤 Bagikan via Aplikasi Lain (Share Intent)",
            "📋 Salin Seluruh Markdown ke Clipboard",
            "📦 Simpan ke Workspace Artifacts ($fileName)"
        )
        MaterialAlertDialogBuilder(ctx)
            .setTitle(host.tx.exportMarkdown)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        try {
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/markdown"
                                putExtra(Intent.EXTRA_SUBJECT, meta?.title ?: "Transkrip AI Agent #${host.scId}")
                                putExtra(Intent.EXTRA_TEXT, md)
                            }
                            ctx.startActivity(Intent.createChooser(sendIntent, "Bagikan Transkrip Markdown"))
                        } catch (e: Throwable) {
                            SketchwareUtil.toast("Gagal membagikan: ${e.message}")
                        }
                    }
                    1 -> {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("session_markdown", md))
                        SketchwareUtil.toast("Markdown sesi disalin ke clipboard")
                    }
                    2 -> {
                        SketchwareUtil.toast("Tersimpan di Artifacts: $fileName")
                        host.onSessionsMutated()
                    }
                }
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    // ------------------------------------------------------------------------------------------
    // 4. Edit, Regenerate, Fork & Undo Turn with Project Checkpoint Restore
    // ------------------------------------------------------------------------------------------

    fun showEditUserPromptDialog(host: Host, entry: AgentChatEntry) {
        val ctx = host.activity
        if (AgentOrchestrator.isRunning) {
            SketchwareUtil.toast("Hentikan eksekusi agent yang sedang berjalan terlebih dahulu.")
            return
        }
        val cleanBody = entry.body.substringBefore("\n\n---Perhatian Agent:").trim()
        val hasCheckpoint = !entry.checkpointPath.isNullOrBlank() && File(entry.checkpointPath).exists()

        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(4))
        }
        val input = EditText(ctx).apply {
            setText(cleanBody)
            setSelection(text.length)
            minLines = 3
            maxLines = 8
            gravity = Gravity.TOP or Gravity.START
        }
        layout.addView(input, LinearLayout.LayoutParams(-1, -2))

        val cbRestore = CheckBox(ctx).apply {
            text = "Pulihkan juga state proyek ke checkpoint sebelum pesan ini (${entry.checkpointPath?.let { File(it).name } ?: ""})"
            textSize = 12.5f
            isChecked = hasCheckpoint
            visibility = if (hasCheckpoint) View.VISIBLE else View.GONE
        }
        layout.addView(cbRestore, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        MaterialAlertDialogBuilder(ctx)
            .setTitle(host.tx.editPrompt)
            .setView(layout)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Kirim Ulang") { _, _ ->
                val newText = input.text?.toString()?.trim().orEmpty()
                if (newText.isNotEmpty()) {
                    host.onEditAndResubmit(entry, newText, cbRestore.isChecked && hasCheckpoint)
                }
            }
            .show()
    }

    fun confirmRegenerateTurn(host: Host, entry: AgentChatEntry) {
        val ctx = host.activity
        if (AgentOrchestrator.isRunning) {
            SketchwareUtil.toast("Hentikan eksekusi agent yang sedang berjalan terlebih dahulu.")
            return
        }
        val entries = AgentMemoryStore.loadChatEntries(host.scId, host.currentSessionId)
        val idx = entries.indexOfFirst { it.id == entry.id }
        val userEntry = if (idx >= 0) {
            (idx downTo 0).map { entries[it] }.firstOrNull { it.type == ChatEntryType.USER }
        } else null
        val cpPath = userEntry?.checkpointPath
        val hasCheckpoint = !cpPath.isNullOrBlank() && File(cpPath).exists()

        if (!hasCheckpoint) {
            host.onRegenerate(entry, restoreCheckpoint = false)
            return
        }

        val cpName = File(cpPath).name
        MaterialAlertDialogBuilder(ctx)
            .setTitle(host.tx.regenerate)
            .setMessage("Ingin memulihkan file proyek ke snapshot sebelum giliran ini ($cpName) sebelum menjalankan ulang?")
            .setNeutralButton("Batal", null)
            .setNegativeButton("Tanpa Rollback Proyek") { _, _ ->
                host.onRegenerate(entry, restoreCheckpoint = false)
            }
            .setPositiveButton("Rollback & Ulangi") { _, _ ->
                host.onRegenerate(entry, restoreCheckpoint = true)
            }
            .show()
    }

    fun confirmForkFromEntry(host: Host, entry: AgentChatEntry) {
        val forked = AgentMemoryStore.forkSession(
            scId = host.scId,
            sourceSessionId = host.currentSessionId,
            upToEntryId = entry.id,
            setActive = true
        )
        SketchwareUtil.toast("Sesi baru dicabangkan: ${forked.title}")
        host.onSwitchSession(forked.id)
    }

    /**
     * Undo Turn with Confirmation Dialog:
     * Finds the nearest `before_ai_task` checkpoint associated with [targetEntry] (or subsequent
     * entries being rolled back), shows a clear summary of how many chat entries will be truncated
     * and which checkpoint will be restored, creates a safety backup checkpoint first, restores the
     * project checkpoint, and truncates the session history.
     */
    fun confirmUndoToEntry(host: Host, targetEntry: AgentChatEntry) {
        val ctx = host.activity
        if (AgentOrchestrator.isRunning) {
            SketchwareUtil.toast("Hentikan eksekusi agent yang sedang berjalan terlebih dahulu.")
            return
        }
        val entries = AgentMemoryStore.loadChatEntries(host.scId, host.currentSessionId)
        val idx = entries.indexOfFirst { it.id == targetEntry.id }
        if (idx < 0) return

        // If user clicked Undo on a USER message, we remove from that USER message onward and restore its before_ai_task checkpoint.
        // If user clicked Undo on an ASSISTANT message, we keep up to that ASSISTANT message and restore the checkpoint of the NEXT user turn (if any).
        val inclusive = targetEntry.type == ChatEntryType.USER
        val cutIndex = if (inclusive) idx else idx + 1
        val entriesToRemove = if (cutIndex < entries.size) entries.subList(cutIndex, entries.size) else emptyList()

        // Find the earliest checkpoint created at or after the cut point
        val checkpointPath = entriesToRemove.firstNotNullOfOrNull { it.checkpointPath?.takeIf { p -> p.isNotBlank() && File(p).exists() } }
            ?: targetEntry.checkpointPath?.takeIf { inclusive && it.isNotBlank() && File(it).exists() }

        val cpId = checkpointPath?.let { File(it).name }
        val userTurnsRemoved = entriesToRemove.count { it.type == ChatEntryType.USER }
        val toolStepsRemoved = entriesToRemove.count { it.type == ChatEntryType.TOOL_CALL }

        val details = buildString {
            if (inclusive) {
                appendLine("Pesan ini dan seluruh percakapan setelahnya akan di-undo:")
            } else {
                appendLine("Seluruh percakapan setelah respons ini akan di-undo:")
            }
            appendLine("• Entri chat dihapus: ${entriesToRemove.size} ($userTurnsRemoved prompt, $toolStepsRemoved langkah tool)")
            if (cpId != null) {
                appendLine("• Checkpoint proyek tersedia: $cpId")
                appendLine("• Safety backup otomatis akan dibuat terlebih dahulu sebelum rollback.")
            } else {
                appendLine("• Tidak ada file checkpoint proyek pada titik ini (hanya riwayat chat yang dipotong).")
            }
        }.trim()

        val builder = MaterialAlertDialogBuilder(ctx)
            .setTitle("⏪ ${host.tx.undoToHere}?")
            .setMessage(details)
            .setNeutralButton("Batal", null)

        if (cpId != null) {
            builder.setNegativeButton("Undo Chat Saja") { _, _ ->
                AgentMemoryStore.truncateSessionFromEntry(host.scId, host.currentSessionId, targetEntry.id, inclusive = inclusive)
                host.onUndoPerformed(targetEntry, null)
            }
            builder.setPositiveButton("Undo Chat & Rollback Proyek") { _, _ ->
                try {
                    ProjectGateway.createCheckpoint(host.scId, "before_undo_safety")
                } catch (_: Throwable) {
                }
                val restoreMsg = try {
                    ProjectGateway.restoreCheckpoint(host.scId, cpId)
                } catch (e: Throwable) {
                    "Gagal restore checkpoint: ${e.message}"
                }
                ProjectGateway.notifyUiChanged(host.scId, host.activeScreenName)
                AgentMemoryStore.truncateSessionFromEntry(host.scId, host.currentSessionId, targetEntry.id, inclusive = inclusive)
                SketchwareUtil.toast(restoreMsg)
                host.onUndoPerformed(targetEntry, cpId)
            }
        } else {
            builder.setPositiveButton("Undo Sekarang") { _, _ ->
                AgentMemoryStore.truncateSessionFromEntry(host.scId, host.currentSessionId, targetEntry.id, inclusive = inclusive)
                host.onUndoPerformed(targetEntry, null)
            }
        }
        builder.show()
    }
}
