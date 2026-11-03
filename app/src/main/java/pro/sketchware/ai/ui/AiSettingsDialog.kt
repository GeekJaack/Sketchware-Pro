package pro.sketchware.ai.ui

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import pro.sketchware.R
import pro.sketchware.ai.config.AgentLanguage
import pro.sketchware.ai.config.AiConfigManager
import pro.sketchware.ai.config.AiStrings
import pro.sketchware.utility.SketchwareUtil
import pro.sketchware.utility.ThemeUtils
import com.google.android.material.R as MR

/**
 * "Pengaturan Agent": language, agent behaviour and the inbound MCP server. API keys and models now
 * live on their own page ([ModelSettingsPage]); Skills, Plugins and MCP connectors each have a
 * dedicated page too, reachable from here.
 */
object AiSettingsDialog {

    @JvmStatic
    fun show(context: Context, onSaved: (() -> Unit)? = null) {
        val config = AiConfigManager(context)
        val activity = context as? Activity
        val dp4 = SketchwareUtil.dpToPx(4f)
        val dp8 = SketchwareUtil.dpToPx(8f)
        val dp10 = SketchwareUtil.dpToPx(10f)
        val dp12 = SketchwareUtil.dpToPx(12f)
        val dp16 = SketchwareUtil.dpToPx(16f)

        val isDark = ColorUtils.calculateLuminance(ThemeUtils.getColor(context, MR.attr.colorSurface)) < 0.5
        val deer = DeerFlowTheme.palette(isDark)
        val kit = DeerUi(context, deer, isDark)
        val cOnSurface = ThemeUtils.getColor(context, MR.attr.colorOnSurface)

        val scroll = ScrollView(context).apply { isFillViewport = true }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp16, dp12, dp16, dp12)
        }
        scroll.addView(root)

        var strings = AiStrings.current(context)

        // 1. Language
        val langLabel = TextView(context).apply {
            text = strings.settingsLanguageLabel
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(cOnSurface)
            setPadding(0, 0, 0, dp4)
        }
        root.addView(langLabel)
        val btnSelectLanguage = MaterialButton(context, null, MR.attr.materialButtonOutlinedStyle).apply {
            textSize = 12f
            minHeight = SketchwareUtil.dpToPx(40f)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }
        root.addView(
            btnSelectLanguage,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp10 }
        )

        // 2. Model & API Key summary + shortcut to its own page
        val tx = ExtText(config.agentLanguage)
        val modelCard = kit.card()
        root.addView(modelCard)
        fun renderModelCard() {
            modelCard.removeAllViews()
            val active = config.providerType
            val key = config.getApiKey(active)
            val ready = config.isConfigured()
            modelCard.addView(kit.row().apply {
                addView(kit.column().apply {
                    addView(kit.text("API Key & Model", 12f, deer.mutedForeground, bold = true))
                    addView(kit.text(config.getModelId(active), 15f, deer.foreground, bold = true, mono = true, maxLines = 1).apply { setPadding(0, kit.dp(2), 0, 0) })
                    addView(kit.text(active.displayName + if (key.isNotEmpty()) "  ·  " + AiConfigManager.maskApiKey(key) else "", 12f, deer.mutedForeground, maxLines = 2).apply { setPadding(0, kit.dp(2), 0, 0) })
                }, kit.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val c = if (ready) deer.success else deer.warning
                addView(kit.pill(if (ready) tx.t("Siap", "Ready") else tx.t("Belum diatur", "Not set"), c, kit.alpha(c, 40)))
            })
            if (activity != null) {
                modelCard.addView(kit.button(tx.t("Kelola API Key & Model", "Manage API key & model"), ButtonKind.OUTLINE, R.drawable.ic_mtrl_key) {
                    ModelSettingsPage(activity, onChanged = { onSaved?.invoke() }, onClosed = { renderModelCard() }).show()
                }, kit.lpMargin(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, t = 10))
            }
        }
        renderModelCard()

        // 3. Dedicated pages for extensions
        if (activity != null) {
            root.addView(kit.sectionLabel(tx.t("Ekstensi", "Extensions")))
            root.addView(kit.row().apply {
                addView(kit.button("Skills", ButtonKind.OUTLINE, R.drawable.ic_mtrl_star) { SkillsPage(activity, null) { onSaved?.invoke() }.show() }, kit.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(kit.button("Plugin", ButtonKind.OUTLINE, R.drawable.ic_mtrl_puzzle) { PluginsPage(activity) { onSaved?.invoke() }.show() }, kit.lpMargin(0, ViewGroup.LayoutParams.WRAP_CONTENT, l = 8, weight = 1f))
                addView(kit.button("MCP", ButtonKind.OUTLINE, R.drawable.ic_mtrl_link) { McpPage(activity) { onSaved?.invoke() }.show() }, kit.lpMargin(0, ViewGroup.LayoutParams.WRAP_CONTENT, l = 8, weight = 1f))
            })
        }

        // 4. Agent execution
        root.addView(kit.sectionLabel(tx.t("Eksekusi agent", "Agent execution")))
        val cbUnlimitedSteps = MaterialCheckBox(context).apply {
            text = "♾️ Langkah Long-Horizon Tidak Terbatas (Unlimited Steps — Biarkan AI Agent Bekerja Otonom Hingga Selesai)"
            isChecked = config.unlimitedSteps
        }
        root.addView(cbUnlimitedSteps)

        val tilMaxSteps = TextInputLayout(context).apply {
            hint = "Maksimum Langkah Manual bila Unlimited dimatikan (5 - 1000)"
            isEnabled = !config.unlimitedSteps
        }
        val etMaxSteps = TextInputEditText(tilMaxSteps.context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setText(config.maxStepsPerRun.toString())
        }
        tilMaxSteps.addView(etMaxSteps)
        cbUnlimitedSteps.setOnCheckedChangeListener { _, checked -> tilMaxSteps.isEnabled = !checked }
        root.addView(
            tilMaxSteps,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp4 }
        )

        val cbAutoBuild = MaterialCheckBox(context).apply {
            text = "Otomatis Compile & Self-Heal setelah Agent menulis kode/blok"
            isChecked = config.autoBuildAndHeal
        }
        root.addView(cbAutoBuild)

        val cbAutoBackup = MaterialCheckBox(context).apply {
            text = "Otomatis buat Snapshot Checkpoint sebelum Agent memodifikasi proyek"
            isChecked = config.autoBackupBeforeRun
        }
        root.addView(cbAutoBackup)

        // 5. Inbound MCP server
        root.addView(kit.sectionLabel("Sketchware MCP & External Agent Bridge"))
        val lanIp = pro.sketchware.ai.mcp.SketchwareMcpServer.getLanIpAddress()
        val mcpRunning = pro.sketchware.ai.mcp.SketchwareMcpServer.isRunning()
        root.addView(TextView(context).apply {
            text = "Status: ${if (mcpRunning) "AKTIF" else "NONAKTIF"} | Port: ${config.mcpServerPort}\n" +
                "• ADB URL: http://127.0.0.1:${config.mcpServerPort}/mcp\n" +
                "• Wi-Fi LAN: http://$lanIp:${config.mcpServerPort}/mcp"
            textSize = 12f
            setTextColor(deer.mutedForeground)
            setPadding(0, 0, 0, dp8)
        })
        val cbMcpEnabled = MaterialCheckBox(context).apply {
            text = "Aktifkan Sketchware Live MCP Server"
            isChecked = config.mcpServerEnabled
        }
        root.addView(cbMcpEnabled)

        val tilMcpPort = TextInputLayout(context).apply {
            hint = "Port MCP Server (Default: 8765)"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        val etMcpPort = TextInputEditText(tilMcpPort.context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(config.mcpServerPort.toString())
            setSingleLine(true)
        }
        tilMcpPort.addView(etMcpPort)
        root.addView(
            tilMcpPort,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp8 }
        )

        fun refreshLanguageLabels() {
            strings = AiStrings.current(context)
            val activeLang = config.agentLanguage
            btnSelectLanguage.text = if (activeLang == AgentLanguage.AUTO) {
                "${activeLang.displayLabel} → ${activeLang.resolved().displayLabel}"
            } else {
                activeLang.displayLabel
            }
            langLabel.text = strings.settingsLanguageLabel
        }
        refreshLanguageLabels()

        btnSelectLanguage.setOnClickListener {
            AiStrings.showLanguagePickerDialog(context) {
                refreshLanguageLabels()
                onSaved?.invoke()
            }
        }

        // Keep the saved-profile list useful right after the first key is entered elsewhere.
        val initialKey = config.getApiKey(config.providerType)
        if (initialKey.isNotEmpty() && config.getSavedProfiles().isEmpty()) {
            config.saveProfile(config.providerType, initialKey, config.getModelId(config.providerType), config.getBaseUrl(config.providerType))
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(strings.settingsDialogTitle)
            .setIcon(R.drawable.ic_mtrl_settings)
            .setView(scroll)
            .setPositiveButton(strings.saveAndCloseText) { d, _ ->
                val maxSteps = etMaxSteps.text?.toString()?.toIntOrNull() ?: 50
                config.unlimitedSteps = cbUnlimitedSteps.isChecked
                config.maxStepsPerRun = maxSteps
                config.autoBuildAndHeal = cbAutoBuild.isChecked
                config.autoBackupBeforeRun = cbAutoBackup.isChecked
                config.mcpServerEnabled = cbMcpEnabled.isChecked
                config.mcpServerPort = etMcpPort.text?.toString()?.toIntOrNull() ?: 8765
                pro.sketchware.ai.mcp.SketchwareMcpServer.ensureStarted(context)
                SketchwareUtil.toast("✓ ${strings.settingsDialogTitle}")
                onSaved?.invoke()
                d.dismiss()
            }
            .setNegativeButton(strings.closeText, null)
            .show()
    }
}
