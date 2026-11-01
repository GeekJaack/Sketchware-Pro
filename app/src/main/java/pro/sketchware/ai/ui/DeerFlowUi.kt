package pro.sketchware.ai.ui

import android.graphics.Color
import pro.sketchware.ai.config.AgentAutonomyMode
import pro.sketchware.ai.config.AgentLanguage

/**
 * DeerFlow-inspired design system for the AI Agent Studio.
 *
 * Color tokens are converted from the open-source DeerFlow web UI (`frontend/src/styles/globals.css`,
 * shadcn/oklch palette): a warm cream light theme and a warm charcoal dark theme with a monochrome
 * primary, subtle 10% borders, a blue focus ring and a gold "Ultra" accent.
 */
data class DeerPalette(
    val background: Int,
    val card: Int,
    val popover: Int,
    val primary: Int,
    val primaryForeground: Int,
    val secondary: Int,
    val muted: Int,
    val mutedForeground: Int,
    val accent: Int,
    val foreground: Int,
    val border: Int,
    val input: Int,
    val ring: Int,
    val destructive: Int,
    val success: Int,
    val warning: Int,
    val sidebar: Int,
    val sidebarAccent: Int,
    val gold: Int,
    val goldLight: Int,
    val goldPale: Int
)

object DeerFlowTheme {

    private fun c(hex: String) = Color.parseColor(hex)

    val DARK = DeerPalette(
        background = c("#201F1D"),
        card = c("#262523"),
        popover = c("#1A1918"),
        primary = c("#FAFAFA"),
        primaryForeground = c("#1C1C1B"),
        secondary = c("#2E2D2B"),
        muted = c("#262523"),
        mutedForeground = c("#A1A1A1"),
        accent = c("#333230"),
        foreground = c("#FAFAFA"),
        border = c("#363533"),
        input = c("#42413F"),
        ring = c("#5EA2FF"),
        destructive = c("#FF6467"),
        success = c("#4ADE80"),
        warning = c("#FBBF24"),
        sidebar = c("#21201E"),
        sidebarAccent = c("#2B2A28"),
        gold = c("#E3A812"),
        goldLight = c("#E9C665"),
        goldPale = c("#EFEFBB")
    )

    val LIGHT = DeerPalette(
        background = c("#FBF9F3"),
        card = c("#FFFDF7"),
        popover = c("#FFFDF7"),
        primary = c("#0A0A0A"),
        primaryForeground = c("#FAFAFA"),
        secondary = c("#EFEDE6"),
        muted = c("#F6F4EE"),
        mutedForeground = c("#737373"),
        accent = c("#ECEAE3"),
        foreground = c("#0A0A0A"),
        border = c("#E7E5DE"),
        input = c("#D9D7D0"),
        ring = c("#2F7CF6"),
        destructive = c("#E7000B"),
        success = c("#16A34A"),
        warning = c("#D97706"),
        sidebar = c("#F4F2EC"),
        sidebarAccent = c("#E8E6DF"),
        gold = c("#C98F00"),
        goldLight = c("#E3A812"),
        goldPale = c("#E9C665")
    )

    fun palette(isDark: Boolean) = if (isDark) DARK else LIGHT

    /** DeerFlow-style tier tag for each autonomy mode (Flash / Pro / Ultra …). */
    fun modeTag(mode: AgentAutonomyMode): String = when (mode) {
        AgentAutonomyMode.ASK -> "Read-only"
        AgentAutonomyMode.COPILOT -> "Flash"
        AgentAutonomyMode.AGENT -> "Pro"
        AgentAutonomyMode.AUTOPILOT -> "Ultra"
        AgentAutonomyMode.TUTOR -> "Guide"
    }
}

/**
 * Short UI labels introduced by the DeerFlow-style redesign. Indonesian and English are provided;
 * every other language falls back to English (long-form strings still come from [pro.sketchware.ai.config.AiStrings]).
 */
class DeerText(lang: AgentLanguage) {
    private val id = lang.resolved() == AgentLanguage.ID
    private fun t(idText: String, en: String) = if (id) idText else en

    val greeting = t("Halo, mari membangun!", "Hello, again!")
    val greetingUltra = t("Mode Ultra siap!", "Ultra mode is ready!")
    val welcomeDesc = t(
        "Selamat datang di Sketchware Agent — super agent open source di HP Anda. Dengan tools, skill & MCP bawaan, agent dapat merancang layar, menyusun blok logika, mengelola library, compile APK, dan memperbaiki error secara otomatis.",
        "Welcome to Sketchware Agent, an open source super agent on your phone. With built-in tools, skills and MCP, it designs screens, writes block logic, manages libraries, compiles APKs and self-heals errors."
    )
    val newChat = t("Percakapan baru", "New chat")
    val chats = t("Chat", "Chats")
    val artifacts = "Artifacts"
    val artifactsDesc = t("File & hasil yang dibuat agent di percakapan ini", "Files and outputs produced in this conversation")
    val noArtifacts = t("Belum ada artifact. Hasil kerja agent (layout, Java, resource, APK) akan muncul di sini.", "No artifacts yet. Agent outputs (layouts, Java, resources, APK) will appear here.")
    val agentModes = t("Mode Agent", "Agent modes")
    val skills = "Skills"
    val plugins = "Plugins"
    val mcp = t("Konektor MCP", "MCP connectors")
    val terminal = "Terminal"
    val checkpoints = t("Checkpoint", "Checkpoints")
    val recent = t("Riwayat prompt", "Recent prompts")
    val noRecent = t("Belum ada prompt di percakapan ini.", "No prompts in this conversation yet.")
    val settings = t("Pengaturan", "Settings")
    val language = t("Bahasa", "Language")
    val closeStudio = t("Tutup Studio", "Close studio")
    val workspace = t("Workspace", "Workspace")
    val todos = "To-dos"
    val modelNotConfigured = t("Model belum dikonfigurasi.", "No model configured.")
    val configureNow = t("Atur sekarang", "Configure")
    val mode = "Mode"
    val chipBuild = t("Bangun", "Build")
    val chipFix = t("Perbaiki", "Fix")
    val chipDesign = t("Desain", "Design")
    val chipLearn = t("Pelajari", "Learn")
    val chipCreate = t("Buat", "Create")
    val createScreen = t("Layar baru", "New screen")
    val createLogic = t("Logika blok", "Block logic")
    val createLibrary = t("Library Maven", "Maven library")
    val createSkill = t("Skill baru", "New skill")
    val promptCreateScreen = t("Buat layar baru bernama [nama_layar] berisi [deskripsi tampilan], lalu hubungkan dari layar utama.", "Create a new screen named [screen_name] containing [ui description], and navigate to it from the main screen.")
    val promptCreateLogic = t("Tambahkan logika blok pada [event/tombol] agar [perilaku yang diinginkan].", "Add block logic to [event/button] so that [desired behavior].")
    val promptCreateLibrary = t("Tambahkan library Maven [group:artifact:version] ke proyek ini dan contohkan penggunaannya.", "Add the Maven library [group:artifact:version] to this project and show an example usage.")
    val promptCreateSkill = t("Kita akan membuat skill baru langkah demi langkah. Pertama, apa yang ingin dilakukan skill ini? [deskripsi skill]", "We're going to build a new skill step by step. To start, what do you want this skill to do? [skill description]")
    val promptLearn = t("Jelaskan cara kerja [fitur/blok Sketchware] dan buatkan tutorial singkat langsung di proyek ini.", "Explain how [Sketchware feature/block] works and create a short hands-on tutorial in this project.")
    val stepsThinking = t("Langkah kerja", "Steps")
    val copy = t("Salin", "Copy")
    val copyPath = t("Salin path", "Copy path")
    val input = "input"
    val output = "output"
    val content = t("Isi", "Content")
    val projectLabel = t("Proyek", "Project")
    val sessions = t("Sesi Percakapan", "Chat Sessions")
    val searchSessions = t("Cari di semua sesi…", "Search all sessions…")
    val pinnedGroup = t("📌 Disematkan", "📌 Pinned")
    val todayGroup = t("Hari ini", "Today")
    val yesterdayGroup = t("Kemarin", "Yesterday")
    val last7DaysGroup = t("7 hari terakhir", "Previous 7 days")
    val olderGroup = t("Lebih lama", "Older")
    val runningBadge = t("● Berjalan", "● Running")
    val loadOlderMessages = t("⬆ Muat pesan sebelumnya", "⬆ Load older messages")
    val renameSession = t("Ubah nama sesi", "Rename session")
    val pinSession = t("Sematkan sesi", "Pin session")
    val unpinSession = t("Lepas sematan", "Unpin session")
    val forkSession = t("Duplikat / Cabangkan sesi", "Duplicate / Fork session")
    val forkHere = t("Cabangkan dari sini", "Fork from here")
    val exportMarkdown = t("Ekspor & Bagikan (.md)", "Export & Share (.md)")
    val deleteSession = t("Hapus sesi", "Delete session")
    val clearCurrentSession = t("Bersihkan isi sesi ini", "Clear this session")
    val editPrompt = t("Edit & Kirim Ulang", "Edit & Resubmit")
    val regenerate = t("Ulangi Respons", "Regenerate")
    val undoToHere = t("Undo ke titik ini", "Undo to here")
}
