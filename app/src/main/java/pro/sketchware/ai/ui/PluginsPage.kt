package pro.sketchware.ai.ui

import android.app.Activity
import android.view.View
import android.widget.LinearLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import pro.sketchware.R
import pro.sketchware.ai.config.AiConfigManager
import pro.sketchware.ai.extensions.AgentPlugin
import pro.sketchware.ai.extensions.PluginManager

private fun lang(activity: Activity) = ExtText(AiConfigManager(activity).agentLanguage)

/**
 * Dedicated Plugins page. A plugin bundles skills, slash commands and MCP connectors, so each card
 * shows what it contributes and the detail dialog lists every contribution.
 */
internal class PluginsPage(
    activity: Activity,
    onClosed: (() -> Unit)? = null
) : CapabilityPage(
    activity,
    lang(activity).t("Plugin", "Plugins"),
    lang(activity).t(
        "Paket yang menambah skill, perintah slash, dan konektor MCP sekaligus.",
        "Bundles that add skills, slash commands and MCP connectors in one go."
    ),
    R.drawable.ic_mtrl_puzzle,
    onClosed
) {

    override fun filters(): List<FilterDef> = listOf(
        FilterDef("all", x.t("Semua", "All")),
        FilterDef("enabled", x.t("Aktif", "Enabled")),
        FilterDef("disabled", x.t("Nonaktif", "Disabled"))
    )

    override fun actions(): List<View> = listOf(
        ui.button(x.t("Pasang plugin", "Install plugin"), ButtonKind.PRIMARY, R.drawable.ic_mtrl_add) { showInstall() }
    )

    override fun emptyView(): View = ui.emptyState(
        R.drawable.ic_mtrl_puzzle,
        x.t("Belum ada plugin", "No plugins yet"),
        x.t("Pasang plugin dari berkas .zip, folder, atau URL plugin.json.", "Install a plugin from a .zip file, a folder, or a plugin.json URL."),
        x.t("Pasang plugin", "Install plugin")
    ) { showInstall() }

    override fun renderItems(box: LinearLayout, query: String, filterKey: String): ListStats {
        val all = PluginManager.listPlugins()
        val shown = all.filter { pl ->
            val passes = when (filterKey) {
                "enabled" -> pl.enabled
                "disabled" -> !pl.enabled
                else -> true
            }
            passes && CapabilityLogic.matches(
                query, pl.name, pl.id, pl.description, pl.author,
                pl.slashCommands.joinToString(" ") { it.command }
            )
        }.sortedWith(compareByDescending<AgentPlugin> { it.enabled }.thenBy { it.name.lowercase() })
        shown.forEach { box.addView(card(it)) }
        return ListStats(all.size, shown.size, all.count { it.enabled })
    }

    private fun card(pl: AgentPlugin): View {
        val chips = ArrayList<String>()
        if (pl.skillIds.isNotEmpty()) chips += "${pl.skillIds.size} skill"
        if (pl.slashCommands.isNotEmpty()) chips += "${pl.slashCommands.size} " + x.t("perintah", "commands")
        if (pl.mcpConnectorIds.isNotEmpty()) chips += "${pl.mcpConnectorIds.size} MCP"
        val actions = listOf(
            ui.button(x.t("Detail", "Details"), ButtonKind.TEXT) { showDetail(pl) },
            ui.button(x.t("Hapus", "Delete"), ButtonKind.DANGER) {
                confirm(
                    x.t("Hapus plugin?", "Delete plugin?"),
                    x.t("Plugin '${pl.name}' dan berkasnya akan dihapus permanen.", "Plugin '${pl.name}' and its files will be deleted permanently."),
                    x.t("Hapus", "Delete")
                ) {
                    toast(if (PluginManager.deletePlugin(pl.id)) x.t("Plugin dihapus.", "Plugin deleted.") else x.t("Gagal menghapus plugin.", "Could not delete the plugin."))
                    refresh()
                }
            }
        )
        return ui.capabilityCard(
            name = pl.name,
            iconRes = R.drawable.ic_mtrl_puzzle,
            description = pl.description,
            sourceLabel = "v${pl.version}",
            enabled = pl.enabled,
            onToggle = { on ->
                PluginManager.setPluginEnabled(pl.id, on)
                refresh()
            },
            chips = chips,
            monoLine = if (pl.author.isNotBlank()) x.t("oleh ${pl.author}", "by ${pl.author}") + " · " + pl.id else pl.id,
            actions = actions
        )
    }

    private fun showDetail(pl: AgentPlugin) {
        val views = ArrayList<View>()
        if (pl.description.isNotBlank()) views += ui.text(pl.description, 13.5f, p.foreground)
        views += ui.text("${pl.id}  ·  v${pl.version}" + if (pl.author.isNotBlank()) "  ·  ${pl.author}" else "", 11.5f, p.mutedForeground, mono = true).apply {
            setPadding(0, ui.dp(6), 0, 0)
        }
        fun section(label: String, lines: List<String>) {
            if (lines.isEmpty()) return
            views += ui.sectionLabel(label)
            lines.forEach { views += ui.text(it, 12.5f, p.foreground, mono = true).apply { setPadding(0, 0, 0, ui.dp(4)) } }
        }
        section(x.t("Perintah slash", "Slash commands"), pl.slashCommands.map { "/${it.command.removePrefix("/")}  ${it.title}" })
        section("Skill", pl.skillIds)
        section(x.t("Konektor MCP", "MCP connectors"), pl.mcpConnectorIds)
        if (pl.systemPromptAppend.isNotBlank()) {
            section(x.t("Tambahan system prompt", "System prompt addition"), listOf(pl.systemPromptAppend.trim().take(600) + if (pl.systemPromptAppend.length > 600) "…" else ""))
        }
        if (views.size <= 2) views += ui.text(x.t("Plugin ini tidak menambahkan komponen apa pun.", "This plugin does not contribute any components."), 12.5f, p.mutedForeground)
        MaterialAlertDialogBuilder(activity)
            .setTitle(pl.name)
            .setView(formBox(*views.toTypedArray()))
            .setPositiveButton(x.t("Tutup", "Close"), null)
            .show()
    }

    private fun showInstall() {
        val src = ui.field(x.t("Path .zip / folder, atau URL plugin.json", "Path to .zip / folder, or plugin.json URL"))
        val hint = ui.text(
            x.t("Contoh: /storage/emulated/0/Download/plugin.zip atau https://…/plugin.json", "Example: /storage/emulated/0/Download/plugin.zip or https://…/plugin.json"),
            12f, p.mutedForeground
        )
        formDialog(x.t("Pasang plugin", "Install plugin"), formBox(src.layout, hint), x.t("Pasang", "Install")) {
            val s = src.edit.text?.toString()?.trim().orEmpty()
            if (s.isEmpty()) {
                toast(x.t("Masukkan path atau URL terlebih dahulu.", "Enter a path or URL first."))
                return@formDialog false
            }
            toast(x.t("Memasang plugin…", "Installing plugin…"))
            runInBackground({ PluginManager.installPluginFromArchiveOrUrl(s) }) { result ->
                toast(result.getOrElse { it.message ?: "Error" })
                refresh()
            }
            true
        }
    }
}
