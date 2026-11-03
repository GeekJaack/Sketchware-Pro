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
        FilterDef("all", tx.t("Semua", "All")),
        FilterDef("enabled", tx.t("Aktif", "Enabled")),
        FilterDef("disabled", tx.t("Nonaktif", "Disabled"))
    )

    override fun actions(): List<View> = listOf(
        ui.button(tx.t("Pasang plugin", "Install plugin"), ButtonKind.PRIMARY, R.drawable.ic_mtrl_add) { showInstall() }
    )

    override fun emptyView(): View = ui.emptyState(
        R.drawable.ic_mtrl_puzzle,
        tx.t("Belum ada plugin", "No plugins yet"),
        tx.t("Pasang plugin dari berkas .zip, folder, atau URL plugin.json.", "Install a plugin from a .zip file, a folder, or a plugin.json URL."),
        tx.t("Pasang plugin", "Install plugin")
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
        if (pl.slashCommands.isNotEmpty()) chips += "${pl.slashCommands.size} " + tx.t("perintah", "commands")
        if (pl.mcpConnectorIds.isNotEmpty()) chips += "${pl.mcpConnectorIds.size} MCP"
        val actions = listOf(
            ui.button(tx.t("Detail", "Details"), ButtonKind.TEXT) { showDetail(pl) },
            ui.button(tx.t("Hapus", "Delete"), ButtonKind.DANGER) {
                confirm(
                    tx.t("Hapus plugin?", "Delete plugin?"),
                    tx.t("Plugin '${pl.name}' dan berkasnya akan dihapus permanen.", "Plugin '${pl.name}' and its files will be deleted permanently."),
                    tx.t("Hapus", "Delete")
                ) {
                    toast(if (PluginManager.deletePlugin(pl.id)) tx.t("Plugin dihapus.", "Plugin deleted.") else tx.t("Gagal menghapus plugin.", "Could not delete the plugin."))
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
            monoLine = if (pl.author.isNotBlank()) tx.t("oleh ${pl.author}", "by ${pl.author}") + " · " + pl.id else pl.id,
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
        section(tx.t("Perintah slash", "Slash commands"), pl.slashCommands.map { "/${it.command.removePrefix("/")}  ${it.title}" })
        section("Skill", pl.skillIds)
        section(tx.t("Konektor MCP", "MCP connectors"), pl.mcpConnectorIds)
        if (pl.systemPromptAppend.isNotBlank()) {
            section(tx.t("Tambahan system prompt", "System prompt addition"), listOf(pl.systemPromptAppend.trim().take(600) + if (pl.systemPromptAppend.length > 600) "…" else ""))
        }
        if (views.size <= 2) views += ui.text(tx.t("Plugin ini tidak menambahkan komponen apa pun.", "This plugin does not contribute any components."), 12.5f, p.mutedForeground)
        MaterialAlertDialogBuilder(activity)
            .setTitle(pl.name)
            .setView(formBox(*views.toTypedArray()))
            .setPositiveButton(tx.t("Tutup", "Close"), null)
            .show()
    }

    private fun showInstall() {
        val src = ui.field(tx.t("Path .zip / folder, atau URL plugin.json", "Path to .zip / folder, or plugin.json URL"))
        val hint = ui.text(
            tx.t("Contoh: /storage/emulated/0/Download/plugin.zip atau https://…/plugin.json", "Example: /storage/emulated/0/Download/plugin.zip or https://…/plugin.json"),
            12f, p.mutedForeground
        )
        formDialog(tx.t("Pasang plugin", "Install plugin"), formBox(src.layout, hint), tx.t("Pasang", "Install")) {
            val s = src.edit.text?.toString()?.trim().orEmpty()
            if (s.isEmpty()) {
                toast(tx.t("Masukkan path atau URL terlebih dahulu.", "Enter a path or URL first."))
                return@formDialog false
            }
            toast(tx.t("Memasang plugin…", "Installing plugin…"))
            runInBackground({ PluginManager.installPluginFromArchiveOrUrl(s) }) { result ->
                toast(result.getOrElse { it.message ?: "Error" })
                refresh()
            }
            true
        }
    }
}
