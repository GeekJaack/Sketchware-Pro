package pro.sketchware.ai.ui

import android.app.Activity
import android.app.Dialog
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import pro.sketchware.R
import pro.sketchware.ai.config.AiConfigManager
import pro.sketchware.ai.config.AiProviderType
import pro.sketchware.ai.config.SavedAiProfile
import pro.sketchware.ai.provider.LlmClient
import pro.sketchware.utility.ThemeUtils
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.google.android.material.R as MR

/**
 * "API Key & Model" page. One screen, top to bottom: the active model, the provider picker, the
 * credentials form (key hints, model presets, optional base URL with an endpoint preview), save and
 * test-connection, saved profiles and the danger zone. Edits are kept as per-provider drafts and only
 * written when "Simpan & aktifkan" is pressed.
 */
internal class ModelSettingsPage(
    private val activity: Activity,
    private val onChanged: (() -> Unit)? = null,
    private val onClosed: (() -> Unit)? = null
) {
    private val config = AiConfigManager(activity)
    private val isDark = ColorUtils.calculateLuminance(ThemeUtils.getColor(activity, MR.attr.colorSurface)) < 0.5
    private val p = DeerFlowTheme.palette(isDark)
    private val ui = DeerUi(activity, p, isDark)
    private val x = ExtText(config.agentLanguage)
    private val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar)
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val llm by lazy { LlmClient() }

    private var provider: AiProviderType = config.providerType
    private val draftKey = HashMap<AiProviderType, String>()
    private val draftModel = HashMap<AiProviderType, String>()
    private val draftBase = HashMap<AiProviderType, String>()
    private var advancedOpen = false
    private var testState: Pair<Int, String>? = null

    private lateinit var content: LinearLayout
    private var keyField: UiField? = null
    private var modelField: UiField? = null
    private var baseField: UiField? = null

    fun show() {
        val activeKey = config.getApiKey(config.providerType)
        if (activeKey.isNotEmpty() && config.getSavedProfiles().isEmpty()) {
            config.saveProfile(config.providerType, activeKey, config.getModelId(config.providerType), config.getBaseUrl(config.providerType))
        }
        val root = buildRoot()
        applyFullScreen(dialog, root, p, isDark)
        dialog.setOnDismissListener {
            worker.shutdown()
            onClosed?.invoke()
        }
        dialog.show()
        render()
    }

    // ---------- skeleton ----------

    private fun buildRoot(): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.background)
        }
        root.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(16), ui.dp(12), ui.dp(8), ui.dp(4))
            addView(ui.iconTile(x.t("API Key", "API Key"), R.drawable.ic_mtrl_key, 40))
            addView(ui.column().apply {
                setPadding(ui.dp(12), 0, ui.dp(8), 0)
                addView(ui.text(x.t("API Key & Model", "API Key & Model"), 19f, p.foreground, bold = true))
                addView(ui.text(x.t("Pilih penyedia, tempel key, lalu uji koneksinya.", "Pick a provider, paste the key, then test the connection."), 12.5f, p.mutedForeground, maxLines = 2))
            }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ui.icon(R.drawable.ic_mtrl_close, p.foreground, 22).apply {
                setPadding(ui.dp(9), ui.dp(9), ui.dp(9), ui.dp(9))
                layoutParams = LinearLayout.LayoutParams(ui.dp(44), ui.dp(44))
                background = ui.ripple(null, 22)
                isClickable = true
                isFocusable = true
                contentDescription = x.t("Tutup", "Close")
                setOnClickListener { dialog.dismiss() }
            })
        })
        content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(32))
        }
        root.addView(ScrollView(activity).apply {
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            addView(content)
        }, ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    // ---------- drafts ----------

    private fun savedKey(pr: AiProviderType) = config.getApiKey(pr)
    private fun currentKey() = draftKey[provider] ?: savedKey(provider)
    private fun currentModel() = draftModel[provider] ?: config.getModelId(provider)
    private fun currentBase() = draftBase[provider] ?: config.getBaseUrl(provider)

    /** Copies what is typed in the visible fields into the drafts of the selected provider. */
    private fun captureDrafts() {
        keyField?.let { draftKey[provider] = it.edit.text?.toString().orEmpty().trim() }
        modelField?.let { draftModel[provider] = it.edit.text?.toString().orEmpty().trim() }
        baseField?.let { draftBase[provider] = it.edit.text?.toString().orEmpty().trim() }
    }

    // ---------- render ----------

    private fun render() {
        content.removeAllViews()
        keyField = null
        modelField = null
        baseField = null

        content.addView(activeCard())
        content.addView(ui.sectionLabel(x.t("Penyedia", "Provider")))
        AiProviderType.entries.forEach { content.addView(providerRow(it)) }

        content.addView(ui.sectionLabel(x.t("Kredensial", "Credentials")))
        content.addView(credentialsCard())

        content.addView(ui.row().apply {
            setPadding(0, ui.dp(2), 0, 0)
            addView(ui.button(x.t("Simpan & aktifkan", "Save & activate"), ButtonKind.PRIMARY, R.drawable.ic_mtrl_check) { save() }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ui.button(x.t("Uji koneksi", "Test connection"), ButtonKind.OUTLINE, R.drawable.ic_mtrl_sync) { testConnection() },
                ui.lpMargin(0, ViewGroup.LayoutParams.WRAP_CONTENT, l = 8, weight = 1f))
        })
        testState?.let { (color, message) ->
            content.addView(ui.text(message, 12.5f, color).apply { setPadding(ui.dp(2), ui.dp(10), ui.dp(2), 0) })
        }

        val profiles = config.getSavedProfiles()
        if (profiles.isNotEmpty()) {
            content.addView(ui.sectionLabel(x.t("Profil tersimpan", "Saved profiles")))
            profiles.forEach { content.addView(profileCard(it)) }
        }

        content.addView(ui.sectionLabel(x.t("Zona berbahaya", "Danger zone")))
        content.addView(ui.row().apply {
            addView(ui.button(x.t("Hapus key penyedia ini", "Delete this provider's key"), ButtonKind.DANGER) { deleteKey() })
            addView(ui.button(x.t("Hapus semua key", "Delete all keys"), ButtonKind.DANGER) { deleteAllKeys() }, ui.lpMargin(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, l = 8))
        })
    }

    private fun activeCard(): View {
        val active = config.providerType
        val key = config.getApiKey(active)
        val ready = key.isNotEmpty() || config.isConfigured()
        val card = ui.card()
        card.addView(ui.row().apply {
            addView(ui.column().apply {
                addView(ui.text(x.t("Model aktif", "Active model"), 11.5f, p.mutedForeground, bold = true))
                addView(ui.text(config.getModelId(active), 16f, p.foreground, bold = true, mono = true, maxLines = 1).apply { setPadding(0, ui.dp(2), 0, 0) })
                addView(ui.text(providerName(active) + if (key.isNotEmpty()) "  ·  " + AiConfigManager.maskApiKey(key) else "", 12.5f, p.mutedForeground, maxLines = 1).apply { setPadding(0, ui.dp(2), 0, 0) })
            }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ui.pill(if (ready) x.t("Siap", "Ready") else x.t("Belum diatur", "Not set"), if (ready) p.success else p.warning, ui.alpha(if (ready) p.success else p.warning, 40)))
        })
        return card
    }

    private fun providerName(pr: AiProviderType) = when (pr) {
        AiProviderType.GEMINI -> "Google Gemini"
        AiProviderType.OPENAI -> "OpenAI"
        AiProviderType.OPENROUTER -> "OpenRouter"
        AiProviderType.CLAUDE -> "Anthropic Claude"
        AiProviderType.CUSTOM -> x.t("Kustom (kompatibel OpenAI)", "Custom (OpenAI-compatible)")
    }

    private fun providerRow(pr: AiProviderType): View {
        val selected = pr == provider
        val hasKey = (draftKey[pr] ?: savedKey(pr)).isNotEmpty()
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
            background = ui.ripple(ui.rounded(if (selected) ui.alpha(p.ring, 28) else p.card, 12, if (selected) p.ring else p.border, if (selected) 2 else 1), 12)
            layoutParams = ui.lpMargin(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, b = 8)
            isClickable = true
            isFocusable = true
            addView(ui.statusDot(if (selected) p.ring else p.border, 12).apply {
                (layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, ui.dp(12), 0)
            })
            addView(ui.column().apply {
                addView(ui.text(providerName(pr), 14.5f, p.foreground, bold = true))
                addView(ui.text(pr.defaultModel, 11.5f, p.mutedForeground, mono = true, maxLines = 1).apply { setPadding(0, ui.dp(2), 0, 0) })
            }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (hasKey) addView(ui.pill(x.t("Key tersimpan", "Key saved"), p.success, ui.alpha(p.success, 36)))
            setOnClickListener {
                if (pr != provider) {
                    captureDrafts()
                    provider = pr
                    testState = null
                    render()
                }
            }
        }
    }

    private fun credentialsCard(): View {
        val card = ui.card()

        val key = ui.field("API Key", currentKey(), password = true, mono = true)
        keyField = key
        val hint = ui.text("", 12f, p.warning).apply { setPadding(ui.dp(2), 0, 0, ui.dp(10)) }
        val saved = savedKey(provider)
        fun updateHint() {
            val typed = key.edit.text?.toString().orEmpty().trim()
            val h = ApiKeyHints.check(provider, typed)
            when {
                h != null -> {
                    hint.text = x.keyHint(h)
                    hint.setTextColor(p.warning)
                    hint.visibility = View.VISIBLE
                }
                typed.isNotEmpty() && typed == saved -> {
                    hint.text = x.t("✓ Tersimpan: ", "✓ Saved: ") + AiConfigManager.maskApiKey(saved)
                    hint.setTextColor(p.success)
                    hint.visibility = View.VISIBLE
                }
                typed.isEmpty() && provider == AiProviderType.CUSTOM -> {
                    hint.text = x.t("Key opsional untuk server lokal (mis. Ollama).", "The key is optional for local servers (for example Ollama).")
                    hint.setTextColor(p.mutedForeground)
                    hint.visibility = View.VISIBLE
                }
                else -> hint.visibility = View.GONE
            }
        }
        key.edit.addTextChangedListener(simpleWatcher { updateHint() })
        card.addView(key.layout)
        card.addView(hint)
        updateHint()

        val model = ui.field(x.t("Model ID", "Model ID"), currentModel(), mono = true)
        modelField = model
        card.addView(model.layout)
        card.addView(modelChips(model))

        card.addView(ui.button(
            if (advancedOpen) x.t("Sembunyikan lanjutan", "Hide advanced") else x.t("Lanjutan: Base URL", "Advanced: Base URL"),
            ButtonKind.TEXT
        ) {
            captureDrafts()
            advancedOpen = !advancedOpen
            render()
        })
        if (advancedOpen) {
            val base = ui.field("Base URL", currentBase(), mono = true)
            baseField = base
            card.addView(base.layout)
            val preview = ui.text("", 11.5f, p.mutedForeground, mono = true).apply { setPadding(ui.dp(2), 0, 0, ui.dp(8)) }
            fun updatePreview() {
                val url = base.edit.text?.toString().orEmpty().trim().ifEmpty { provider.defaultBaseUrl }
                preview.text = endpointPreview(url)
            }
            base.edit.addTextChangedListener(simpleWatcher { updatePreview() })
            updatePreview()
            card.addView(preview)
            card.addView(ui.button(x.t("Kembalikan ke default", "Reset to default"), ButtonKind.TEXT) {
                base.edit.setText(provider.defaultBaseUrl)
            })
        }
        return card
    }

    private fun endpointPreview(baseUrl: String): String {
        val openAiStyle = provider == AiProviderType.OPENAI || provider == AiProviderType.OPENROUTER || provider == AiProviderType.CUSTOM
        if (!openAiStyle) return x.t("Endpoint: ", "Endpoint: ") + baseUrl.trimEnd('/')
        val base = llm.normalizeOpenAiBaseUrl(baseUrl)
        return "Endpoint: " + if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
    }

    private fun modelChips(target: UiField): View {
        val builtIn = presets(provider)
        val custom = config.getCustomModels(provider)
        val all = (custom + builtIn).distinct()
        val flow = ui.flow().apply { setPadding(0, 0, 0, ui.dp(8)) }
        for (name in all) {
            val isCustom = custom.any { it.equals(name, true) } && builtIn.none { it.equals(name, true) }
            val selected = target.edit.text?.toString().orEmpty().trim().equals(name, true)
            flow.addView(ui.chip(if (isCustom) "$name ✕" else name, selected) {
                target.edit.setText(name)
                captureDrafts()
                render()
            }.apply {
                if (isCustom) {
                    setOnLongClickListener {
                        config.removeCustomModel(name, provider)
                        Toast.makeText(activity, x.t("Model '$name' dihapus dari daftar.", "Model '$name' removed from the list."), Toast.LENGTH_SHORT).show()
                        captureDrafts()
                        render()
                        true
                    }
                }
            })
        }
        return ui.column().apply {
            addView(flow)
            if (custom.isNotEmpty()) addView(ui.text(x.t("Tekan lama model berlabel ✕ untuk menghapusnya.", "Long-press a model marked ✕ to remove it."), 11f, p.mutedForeground).apply { setPadding(ui.dp(2), 0, 0, ui.dp(6)) })
        }
    }

    private fun presets(pr: AiProviderType): List<String> = when (pr) {
        AiProviderType.GEMINI -> listOf("gemini-2.5-pro", "gemini-2.5-flash", "gemini-2.0-flash")
        AiProviderType.OPENAI -> listOf("gpt-5", "gpt-5-mini", "gpt-4.1", "gpt-4.1-mini", "o4-mini")
        AiProviderType.OPENROUTER -> listOf("anthropic/claude-sonnet-4", "google/gemini-2.5-pro-preview", "deepseek/deepseek-chat-v3-0324")
        AiProviderType.CLAUDE -> listOf("claude-sonnet-4-20250514", "claude-3-7-sonnet-latest")
        AiProviderType.CUSTOM -> listOf("deepseek-chat", "qwen2.5-coder:32b", "llama-3.3-70b-versatile", "glm-5.3-flash")
    }

    private fun profileCard(pr: SavedAiProfile): View {
        val active = pr.provider == config.providerType && pr.apiKey == config.getApiKey(pr.provider) &&
            pr.modelId.equals(config.getModelId(pr.provider), true)
        val card = ui.card()
        card.addView(ui.row().apply {
            addView(ui.column().apply {
                addView(ui.text(providerName(pr.provider), 14f, p.foreground, bold = true, maxLines = 1))
                addView(ui.text(pr.modelId + "  ·  " + AiConfigManager.maskApiKey(pr.apiKey), 12f, p.mutedForeground, mono = true, maxLines = 1).apply { setPadding(0, ui.dp(2), 0, 0) })
            }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (active) addView(ui.pill(x.t("Aktif", "Active"), p.success, ui.alpha(p.success, 36)))
        })
        card.addView(ui.row(Gravity.END or Gravity.CENTER_VERTICAL).apply {
            setPadding(0, ui.dp(8), 0, 0)
            if (!active) addView(ui.button(x.t("Pakai", "Use"), ButtonKind.TEXT) { activate(pr) })
            addView(ui.button(x.t("Hapus", "Delete"), ButtonKind.DANGER) {
                config.deleteProfile(pr.id)
                render()
            }, ui.lpMargin(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, l = 4))
        })
        return card
    }

    // ---------- actions ----------

    private fun activate(pr: SavedAiProfile) {
        config.providerType = pr.provider
        config.setApiKey(pr.apiKey, pr.provider)
        config.setModelId(pr.modelId, pr.provider)
        config.setBaseUrl(if (pr.baseUrl == pr.provider.defaultBaseUrl) "" else pr.baseUrl, pr.provider)
        provider = pr.provider
        draftKey.clear(); draftModel.clear(); draftBase.clear()
        testState = null
        toast(x.t("Profil diaktifkan: ${pr.modelId}", "Profile activated: ${pr.modelId}"))
        render()
        onChanged?.invoke()
    }

    private fun save() {
        captureDrafts()
        val key = currentKey().trim()
        val model = currentModel().trim().ifEmpty { provider.defaultModel }
        val base = currentBase().trim().ifEmpty { provider.defaultBaseUrl }
        if (key.isEmpty() && provider != AiProviderType.CUSTOM) {
            toast(x.t("API Key wajib diisi untuk ${providerName(provider)}.", "An API key is required for ${providerName(provider)}."))
            return
        }
        config.providerType = provider
        if (key.isEmpty()) config.clearApiKey(provider) else config.setApiKey(key, provider)
        config.setModelId(model, provider)
        config.setBaseUrl(if (base == provider.defaultBaseUrl) "" else base, provider)
        if (key.isNotEmpty()) config.saveProfile(provider, key, model, base)
        draftKey.remove(provider); draftModel.remove(provider); draftBase.remove(provider)
        toast(x.t("Tersimpan. Model aktif: $model", "Saved. Active model: $model"))
        render()
        onChanged?.invoke()
    }

    private fun testConnection() {
        captureDrafts()
        val key = currentKey().trim()
        val model = currentModel().trim()
        val base = currentBase().trim()
        val target = provider
        if (key.isEmpty() && target != AiProviderType.CUSTOM) {
            testState = p.warning to x.t("Isi API Key dulu sebelum menguji.", "Enter an API key before testing.")
            render()
            return
        }
        testState = p.mutedForeground to x.t("Menguji koneksi…", "Testing connection…")
        render()
        worker.execute {
            val started = System.nanoTime()
            val outcome = runCatching { llm.ping(target, key, base, model) }
            val ms = (System.nanoTime() - started) / 1_000_000
            activity.runOnUiThread {
                if (!dialog.isShowing) return@runOnUiThread
                testState = outcome.fold(
                    onSuccess = { r ->
                        val reply = r.text.trim().replace("\n", " ").take(60)
                        p.success to x.t("✓ Terhubung (${ms} ms). Balasan: \"$reply\"", "✓ Connected (${ms} ms). Reply: \"$reply\"")
                    },
                    onFailure = { e -> p.destructive to "✗ " + (e.message ?: e.javaClass.simpleName).take(300) }
                )
                render()
            }
        }
    }

    private fun deleteKey() {
        MaterialAlertDialogBuilder(activity)
            .setTitle(x.t("Hapus key?", "Delete key?"))
            .setMessage(x.t("API Key untuk ${providerName(provider)} akan dihapus dari perangkat ini.", "The API key for ${providerName(provider)} will be removed from this device."))
            .setNegativeButton(x.t("Batal", "Cancel"), null)
            .setPositiveButton(x.t("Hapus", "Delete")) { _, _ ->
                config.clearApiKey(provider)
                draftKey.remove(provider)
                testState = null
                render()
                onChanged?.invoke()
            }
            .show()
    }

    private fun deleteAllKeys() {
        MaterialAlertDialogBuilder(activity)
            .setTitle(x.t("Hapus semua key?", "Delete all keys?"))
            .setMessage(x.t("Semua API Key dan profil tersimpan akan dihapus dari perangkat ini.", "All API keys and saved profiles will be removed from this device."))
            .setNegativeButton(x.t("Batal", "Cancel"), null)
            .setPositiveButton(x.t("Hapus semua", "Delete all")) { _, _ ->
                config.clearAllApiKeys()
                draftKey.clear()
                testState = null
                render()
                onChanged?.invoke()
            }
            .show()
    }

    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()

    private fun simpleWatcher(onChange: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = onChange()
    }
}
