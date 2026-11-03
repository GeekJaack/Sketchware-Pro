package pro.sketchware.ai.ui

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import pro.sketchware.R
import pro.sketchware.ai.config.AiConfigManager
import pro.sketchware.ai.extensions.McpClientHub
import pro.sketchware.ai.extensions.McpConnectorConfig
import pro.sketchware.ai.extensions.SkillManager

private fun lang(activity: Activity) = ExtText(AiConfigManager(activity).agentLanguage)

/**
 * Dedicated MCP page: connectors with a live status dot, transport/target, the tools each one
 * exposes (expandable), sync, edit and delete. Replaces the old "MCP" tab of the shared hub.
 */
internal class McpPage(
    activity: Activity,
    onClosed: (() -> Unit)? = null
) : CapabilityPage(
    activity,
    lang(activity).t("Konektor MCP", "MCP connectors"),
    lang(activity).t(
        "Hubungkan server MCP (HTTP atau STDIO) agar agent bisa memakai tool eksternal.",
        "Connect MCP servers (HTTP or STDIO) so the agent can use external tools."
    ),
    R.drawable.ic_mtrl_link,
    onClosed
) {

    private val expanded = HashSet<String>()

    override fun filters(): List<FilterDef> = listOf(
        FilterDef("all", tx.t("Semua", "All")),
        FilterDef("connected", tx.t("Terhubung", "Connected")),
        FilterDef("issues", tx.t("Perlu perhatian", "Needs attention")),
        FilterDef("builtin", tx.t("Bawaan", "Built-in")),
        FilterDef("custom", tx.t("Kustom", "Custom"))
    )

    override fun actions(): List<View> = listOf(
        ui.button(tx.t("Konektor baru", "New connector"), ButtonKind.PRIMARY, R.drawable.ic_mtrl_add) { showEditor(null) },
        ui.button(tx.t("Sinkron semua", "Sync all"), ButtonKind.OUTLINE, R.drawable.ic_mtrl_sync) { syncAll() }
    )

    override fun emptyView(): View = ui.emptyState(
        R.drawable.ic_mtrl_link,
        tx.t("Belum ada konektor", "No connectors yet"),
        tx.t("Tambahkan server MCP agar tool-nya muncul di agent.", "Add an MCP server so its tools show up in the agent."),
        tx.t("Tambah konektor", "Add connector")
    ) { showEditor(null) }

    override fun renderItems(box: LinearLayout, query: String, filterKey: String): ListStats {
        val all = McpClientHub.listConnectors()
        val shown = all.filter { c ->
            val state = state(c)
            val passes = when (filterKey) {
                "connected" -> state == McpState.CONNECTED
                "issues" -> state == McpState.ERROR || state == McpState.IDLE
                "builtin" -> CapabilityLogic.mcpKind(c.source) == SourceKind.BUILTIN
                "custom" -> CapabilityLogic.mcpKind(c.source) == SourceKind.CUSTOM
                else -> true
            }
            passes && CapabilityLogic.matches(query, c.name, c.id, c.url, c.command, c.cachedTools.joinToString(" ") { it.name })
        }.sortedWith(compareByDescending<McpConnectorConfig> { it.enabled }.thenBy { it.name.lowercase() })
        shown.forEach { box.addView(card(it)) }
        return ListStats(all.size, shown.size, all.count { it.enabled })
    }

    private fun state(c: McpConnectorConfig) = CapabilityLogic.mcpState(c.enabled, c.lastStatus, c.cachedTools.size)

    private fun card(c: McpConnectorConfig): View {
        val kind = CapabilityLogic.mcpKind(c.source)
        val st = state(c)
        val (dot, statusText) = when (st) {
            McpState.CONNECTED -> p.success to tx.t("Terhubung", "Connected")
            McpState.ERROR -> p.destructive to tx.t("Gagal sinkron", "Sync failed")
            McpState.IDLE -> p.warning to tx.t("Belum disinkronkan", "Not synced yet")
            McpState.DISABLED -> p.mutedForeground to tx.t("Nonaktif", "Disabled")
        }
        val label = when (kind) {
            SourceKind.BUILTIN -> tx.t("Bawaan", "Built-in")
            SourceKind.CUSTOM -> tx.t("Kustom", "Custom")
            SourceKind.PLUGIN -> "Plugin: " + (CapabilityLogic.pluginIdOf(c.source) ?: "?")
        }
        val chips = ArrayList<String>()
        chips += if (c.transport == "stdio") "STDIO" else "HTTP"
        if (c.transport == "stdio") chips += if (c.inLinux) "Linux (${c.linuxDistro})" else "Host"
        chips += "${c.cachedTools.size} tool"

        val isOpen = c.id in expanded
        val actions = ArrayList<View>()
        if (c.cachedTools.isNotEmpty()) {
            actions += ui.button((if (isOpen) tx.t("Sembunyikan tool", "Hide tools") else "Tools (${c.cachedTools.size})"), ButtonKind.TEXT) {
                if (isOpen) expanded.remove(c.id) else expanded.add(c.id)
                refresh()
            }
        }
        actions += ui.button(tx.t("Sinkron", "Sync"), ButtonKind.TEXT, R.drawable.ic_mtrl_sync) { syncOne(c) }
        if (kind == SourceKind.CUSTOM) actions += ui.button(tx.t("Edit", "Edit"), ButtonKind.TEXT) { showEditor(c) }
        if (kind != SourceKind.PLUGIN) {
            actions += ui.button(tx.t("Hapus", "Delete"), ButtonKind.DANGER) {
                confirm(
                    tx.t("Hapus konektor?", "Delete connector?"),
                    tx.t("Konektor '${c.name}' akan dihapus.", "Connector '${c.name}' will be removed."),
                    tx.t("Hapus", "Delete")
                ) {
                    McpClientHub.removeConnector(c.id)
                    expanded.remove(c.id)
                    refresh()
                }
            }
        }
        val detail = c.lastStatus.lineSequence().firstOrNull()?.trim().orEmpty()
        return ui.capabilityCard(
            name = c.name,
            iconRes = R.drawable.ic_mtrl_link,
            description = if (st == McpState.ERROR) detail else "",
            sourceLabel = label,
            enabled = c.enabled,
            onToggle = { on ->
                McpClientHub.setConnectorEnabled(c.id, on)
                refresh()
            },
            statusColor = dot,
            statusText = statusText,
            chips = chips,
            monoLine = if (c.transport == "stdio") c.command else c.url,
            actions = actions,
            extra = if (isOpen) toolList(c) else null
        )
    }

    private fun toolList(c: McpConnectorConfig): View =
        ui.column().apply {
            setPadding(0, ui.dp(10), 0, 0)
            c.cachedTools.forEach { tool ->
                addView(ui.column().apply {
                    background = ui.rounded(p.secondary, 8)
                    setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8))
                    layoutParams = ui.lpMargin(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, b = 6)
                    addView(ui.text(tool.name, 12.5f, p.foreground, bold = true, mono = true, maxLines = 1))
                    if (tool.description.isNotBlank()) addView(ui.text(tool.description, 12f, p.mutedForeground, maxLines = 2))
                })
            }
        }

    // ---------- actions ----------

    private fun syncOne(c: McpConnectorConfig) {
        toast(tx.t("Menyinkronkan '${c.name}'…", "Syncing '${c.name}'…"))
        runInBackground({ McpClientHub.syncConnectorTools(c.id) }) { result ->
            val msg = result.getOrElse { it.message ?: "Error" }
            toast(msg.lines().firstOrNull() ?: msg)
            refresh()
        }
    }

    private fun syncAll() {
        val enabled = McpClientHub.listConnectors().filter { it.enabled }
        if (enabled.isEmpty()) {
            toast(tx.t("Tidak ada konektor aktif.", "No enabled connectors."))
            return
        }
        toast(tx.t("Menyinkronkan ${enabled.size} konektor…", "Syncing ${enabled.size} connectors…"))
        runInBackground({ enabled.forEach { McpClientHub.syncConnectorTools(it.id) } }) {
            toast(tx.t("Sinkronisasi selesai.", "Sync finished."))
            refresh()
        }
    }

    private fun showEditor(existing: McpConnectorConfig?) {
        var transport = existing?.transport ?: "http"
        val name = ui.field(tx.t("Nama konektor", "Connector name"), existing?.name.orEmpty())
        if (existing != null) name.edit.isEnabled = false
        val target = ui.field("", existing?.let { if (it.transport == "stdio") it.command else it.url }.orEmpty(), mono = true)
        val token = ui.field(
            if (existing != null && existing.headers.isNotEmpty()) tx.t("Token Authorization (kosongkan untuk mempertahankan)", "Authorization token (leave empty to keep)")
            else tx.t("Token Authorization (opsional)", "Authorization token (optional)"),
            password = true
        )
        val inLinux = ui.switchView(existing?.inLinux ?: true) {}
        val inLinuxRow = ui.row().apply {
            setPadding(0, 0, 0, ui.dp(10))
            addView(ui.text(tx.t("Jalankan di dalam Linux (proot)", "Run inside Linux (proot)"), 13.5f, p.foreground), ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(inLinux)
        }
        val transportRow = ui.row().apply { setPadding(0, 0, 0, ui.dp(12)) }

        fun applyTransport() {
            target.layout.hint = if (transport == "http") tx.t("URL server MCP (https://…)", "MCP server URL (https://…)")
            else tx.t("Perintah (mis. npx -y @modelcontextprotocol/server-filesystem /sdcard)", "Command (e.g. npx -y @modelcontextprotocol/server-filesystem /sdcard)")
            token.layout.visibility = if (transport == "http") View.VISIBLE else View.GONE
            inLinuxRow.visibility = if (transport == "stdio") View.VISIBLE else View.GONE
            transportRow.removeAllViews()
            listOf("http" to "HTTP", "stdio" to "STDIO").forEach { (key, label) ->
                transportRow.addView(
                    ui.chip(label, transport == key) {
                        transport = key
                        applyTransport()
                    },
                    ui.lpMargin(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, r = 8)
                )
            }
        }
        applyTransport()

        formDialog(
            if (existing == null) tx.t("Konektor baru", "New connector") else tx.t("Edit konektor", "Edit connector"),
            formBox(name.layout, transportRow, target.layout, token.layout, inLinuxRow),
            tx.t("Simpan", "Save")
        ) {
            val n = name.edit.text?.toString()?.trim().orEmpty()
            val t = target.edit.text?.toString()?.trim().orEmpty()
            val tok = token.edit.text?.toString()?.trim().orEmpty()
            if (n.isEmpty() || t.isEmpty()) {
                toast(tx.t("Nama dan URL/perintah wajib diisi.", "Name and URL/command are required."))
                return@formDialog false
            }
            if (transport == "http" && !(t.startsWith("http://") || t.startsWith("https://"))) {
                toast(tx.t("URL harus diawali http:// atau https://", "The URL must start with http:// or https://"))
                return@formDialog false
            }
            val headers = when {
                transport != "http" -> emptyMap()
                tok.isNotEmpty() -> mapOf("Authorization" to if (tok.startsWith("Bearer ", ignoreCase = true)) tok else "Bearer $tok")
                else -> existing?.headers.orEmpty()
            }
            val config = (existing ?: McpConnectorConfig(id = SkillManager.sanitizeId(n), name = n, transport = transport)).copy(
                transport = transport,
                url = if (transport == "http") t else "",
                command = if (transport == "stdio") t else "",
                inLinux = inLinux.isChecked,
                headers = headers,
                enabled = existing?.enabled ?: true
            )
            val saved = McpClientHub.addOrUpdateConnector(config)
            refresh()
            syncOne(saved)
            true
        }
    }
}
