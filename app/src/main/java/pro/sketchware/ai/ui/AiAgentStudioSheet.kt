package pro.sketchware.ai.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.besome.sketch.design.DesignActivity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.divider.MaterialDivider
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import mod.hey.studios.project.ProjectTracker
import pro.sketchware.R
import pro.sketchware.ai.agent.AgentChatEntry
import pro.sketchware.ai.agent.AgentMemoryStore
import pro.sketchware.ai.agent.AgentOrchestrator
import pro.sketchware.ai.agent.AgentPlan
import pro.sketchware.ai.agent.ChatEntryType
import pro.sketchware.ai.agent.TaskStatus
import pro.sketchware.ai.config.AgentAutonomyMode
import pro.sketchware.ai.config.AiConfigManager
import pro.sketchware.ai.project.ProjectGateway
import pro.sketchware.utility.SketchwareUtil
import pro.sketchware.utility.ThemeUtils
import java.util.Locale

object AiAgentStudioSheet {

    /**
     * Opens the full-screen AI Agent chat ([AgentChatScreen]) inside [DesignActivity] (or any project context).
     */
    @JvmStatic
    fun show(activity: Activity, scId: String, activeScreenName: String? = null) {
        AgentChatScreen(activity, scId, activeScreenName).show()
    }


    /**
     * Wizard dialog on the Home screen ([pro.sketchware.activities.main.fragments.projects.ProjectsFragment])
     * that creates a brand-new Sketchware project from 0, opens [DesignActivity], and launches the
     * AI Agent in `AUTOPILOT` mode to build the requested app end-to-end!
     */
    @JvmStatic
    fun showCreateAppFromZeroDialog(
        activity: Activity,
        onProjectCreated: java.util.function.Consumer<String>? = null
    ) {
        val config = AiConfigManager(activity)
        val dp8 = SketchwareUtil.dpToPx(8f)
        val dp12 = SketchwareUtil.dpToPx(12f)
        val dp16 = SketchwareUtil.dpToPx(16f)

        val scroll = ScrollView(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp16, dp12, dp16, dp8)
        }
        scroll.addView(root)

        val tvIntro = TextView(activity).apply {
            text = "AI Agent akan membuat proyek Sketchware baru dari 0, merancang Long-Horizon Plan, membangun Layout XML (ViewBean), Variabel, Komponen, Logika Blok (BlockBean), dan memverifikasi kompilasi APK secara otonom."
            textSize = 13f
        }
        root.addView(tvIntro)

        val tilAppName = TextInputLayout(activity).apply {
            hint = "Nama Aplikasi (contoh: Kasir Pintar Pro)"
        }
        val etAppName = TextInputEditText(tilAppName.context).apply {
            setSingleLine(true)
            setText("Smart Notes AI")
        }
        tilAppName.addView(etAppName)
        root.addView(
            tilAppName,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp12
            }
        )

        val tilPkg = TextInputLayout(activity).apply {
            hint = "Package Name (contoh: com.ai.smartnotes)"
        }
        val etPkg = TextInputEditText(tilPkg.context).apply {
            setSingleLine(true)
            setText("com.sketchware.ai.smartnotes")
        }
        tilPkg.addView(etPkg)
        root.addView(
            tilPkg,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp12
            }
        )

        val tilPrompt = TextInputLayout(activity).apply {
            hint = "Deskripsikan Aplikasi yang Ingin Dibangun dari 0"
        }
        val etPrompt = TextInputEditText(tilPrompt.context).apply {
            minLines = 4
            maxLines = 8
            gravity = Gravity.TOP or Gravity.START
            setText(
                "Bangun aplikasi Catatan & Todo List modern dari 0:\n" +
                    "1. Di layar utama (main.xml), buat header ringkasan jumlah catatan, EditText input judul & isi catatan, tombol Tambah, tombol Hapus Semua, dan ListView daftar catatan.\n" +
                    "2. Simpan data catatan secara permanen menggunakan SharedPreferences (File component) dan ListString sehingga saat aplikasi ditutup dan dibuka kembali data tetap tersimpan.\n" +
                    "3. Ketika item ListView diklik, tampilkan Dialog detail catatan beserta opsi hapus item tersebut.\n" +
                    "4. Verifikasi kompilasi hingga sukses tanpa error."
            )
        }
        tilPrompt.addView(etPrompt)
        root.addView(
            tilPrompt,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp12
            }
        )

        MaterialAlertDialogBuilder(activity)
            .setTitle("Bangun Aplikasi dari 0 dengan AI Agent")
            .setIcon(R.drawable.ic_mtrl_code)
            .setView(scroll)
            .setNeutralButton("API Key & Model") { _, _ ->
                ModelSettingsPage(activity, onClosed = {
                    showCreateAppFromZeroDialog(activity, onProjectCreated)
                }).show()
            }
            .setNegativeButton("Batal", null)
            .setPositiveButton("Buat & Jalankan Agent") { d, _ ->
                val appName = etAppName.text?.toString()?.trim().orEmpty().ifEmpty { "My AI App" }
                val rawPkg = etPkg.text?.toString()?.trim().orEmpty().ifEmpty {
                    "com.sketchware.ai." + appName.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "")
                }
                val goalPrompt = etPrompt.text?.toString()?.trim().orEmpty()
                if (goalPrompt.isEmpty()) {
                    SketchwareUtil.toastError("Deskripsi aplikasi tidak boleh kosong")
                    return@setPositiveButton
                }

                config.autonomyMode = AgentAutonomyMode.AUTOPILOT
                val newScId = ProjectGateway.createProjectFromZero(
                    context = activity,
                    appName = appName,
                    packageName = rawPkg,
                    projectName = appName
                )
                onProjectCreated?.accept(newScId)

                val hasApiKey = config.isConfigured()
                if (!hasApiKey) {
                    AgentMemoryStore.appendChatEntry(
                        newScId,
                        pro.sketchware.ai.agent.AgentChatEntry(
                            id = java.util.UUID.randomUUID().toString(),
                            type = pro.sketchware.ai.agent.ChatEntryType.USER,
                            title = "Target Aplikasi (MCP Bridge Mode)",
                            body = goalPrompt
                        )
                    )
                    AgentMemoryStore.appendChatEntry(
                        newScId,
                        pro.sketchware.ai.agent.AgentChatEntry(
                            id = java.util.UUID.randomUUID().toString(),
                            type = pro.sketchware.ai.agent.ChatEntryType.ASSISTANT,
                            title = "🌐 Proyek #$newScId Siap Dikendalikan via MCP (Port ${pro.sketchware.ai.mcp.SketchwareMcpServer.boundPort})",
                            body = "Proyek '$appName' (sc_id=$newScId) telah dibuat! AI Agent eksternal (Antigravity / Claude Code / Cursor) dapat langsung membangun proyek ini lewat MCP Server tanpa API Key di HP."
                        )
                    )
                }

                val intent = Intent(activity, DesignActivity::class.java).apply {
                    ProjectTracker.setScId(newScId)
                    putExtra("sc_id", newScId)
                    if (hasApiKey) {
                        putExtra("ai_initial_prompt", goalPrompt)
                    }
                    putExtra("open_ai_studio", true)
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                activity.startActivity(intent)
                d.dismiss()
            }
            .show()
    }
}
