package pro.sketchware.ai.ui

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import pro.sketchware.ai.provider.LlmClient
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import pro.sketchware.R
import pro.sketchware.ai.config.AgentLanguage
import pro.sketchware.ai.config.AiConfigManager
import pro.sketchware.ai.config.AiProviderType
import pro.sketchware.ai.config.AiStrings
import pro.sketchware.utility.SketchwareUtil
import com.google.android.material.R as MR

/**
 * Dedicated full-screen DeerFlow 2.0 Studio for API Key Vault, LLM Provider Selection,
 * Model Presets, Saved Profiles, and Agent Execution Parameters.
 *
 * Separated completely from MCP Connectors, Plugins, and Skills views.
 */
object AiSettingsDialog {

    @JvmStatic
    fun show(context: Context, onSaved: (() -> Unit)? = null) {
        val config = AiConfigManager(context)
        val isDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val p = DeerFlowTheme.palette(isDark)

        fun dp(v: Float) = SketchwareUtil.dpToPx(v)
        fun rounded(color: Int, radiusDp: Float, stroke: Int? = null) = GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(color)
            if (stroke != null) setStroke(maxOf(1, dp(1f)), stroke)
        }

        val dialog = Dialog(context, android.R.style.Theme_DeviceDefault_NoActionBar)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.background)
        }

        // ------------------------------------------------------------------
        // Top App Bar (DeerFlow Studio Header)
        // ------------------------------------------------------------------
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(p.sidebar)
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        }

        val btnBack = TextView(context).apply {
            text = "←"
            textSize = 18f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(p.foreground)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(40f), dp(40f))
            background = rounded(p.card, 12f, p.border)
            isClickable = true
            setOnClickListener { dialog.dismiss() }
        }
        header.addView(btnBack)

        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12f)
            }
        }
        val tvHeaderTitle = TextView(context).apply {
            text = "🔑 API Key & Model Studio"
            textSize = 16.5f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(p.foreground)
        }
        val tvHeaderSubtitle = TextView(context).apply {
            text = "Konfigurasi Provider LLM, Brankas API Key, Model Preset & Otonomi Agent"
            textSize = 11.5f
            setTextColor(p.mutedForeground)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        titleCol.addView(tvHeaderTitle)
        titleCol.addView(tvHeaderSubtitle)
        header.addView(titleCol)

        val btnSaveHeader = MaterialButton(context).apply {
            text = "Simpan"
            textSize = 12f
            isAllCaps = false
            backgroundTintList = ColorStateList.valueOf(p.ring)
            setTextColor(Color.WHITE)
            cornerRadius = dp(10f)
            minHeight = dp(38f)
        }
        header.addView(btnSaveHeader)
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(View(context).apply { setBackgroundColor(p.border) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(14f), dp(16f), dp(28f))
        }
        scroll.addView(content)
        root.addView(scroll)

        var selectedProvider = config.providerType
        var ui = AiStrings.current(context)

        fun sectionCard(): LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(p.card, 14f, p.border)
            setPadding(dp(14f), dp(12f), dp(14f), dp(14f))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12f)
            }
        }

        fun sectionHeader(badge: String, title: String, desc: String): View = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(8f))
            val top = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            top.addView(TextView(context).apply {
                text = badge
                textSize = 10f
                typeface = Typeface.MONOSPACE
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(p.ring)
                background = rounded(ColorUtils.setAlphaComponent(p.ring, 28), 6f)
                setPadding(dp(7f), dp(2f), dp(7f), dp(2f))
            })
            top.addView(TextView(context).apply {
                text = title
                textSize = 14f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(p.foreground)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(8f)
                }
            })
            addView(top)
            addView(TextView(context).apply {
                text = desc
                textSize = 11.5f
                setTextColor(p.mutedForeground)
                setPadding(0, dp(3f), 0, 0)
            })
        }

        // ==================================================================
        // CARD 1: Provider Selection Cards
        // ==================================================================
        val providerCard = sectionCard()
        val tvProviderHead = sectionHeader(
            "PROVIDER",
            ui.settingsProviderLabel,
            "Pilih penyedia layanan LLM aktif. Setiap provider menyimpan API Key, Model ID, dan Base URL secara terpisah."
        )
        providerCard.addView(tvProviderHead)

        val providerScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
        }
        val providerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4f), 0, dp(2f))
        }
        providerScroll.addView(providerRow)
        providerCard.addView(providerScroll)
        content.addView(providerCard)

        // ==================================================================
        // CARD 2: API Key Vault & Model Configuration
        // ==================================================================
        val credentialsCard = sectionCard()
        credentialsCard.addView(
            sectionHeader(
                "CREDENTIALS & MODEL",
                "Brankas API Key & Model Aktif",
                "Masukkan API Key dan pilih atau simpan Model ID kustom untuk provider yang dipilih."
            )
        )

        val tvKeyStatus = TextView(context).apply {
            textSize = 12f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8f)
            }
        }
        credentialsCard.addView(tvKeyStatus)

        val tilApiKey = TextInputLayout(context).apply {
            hint = "API Key"
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        val etApiKey = TextInputEditText(tilApiKey.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setTextColor(p.foreground)
        }
        tilApiKey.addView(etApiKey)
        credentialsCard.addView(tilApiKey, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val tvKeyHint = TextView(context).apply {
            textSize = 11.5f
            setTextColor(p.warning)
            visibility = View.GONE
            setPadding(dp(4f), dp(4f), dp(4f), 0)
        }
        credentialsCard.addView(tvKeyHint)

        val tilModel = TextInputLayout(context).apply {
            hint = "Model ID (contoh: gemini-2.5-pro, gpt-4.1, claude-sonnet-4-20250514)"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        val etModel = TextInputEditText(tilModel.context).apply {
            setSingleLine(true)
            setTextColor(p.foreground)
        }
        tilModel.addView(etModel)
        credentialsCard.addView(
            tilModel,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10f)
            }
        )

        val modelHeaderRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8f), 0, dp(4f))
        }
        val tvModelPresetLabel = TextView(context).apply {
            text = "Preset & Model Kustom (ketuk untuk memilih, × untuk hapus):"
            textSize = 11.5f
            setTextColor(p.mutedForeground)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnAddModel = TextView(context).apply {
            text = "+ Simpan Model"
            textSize = 11.5f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(p.ring)
            background = rounded(ColorUtils.setAlphaComponent(p.ring, 24), 8f, p.ring)
            setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
            isClickable = true
        }
        modelHeaderRow.addView(tvModelPresetLabel)
        modelHeaderRow.addView(btnAddModel)
        credentialsCard.addView(modelHeaderRow)

        val modelPresetGroup = ChipGroup(context).apply {
            isSingleSelection = false
        }
        credentialsCard.addView(modelPresetGroup, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val tilBaseUrl = TextInputLayout(context).apply {
            hint = "Base URL Endpoint"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        val etBaseUrl = TextInputEditText(tilBaseUrl.context).apply {
            setSingleLine(true)
            setTextColor(p.foreground)
        }
        tilBaseUrl.addView(etBaseUrl)
        credentialsCard.addView(
            tilBaseUrl,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10f)
            }
        )

        val tvEndpoint = TextView(context).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(p.mutedForeground)
            setPadding(dp(4f), dp(4f), dp(4f), 0)
        }
        credentialsCard.addView(tvEndpoint)

        val diagnostics = ExtText(config.agentLanguage)
        val llmProbe by lazy { LlmClient() }

        fun updateKeyHint() {
            val hint = ApiKeyHints.check(selectedProvider, etApiKey.text?.toString().orEmpty())
            if (hint == null) {
                tvKeyHint.visibility = View.GONE
            } else {
                tvKeyHint.text = "⚠ " + diagnostics.keyHint(hint)
                tvKeyHint.visibility = View.VISIBLE
            }
        }

        fun updateEndpointPreview() {
            val url = etBaseUrl.text?.toString().orEmpty().trim().ifEmpty { selectedProvider.defaultBaseUrl }
            val openAiStyle = selectedProvider == AiProviderType.OPENAI ||
                selectedProvider == AiProviderType.OPENROUTER || selectedProvider == AiProviderType.CUSTOM
            tvEndpoint.text = if (!openAiStyle) {
                "Endpoint: ${url.trimEnd('/')}"
            } else {
                val base = llmProbe.normalizeOpenAiBaseUrl(url)
                "Endpoint: " + if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
            }
        }

        fun watcher(onChange: () -> Unit) = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = onChange()
        }
        etApiKey.addTextChangedListener(watcher { updateKeyHint() })
        etBaseUrl.addTextChangedListener(watcher { updateEndpointPreview() })

        val keyActionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val btnSaveKeyModel = MaterialButton(context, null, MR.attr.materialButtonOutlinedStyle).apply {
            text = ui.settingsSaveKeyBtn
            textSize = 11.5f
            isAllCaps = false
            minHeight = dp(40f)
            setTextColor(p.ring)
            strokeColor = ColorStateList.valueOf(p.ring)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6f)
            }
        }
        val btnDeleteKey = MaterialButton(context, null, MR.attr.materialButtonOutlinedStyle).apply {
            text = ui.settingsDeleteKeyBtn
            textSize = 11.5f
            isAllCaps = false
            minHeight = dp(40f)
            setTextColor(p.destructive)
            strokeColor = ColorStateList.valueOf(p.destructive)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6f)
            }
        }
        keyActionRow.addView(btnSaveKeyModel)
        keyActionRow.addView(btnDeleteKey)
        credentialsCard.addView(
            keyActionRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12f)
            }
        )

        val btnTestConnection = MaterialButton(context, null, MR.attr.materialButtonOutlinedStyle).apply {
            text = diagnostics.t("Uji Koneksi", "Test connection")
            textSize = 11.5f
            isAllCaps = false
            minHeight = dp(40f)
            setTextColor(p.foreground)
            strokeColor = ColorStateList.valueOf(p.border)
        }
        val tvTestResult = TextView(context).apply {
            textSize = 12f
            visibility = View.GONE
            setPadding(dp(4f), dp(8f), dp(4f), 0)
        }
        credentialsCard.addView(
            btnTestConnection,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8f)
            }
        )
        credentialsCard.addView(tvTestResult)

        btnTestConnection.setOnClickListener {
            val provider = selectedProvider
            val key = etApiKey.text?.toString()?.trim().orEmpty()
            val model = etModel.text?.toString()?.trim().orEmpty()
            val url = etBaseUrl.text?.toString()?.trim().orEmpty()
            tvTestResult.visibility = View.VISIBLE
            if (key.isEmpty() && provider != AiProviderType.CUSTOM) {
                tvTestResult.setTextColor(p.warning)
                tvTestResult.text = diagnostics.t("Isi API Key dulu sebelum menguji.", "Enter an API key before testing.")
                return@setOnClickListener
            }
            btnTestConnection.isEnabled = false
            tvTestResult.setTextColor(p.mutedForeground)
            tvTestResult.text = diagnostics.t("Menguji koneksi…", "Testing connection…")
            val mainHandler = Handler(Looper.getMainLooper())
            Thread {
                val started = System.nanoTime()
                val outcome = runCatching { llmProbe.ping(provider, key, url, model) }
                val ms = (System.nanoTime() - started) / 1_000_000
                mainHandler.post {
                    btnTestConnection.isEnabled = true
                    outcome.fold(
                        onSuccess = { r ->
                            val reply = r.text.trim().replace("\n", " ").take(60)
                            tvTestResult.setTextColor(p.success)
                            tvTestResult.text = diagnostics.t("✓ Terhubung (${ms} ms). Balasan: \"$reply\"", "✓ Connected (${ms} ms). Reply: \"$reply\"")
                        },
                        onFailure = { e ->
                            tvTestResult.setTextColor(p.destructive)
                            tvTestResult.text = "✗ " + (e.message ?: e.javaClass.simpleName).take(300)
                        }
                    )
                }
            }.start()
        }
        content.addView(credentialsCard)

        // ==================================================================
        // CARD 3: Saved API Key & Model Profiles Switcher
        // ==================================================================
        val profilesCard = sectionCard()
        val savedHeaderRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tvSavedTitle = TextView(context).apply {
            text = "🗂️ ${ui.settingsSavedProfilesTitle}"
            textSize = 14f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(p.foreground)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnClearAllKeys = TextView(context).apply {
            text = "Hapus Semua"
            textSize = 11.5f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(p.destructive)
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
            isClickable = true
        }
        savedHeaderRow.addView(tvSavedTitle)
        savedHeaderRow.addView(btnClearAllKeys)
        profilesCard.addView(savedHeaderRow)

        profilesCard.addView(TextView(context).apply {
            text = "Ketuk salah satu profil di bawah untuk langsung beralih provider, API key, dan model dengan 1 klik."
            textSize = 11.5f
            setTextColor(p.mutedForeground)
            setPadding(0, dp(2f), 0, dp(8f))
        })

        val savedListContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        profilesCard.addView(savedListContainer)
        content.addView(profilesCard)

        // ==================================================================
        // CARD 4: Agent Execution, Long-Horizon & Language Preferences
        // ==================================================================
        val executionCard = sectionCard()
        executionCard.addView(
            sectionHeader(
                "AGENT HARNESS",
                "Parameter Eksekusi & Bahasa Agent",
                "Atur batas langkah otonom (Long-Horizon), auto-compile self-healing, checkpoint, dan bahasa respons."
            )
        )

        val langLabel = TextView(context).apply {
            text = ui.settingsLanguageLabel
            textSize = 12.5f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(p.foreground)
            setPadding(0, dp(2f), 0, dp(4f))
        }
        executionCard.addView(langLabel)

        val btnSelectLanguage = MaterialButton(context, null, MR.attr.materialButtonOutlinedStyle).apply {
            val activeLang = config.agentLanguage
            text = if (activeLang == AgentLanguage.AUTO) {
                "${activeLang.displayLabel} → ${activeLang.resolved().displayLabel}"
            } else {
                activeLang.displayLabel
            }
            textSize = 12f
            isAllCaps = false
            minHeight = dp(40f)
            setTextColor(p.foreground)
            strokeColor = ColorStateList.valueOf(p.border)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }
        executionCard.addView(
            btnSelectLanguage,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8f)
            }
        )

        val cbUnlimitedSteps = MaterialCheckBox(context).apply {
            text = "♾️ Langkah Long-Horizon Tidak Terbatas (Unlimited Steps — Agent bekerja otonom hingga tuntas)"
            isChecked = config.unlimitedSteps
            setTextColor(p.foreground)
        }
        executionCard.addView(cbUnlimitedSteps)

        val tilMaxSteps = TextInputLayout(context).apply {
            hint = "Maksimum Langkah Manual bila Unlimited dimatikan (5 - 1000)"
            isEnabled = !config.unlimitedSteps
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        val etMaxSteps = TextInputEditText(tilMaxSteps.context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setText(config.maxStepsPerRun.toString())
            setTextColor(p.foreground)
        }
        tilMaxSteps.addView(etMaxSteps)
        cbUnlimitedSteps.setOnCheckedChangeListener { _, checked ->
            tilMaxSteps.isEnabled = !checked
        }
        executionCard.addView(
            tilMaxSteps,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4f)
                bottomMargin = dp(4f)
            }
        )

        val cbAutoBuild = MaterialCheckBox(context).apply {
            text = "Otomatis Compile & Self-Heal setelah Agent menulis kode/blok"
            isChecked = config.autoBuildAndHeal
            setTextColor(p.foreground)
        }
        executionCard.addView(cbAutoBuild)

        val cbAutoBackup = MaterialCheckBox(context).apply {
            text = "Otomatis buat Snapshot Checkpoint sebelum Agent memodifikasi proyek"
            isChecked = config.autoBackupBeforeRun
            setTextColor(p.foreground)
        }
        executionCard.addView(cbAutoBackup)
        content.addView(executionCard)

        // ==================================================================
        // Helper functions
        // ==================================================================
        fun defaultPresetsFor(provider: AiProviderType): List<String> = when (provider) {
            AiProviderType.GEMINI -> listOf("gemini-2.5-pro", "gemini-2.5-flash", "gemini-2.0-flash")
            AiProviderType.OPENAI -> listOf("gpt-4.1", "gpt-4.1-mini", "o4-mini")
            AiProviderType.OPENROUTER -> listOf(
                "anthropic/claude-sonnet-4",
                "google/gemini-2.5-pro-preview",
                "deepseek/deepseek-chat-v3-0324"
            )
            AiProviderType.CLAUDE -> listOf("claude-sonnet-4-20250514", "claude-3-7-sonnet-latest")
            AiProviderType.CUSTOM -> listOf("glm-5.3-flash", "deepseek-chat", "qwen2.5-coder:32b", "llama-3.3-70b-versatile")
        }

        fun providerDescription(provider: AiProviderType): String = when (provider) {
            AiProviderType.GEMINI -> "Google AI Studio · Gemini 2.5"
            AiProviderType.OPENAI -> "OpenAI Platform · GPT-4.1 / o4"
            AiProviderType.OPENROUTER -> "OpenRouter · Multi-Model Hub"
            AiProviderType.CLAUDE -> "Anthropic · Claude 4 / 3.7"
            AiProviderType.CUSTOM -> "OpenAI-Compatible Endpoint"
        }

        fun updateKeyStatusLabel(provider: AiProviderType) {
            val savedKey = config.getApiKey(provider)
            if (savedKey.isNotEmpty()) {
                tvKeyStatus.text = "✓ API Key ${provider.name} aktif (${AiConfigManager.maskApiKey(savedKey)})"
                tvKeyStatus.setTextColor(p.success)
                tvKeyStatus.background = rounded(ColorUtils.setAlphaComponent(p.success, 24), 8f, p.success)
            } else {
                tvKeyStatus.text = "⚠ Belum ada API Key tersimpan untuk ${provider.name}"
                tvKeyStatus.setTextColor(p.warning)
                tvKeyStatus.background = rounded(ColorUtils.setAlphaComponent(p.warning, 24), 8f, p.warning)
            }
        }

        fun renderModelChips(provider: AiProviderType) {
            modelPresetGroup.removeAllViews()
            val builtIn = defaultPresetsFor(provider)
            val customModels = config.getCustomModels(provider)
            val allModels = (customModels + builtIn).distinct()

            for (modelName in allModels) {
                val isCustom = customModels.any { it.equals(modelName, ignoreCase = true) } &&
                    builtIn.none { it.equals(modelName, ignoreCase = true) }
                val chip = Chip(context).apply {
                    text = modelName
                    isCloseIconVisible = isCustom
                    setOnClickListener {
                        etModel.setText(modelName)
                    }
                    if (isCustom) {
                        setOnCloseIconClickListener {
                            config.removeCustomModel(modelName, provider)
                            renderModelChips(provider)
                            SketchwareUtil.toast("Model '$modelName' dihapus dari daftar")
                        }
                    }
                }
                modelPresetGroup.addView(chip)
            }
        }

        lateinit var renderProviderCards: () -> Unit
        lateinit var renderSavedProfiles: () -> Unit

        fun populateProviderFields(provider: AiProviderType) {
            selectedProvider = provider
            etApiKey.setText(config.getApiKey(provider))
            etModel.setText(config.getModelId(provider))
            etBaseUrl.setText(config.getBaseUrl(provider))
            tvTestResult.visibility = View.GONE
            updateKeyHint()
            updateEndpointPreview()
            updateKeyStatusLabel(provider)
            renderModelChips(provider)
            renderProviderCards()
        }

        renderProviderCards = {
            providerRow.removeAllViews()
            for (prov in AiProviderType.entries) {
                val active = (prov == selectedProvider)
                val hasKey = config.getApiKey(prov).isNotEmpty()
                val item = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = rounded(
                        if (active) ColorUtils.setAlphaComponent(p.ring, 28) else p.secondary,
                        12f,
                        if (active) p.ring else p.border
                    )
                    setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                    layoutParams = LinearLayout.LayoutParams(dp(148f), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginEnd = dp(8f)
                    }
                    isClickable = true
                    setOnClickListener {
                        config.setApiKey(etApiKey.text?.toString().orEmpty(), selectedProvider)
                        config.setModelId(etModel.text?.toString().orEmpty(), selectedProvider)
                        config.setBaseUrl(etBaseUrl.text?.toString().orEmpty(), selectedProvider)
                        populateProviderFields(prov)
                        renderSavedProfiles()
                    }
                }
                val top = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                top.addView(TextView(context).apply {
                    text = prov.name
                    textSize = 13f
                    setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(if (active) p.ring else p.foreground)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                top.addView(TextView(context).apply {
                    text = if (hasKey) "● KEY" else "○"
                    textSize = 9.5f
                    setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(if (hasKey) p.success else p.mutedForeground)
                })
                item.addView(top)
                item.addView(TextView(context).apply {
                    text = providerDescription(prov)
                    textSize = 10.5f
                    setTextColor(p.mutedForeground)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(3f), 0, 0)
                })
                providerRow.addView(item)
            }
        }

        renderSavedProfiles = {
            savedListContainer.removeAllViews()
            val profiles = config.getSavedProfiles()
            btnClearAllKeys.visibility = if (profiles.isEmpty()) View.GONE else View.VISIBLE
            if (profiles.isEmpty()) {
                savedListContainer.addView(TextView(context).apply {
                    text = "Belum ada profil API Key & Model yang disimpan. Isi API Key & Model di atas lalu ketuk '${ui.settingsSaveKeyBtn}'."
                    textSize = 11.5f
                    setTextColor(p.mutedForeground)
                })
            } else {
                for (profile in profiles) {
                    val isActive = (config.providerType == profile.provider &&
                        config.getApiKey(profile.provider) == profile.apiKey &&
                        config.getModelId(profile.provider).equals(profile.modelId, ignoreCase = true))

                    val row = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        background = rounded(
                            if (isActive) ColorUtils.setAlphaComponent(p.ring, 24) else p.secondary,
                            10f,
                            if (isActive) p.ring else p.border
                        )
                        setPadding(dp(12f), dp(10f), dp(10f), dp(10f))
                        isClickable = true
                        setOnClickListener {
                            config.providerType = profile.provider
                            config.setApiKey(profile.apiKey, profile.provider)
                            config.setModelId(profile.modelId, profile.provider)
                            config.setBaseUrl(profile.baseUrl, profile.provider)
                            populateProviderFields(profile.provider)
                            renderSavedProfiles()
                            onSaved?.invoke()
                            SketchwareUtil.toast("Mengaktifkan ${profile.provider.name} · ${profile.modelId}")
                        }
                    }

                    val infoCol = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    infoCol.addView(TextView(context).apply {
                        text = "${if (isActive) "✓ AKTIF · " else ""}${profile.provider.name} · ${profile.modelId}"
                        textSize = 12.5f
                        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                        setTextColor(if (isActive) p.ring else p.foreground)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    })
                    infoCol.addView(TextView(context).apply {
                        text = "Key: ${AiConfigManager.maskApiKey(profile.apiKey)} · ${profile.baseUrl}"
                        textSize = 11f
                        setTextColor(p.mutedForeground)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.MIDDLE
                    })
                    row.addView(infoCol)

                    val btnDelItem = ImageView(context).apply {
                        setImageResource(R.drawable.ic_mtrl_delete)
                        imageTintList = ColorStateList.valueOf(p.destructive)
                        setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
                        isClickable = true
                        contentDescription = "Hapus"
                        setOnClickListener {
                            config.deleteProfile(profile.id)
                            if (config.getApiKey(profile.provider) == profile.apiKey) {
                                config.clearApiKey(profile.provider)
                                if (selectedProvider == profile.provider) {
                                    etApiKey.setText("")
                                    updateKeyStatusLabel(selectedProvider)
                                }
                            }
                            renderProviderCards()
                            renderSavedProfiles()
                            onSaved?.invoke()
                            SketchwareUtil.toast("Profil '${profile.modelId}' dihapus")
                        }
                    }
                    row.addView(
                        btnDelItem,
                        LinearLayout.LayoutParams(dp(34f), dp(34f)).apply {
                            marginStart = dp(6f)
                        }
                    )

                    savedListContainer.addView(
                        row,
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            bottomMargin = dp(6f)
                        }
                    )
                }
            }
        }

        btnAddModel.setOnClickListener {
            val modelText = etModel.text?.toString()?.trim().orEmpty()
            if (modelText.isEmpty()) {
                SketchwareUtil.toastError("Ketik nama Model ID terlebih dahulu")
                return@setOnClickListener
            }
            config.addCustomModel(modelText, selectedProvider)
            config.setModelId(modelText, selectedProvider)
            renderModelChips(selectedProvider)
            SketchwareUtil.toast("Model '$modelText' disimpan ke daftar ${selectedProvider.name}")
        }

        btnSaveKeyModel.setOnClickListener {
            val keyText = etApiKey.text?.toString()?.trim().orEmpty()
            val modelText = etModel.text?.toString()?.trim().orEmpty().ifEmpty { selectedProvider.defaultModel }
            val urlText = etBaseUrl.text?.toString()?.trim().orEmpty().ifEmpty { selectedProvider.defaultBaseUrl }
            if (keyText.isEmpty() && selectedProvider != AiProviderType.CUSTOM) {
                SketchwareUtil.toastError("Masukkan API Key terlebih dahulu")
                return@setOnClickListener
            }
            config.providerType = selectedProvider
            config.setApiKey(keyText, selectedProvider)
            config.setModelId(modelText, selectedProvider)
            config.setBaseUrl(urlText, selectedProvider)
            config.saveProfile(selectedProvider, keyText, modelText, urlText)
            updateKeyStatusLabel(selectedProvider)
            renderModelChips(selectedProvider)
            renderProviderCards()
            renderSavedProfiles()
            onSaved?.invoke()
            SketchwareUtil.toast("API Key & Model ($modelText) disimpan!")
        }

        btnDeleteKey.setOnClickListener {
            val currentKey = etApiKey.text?.toString()?.trim().orEmpty()
                .ifEmpty { config.getApiKey(selectedProvider) }
            config.clearApiKey(selectedProvider)
            if (currentKey.isNotEmpty()) {
                config.getSavedProfiles()
                    .filter { it.provider == selectedProvider && it.apiKey == currentKey }
                    .forEach { config.deleteProfile(it.id) }
            }
            etApiKey.setText("")
            updateKeyStatusLabel(selectedProvider)
            renderProviderCards()
            renderSavedProfiles()
            onSaved?.invoke()
            SketchwareUtil.toast("API Key untuk ${selectedProvider.name} berhasil dihapus")
        }

        btnClearAllKeys.setOnClickListener {
            MaterialAlertDialogBuilder(context)
                .setTitle("Hapus Semua API Key?")
                .setMessage("Semua API Key dan daftar profil model yang tersimpan di perangkat ini akan dihapus.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Hapus Semua") { _, _ ->
                    config.clearAllApiKeys()
                    etApiKey.setText("")
                    updateKeyStatusLabel(selectedProvider)
                    renderProviderCards()
                    renderSavedProfiles()
                    onSaved?.invoke()
                    SketchwareUtil.toast("Semua API Key telah dihapus")
                }
                .show()
        }

        val initialKey = config.getApiKey(selectedProvider)
        if (initialKey.isNotEmpty() && config.getSavedProfiles().isEmpty()) {
            config.saveProfile(
                selectedProvider,
                initialKey,
                config.getModelId(selectedProvider),
                config.getBaseUrl(selectedProvider)
            )
        }

        populateProviderFields(selectedProvider)
        renderSavedProfiles()

        fun refreshLanguageLabels() {
            ui = AiStrings.current(context)
            val activeLang = config.agentLanguage
            btnSelectLanguage.text = if (activeLang == AgentLanguage.AUTO) {
                "${activeLang.displayLabel} → ${activeLang.resolved().displayLabel}"
            } else {
                activeLang.displayLabel
            }
            langLabel.text = ui.settingsLanguageLabel
            btnSaveKeyModel.text = ui.settingsSaveKeyBtn
            btnDeleteKey.text = ui.settingsDeleteKeyBtn
            tvSavedTitle.text = "🗂️ ${ui.settingsSavedProfilesTitle}"
        }
        refreshLanguageLabels()

        btnSelectLanguage.setOnClickListener {
            AiStrings.showLanguagePickerDialog(context) {
                refreshLanguageLabels()
                onSaved?.invoke()
            }
        }

        btnSaveHeader.setOnClickListener {
            val keyText = etApiKey.text?.toString()?.trim().orEmpty()
            val modelText = etModel.text?.toString()?.trim().orEmpty().ifEmpty { selectedProvider.defaultModel }
            val urlText = etBaseUrl.text?.toString()?.trim().orEmpty().ifEmpty { selectedProvider.defaultBaseUrl }
            config.providerType = selectedProvider
            config.setApiKey(keyText, selectedProvider)
            config.setModelId(modelText, selectedProvider)
            config.setBaseUrl(urlText, selectedProvider)
            if (keyText.isNotEmpty() || selectedProvider == AiProviderType.CUSTOM) {
                config.saveProfile(selectedProvider, keyText, modelText, urlText)
            }
            val maxSteps = etMaxSteps.text?.toString()?.toIntOrNull() ?: 50
            config.unlimitedSteps = cbUnlimitedSteps.isChecked
            config.maxStepsPerRun = maxSteps
            config.autoBuildAndHeal = cbAutoBuild.isChecked
            config.autoBackupBeforeRun = cbAutoBackup.isChecked
            SketchwareUtil.toast("✓ Pengaturan API Key & Model disimpan")
            onSaved?.invoke()
            dialog.dismiss()
        }

        dialog.setContentView(root)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(p.background))
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            statusBarColor = p.sidebar
            navigationBarColor = p.background
        }
        dialog.show()
    }
}
