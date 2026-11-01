package pro.sketchware.ai.ui

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import pro.sketchware.R
import pro.sketchware.ai.config.AiConfigManager
import pro.sketchware.ai.extensions.AgentSkill
import pro.sketchware.ai.extensions.SkillManager
import java.io.File

private fun lang(activity: Activity) = ExtText(AiConfigManager(activity).agentLanguage)

/**
 * Dedicated Skills page: search, source filters (built-in / custom / from plugins), one card per
 * skill with an enable switch, a SKILL.md viewer/editor, URL import and delete.
 */
internal class SkillsPage(
    activity: Activity,
    private val onUseInChat: ((String) -> Unit)? = null,
    onClosed: (() -> Unit)? = null
) : CapabilityPage(
    activity,
    lang(activity).t("Skills", "Skills"),
    lang(activity).t(
        "Keahlian khusus yang dimuat agent saat tugas cocok. Aktifkan hanya yang diperlukan.",
        "Specialised know-how the agent loads when a task matches. Enable only what you need."
    ),
    R.drawable.ic_mtrl_star,
    onClosed
) {

    override fun filters(): List<FilterDef> = listOf(
        FilterDef("all", x.t("Semua", "All")),
        FilterDef("enabled", x.t("Aktif", "Enabled")),
        FilterDef("builtin", x.t("Bawaan", "Built-in")),
        FilterDef("custom", x.t("Kustom", "Custom")),
        FilterDef("plugin", x.t("Dari plugin", "From plugins"))
    )

    override fun actions(): List<View> = listOf(
        ui.button(x.t("Skill baru", "New skill"), ButtonKind.PRIMARY, R.drawable.ic_mtrl_add) { showEditor(null) },
        ui.button(x.t("Impor dari URL", "Import from URL"), ButtonKind.OUTLINE, R.drawable.ic_mtrl_download) { showImportUrl() }
    )

    override fun emptyView(): View = ui.emptyState(
        R.drawable.ic_mtrl_star,
        x.t("Belum ada skill", "No skills yet"),
        x.t("Skill adalah berkas SKILL.md berisi panduan yang dibaca agent saat tugas cocok.", "A skill is a SKILL.md file with guidance the agent reads when a task matches."),
        x.t("Buat skill", "Create skill")
    ) { showEditor(null) }

    override fun renderItems(box: LinearLayout, query: String, filterKey: String): ListStats {
        val all = SkillManager.listAllSkills()
        val shown = all.filter { s ->
            val kind = CapabilityLogic.skillKind(s.source, s.id)
            val passes = when (filterKey) {
                "enabled" -> s.enabled
                "builtin" -> kind == SourceKind.BUILTIN
                "custom" -> kind == SourceKind.CUSTOM
                "plugin" -> kind == SourceKind.PLUGIN
                else -> true
            }
            passes && CapabilityLogic.matches(query, s.name, s.id, s.description, s.triggers.joinToString(" "))
        }.sortedWith(compareByDescending<AgentSkill> { it.enabled }.thenBy { it.name.lowercase() })
        shown.forEach { box.addView(card(it)) }
        return ListStats(all.size, shown.size, all.count { it.enabled })
    }

    private fun card(s: AgentSkill): View {
        val kind = CapabilityLogic.skillKind(s.source, s.id)
        val label = when (kind) {
            SourceKind.BUILTIN -> x.t("Bawaan", "Built-in")
            SourceKind.CUSTOM -> x.t("Kustom", "Custom")
            SourceKind.PLUGIN -> x.t("Plugin", "Plugin") + ": " + (CapabilityLogic.pluginIdOf(s.source) ?: "?")
        }
        val actions = ArrayList<View>()
        actions += ui.button(if (kind == SourceKind.PLUGIN) x.t("Lihat", "View") else x.t("Lihat / Edit", "View / Edit"), ButtonKind.TEXT) { showViewer(s, kind) }
        if (onUseInChat != null && s.enabled) {
            actions += ui.button(x.t("Pakai di chat", "Use in chat"), ButtonKind.TEXT) {
                onUseInChat.invoke(
                    "Gunakan skill '${s.id}' (panggil use_skill(action=\"read\", skill_id=\"${s.id}\")) untuk menyelesaikan tugas berikut: "
                )
                dismiss()
            }
        }
        if (kind != SourceKind.PLUGIN) {
            actions += ui.button(x.t("Hapus", "Delete"), ButtonKind.DANGER) {
                confirm(
                    x.t("Hapus skill?", "Delete skill?"),
                    x.t("Skill '${s.name}' akan dihapus permanen.", "Skill '${s.name}' will be deleted permanently."),
                    x.t("Hapus", "Delete")
                ) {
                    SkillManager.deleteSkill(s.id)
                    refresh()
                }
            }
        }
        return ui.capabilityCard(
            name = s.name,
            iconRes = R.drawable.ic_mtrl_star,
            description = s.description,
            sourceLabel = label,
            enabled = s.enabled,
            onToggle = { on ->
                SkillManager.setSkillEnabled(s.id, on)
                refresh()
            },
            chips = CapabilityLogic.preview(s.triggers, 3),
            monoLine = s.id,
            actions = actions
        )
    }

    // ---------- dialogs ----------

    private fun readSource(s: AgentSkill): String {
        val f = File(s.skillFilePath)
        return if (f.isFile) f.readText() else ""
    }

    private fun showViewer(s: AgentSkill, kind: SourceKind) {
        val content = readSource(s).ifBlank { x.t("(berkas SKILL.md kosong atau tidak ditemukan)", "(SKILL.md is empty or missing)") }
        val body = ui.text(content, 12f, p.foreground, mono = true).apply {
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.15f)
        }
        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(s.name)
            .setView(formBox(body))
            .setNegativeButton(x.t("Tutup", "Close"), null)
        if (kind != SourceKind.PLUGIN) {
            builder.setPositiveButton(x.t("Edit", "Edit")) { _, _ -> showEditor(s) }
        }
        builder.show()
    }

    private fun showEditor(existing: AgentSkill?) {
        val name = ui.field(x.t("Nama skill", "Skill name"), existing?.name.orEmpty())
        if (existing != null) name.edit.isEnabled = false
        val desc = ui.field(x.t("Deskripsi singkat (kapan skill dipakai)", "Short description (when to use it)"), existing?.description.orEmpty())
        val body = ui.field(
            x.t("Isi SKILL.md (Markdown)", "SKILL.md content (Markdown)"),
            existing?.let { readSource(it) }.orEmpty(), mono = true, lines = 10
        )
        formDialog(
            if (existing == null) x.t("Skill baru", "New skill") else x.t("Edit skill", "Edit skill"),
            formBox(name.layout, desc.layout, body.layout),
            x.t("Simpan", "Save")
        ) {
            val n = name.edit.text?.toString()?.trim().orEmpty()
            val d = desc.edit.text?.toString()?.trim().orEmpty()
            val b = body.edit.text?.toString()?.trim().orEmpty()
            if (n.isEmpty() || b.isEmpty()) {
                toast(x.t("Nama dan isi SKILL.md wajib diisi.", "Name and SKILL.md content are required."))
                return@formDialog false
            }
            try {
                SkillManager.createOrUpdateSkill(
                    id = existing?.id ?: SkillManager.sanitizeId(n),
                    name = n,
                    description = d.ifBlank { x.t("Panduan khusus untuk $n", "Guidance for $n") },
                    bodyMarkdown = b
                )
            } catch (e: IllegalArgumentException) {
                toast(e.message ?: "Error")
                return@formDialog false
            }
            toast(x.t("Skill '$n' disimpan.", "Skill '$n' saved."))
            refresh()
            true
        }
    }

    private fun showImportUrl() {
        val url = ui.field("URL SKILL.md (https://…)")
        val hint = ui.text(
            x.t("Tempel tautan langsung ke berkas SKILL.md (mis. dari GitHub raw).", "Paste a direct link to a SKILL.md file (for example a GitHub raw URL)."),
            12f, p.mutedForeground
        )
        formDialog(x.t("Impor skill", "Import skill"), formBox(url.layout, hint), x.t("Impor", "Import")) {
            val u = url.edit.text?.toString()?.trim().orEmpty()
            if (!(u.startsWith("http://") || u.startsWith("https://"))) {
                toast(x.t("Masukkan URL yang diawali http:// atau https://", "Enter a URL starting with http:// or https://"))
                return@formDialog false
            }
            toast(x.t("Mengunduh skill…", "Downloading skill…"))
            runInBackground({ SkillManager.installSkillFromUrl(u) }) { result ->
                toast(result.getOrElse { it.message ?: "Error" })
                refresh()
            }
            true
        }
    }
}
