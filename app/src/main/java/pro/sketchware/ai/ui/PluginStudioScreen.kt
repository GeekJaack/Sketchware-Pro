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
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
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
import pro.sketchware.ai.extensions.AgentPlugin
import pro.sketchware.ai.extensions.PluginManager
import pro.sketchware.ai.extensions.PluginSlashCommand
import pro.sketchware.utility.SketchwareUtil
import java.io.File
import java.util.concurrent.Executors
import com.google.android.material.R as MR

/**
 * Dedicated full-screen DeerFlow 2.0 Studio exclusively for managing AI Agent Plugins
 * (`.sketchware/ai/plugins/<id>/plugin.json`).
 *
 * Separated completely from API Key Settings, MCP Connectors, and Skills views.
 */
object PluginStudioScreen {

    private val bgExecutor = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())

    @JvmStatic
    fun show(
        context: Context,
        onUsePrompt: ((String) -> Unit)? = null,
        onChanged: (() -> Unit)? = null
    ) {
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
        // Top App Bar
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
            setOnClickListener {
                onChanged?.invoke()
                dialog.dismiss()
            }
        }
        header.addView(btnBack)

        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12f)
            }
        }
        titleCol.addView(TextView(context).apply {
            text = "🧩 Plugins Studio (plugin.json)"
            textSize = 16.5f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(p.foreground)
        })
        titleCol.addView(TextView(context).apply {
            text = "Kelola Bundel Plugin, Slash Commands, Domain Rules & Paket Ekstensi"
            textSize = 11.5f
            setTextColor(p.mutedForeground)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        header.addView(titleCol)

        val btnInstallHeader = MaterialButton(context).apply {
            text = "+ Pasang"
            textSize = 11.5f
            isAllCaps = false
            backgroundTintList = ColorStateList.valueOf(p.ring)
            setTextColor(Color.WHITE)
            cornerRadius = dp(10f)
            minHeight = dp(36f)
        }
        header.addView(btnInstallHeader)
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

        fun smallBtn(label: String, tint: Int = p.ring, onClick: () -> Unit): MaterialButton {
            return MaterialButton(context, null, MR.attr.materialButtonOutlinedStyle).apply {
                text = label
                textSize = 11f
                isAllCaps = false
                minHeight = dp(34f)
                minimumHeight = dp(34f)
                setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
                setTextColor(tint)
                strokeColor = ColorStateList.valueOf(tint)
                setOnClickListener { onClick() }
            }
        }

        // Top overview & action bar card
        val topCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(p.card, 14f, p.border)
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12f)
            }
        }

        val tvStats = TextView(context).apply {
            textSize = 12.5f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(p.foreground)
        }
        topCard.addView(tvStats)

        topCard.addView(TextView(context).apply {
            text = "Setiap plugin adalah paket mandiri di `.sketchware/ai/plugins/<id>/plugin.json` yang dapat membundel Skills, Slash Commands, aturan System Prompt, dan konektor MCP."
            textSize = 11.5f
            setTextColor(p.mutedForeground)
            setPadding(0, dp(3f), 0, dp(8f))
        })

        val tilSearch = TextInputLayout(context).apply {
            hint = "Cari plugin berdasarkan nama, deskripsi, atau slash command…"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        val etSearch = TextInputEditText(tilSearch.context).apply {
            setSingleLine(true)
            setTextColor(p.foreground)
            textSize = 12.5f
        }
        tilSearch.addView(etSearch)
        topCard.addView(tilSearch)

        val actionBtnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            setPadding(0, dp(8f), 0, 0)
        }
        val btnInstallArchive = smallBtn("📦 Pasang dari .zip / Folder / URL") {}
        val btnCreateCustomPlugin = smallBtn("✨ Buat Plugin Baru") {}.apply {
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8f) }
        }
        actionBtnRow.addView(btnInstallArchive)
        actionBtnRow.addView(btnCreateCustomPlugin)
        topCard.addView(actionBtnRow)
        content.addView(topCard)

        val pluginsListContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(pluginsListContainer)

        lateinit var renderPlugins: () -> Unit

        fun showInstallPluginDialog() {
            val form = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16f), dp(12f), dp(16f), dp(8f))
            }
            val tilSource = TextInputLayout(context).apply {
                hint = "Path file .zip / folder plugin atau URL plugin.json"
            }
            val etSource = TextInputEditText(tilSource.context).apply { setSingleLine(true) }
            tilSource.addView(etSource)
            form.addView(tilSource)

            MaterialAlertDialogBuilder(context)
                .setTitle("Pasang Plugin dari File / URL")
                .setView(form)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Pasang") { _, _ ->
                    val src = etSource.text?.toString()?.trim().orEmpty()
                    if (src.isEmpty()) {
                        SketchwareUtil.toastError("Masukkan path atau URL terlebih dahulu")
                        return@setPositiveButton
                    }
                    bgExecutor.execute {
                        val msg = PluginManager.installPluginFromArchiveOrUrl(src)
                        mainHandler.post {
                            SketchwareUtil.toast(msg)
                            renderPlugins()
                            onChanged?.invoke()
                        }
                    }
                }
                .show()
        }

        fun showCreatePluginDialog(existing: AgentPlugin? = null) {
            val formScroll = ScrollView(context)
            val form = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16f), dp(12f), dp(16f), dp(8f))
            }
            formScroll.addView(form)

            val tilName = TextInputLayout(context).apply { hint = "Nama Plugin (contoh: TikTok UI & Media Kit)" }
            val etName = TextInputEditText(tilName.context).apply {
                setSingleLine(true)
                if (existing != null) setText(existing.name)
            }
            tilName.addView(etName)
            form.addView(tilName)

            val tilVersion = TextInputLayout(context).apply {
                hint = "Versi (contoh: 1.0.0)"
                setPadding(0, dp(8f), 0, 0)
            }
            val etVersion = TextInputEditText(tilVersion.context).apply {
                setSingleLine(true)
                setText(existing?.version ?: "1.0.0")
            }
            tilVersion.addView(etVersion)
            form.addView(tilVersion)

            val tilDesc = TextInputLayout(context).apply {
                hint = "Deskripsi fungsi plugin"
                setPadding(0, dp(8f), 0, 0)
            }
            val etDesc = TextInputEditText(tilDesc.context).apply {
                if (existing != null) setText(existing.description)
            }
            tilDesc.addView(etDesc)
            form.addView(tilDesc)

            val tilRules = TextInputLayout(context).apply {
                hint = "Aturan Domain / System Prompt Tambahan (systemPromptAppend)"
                setPadding(0, dp(8f), 0, 0)
            }
            val etRules = TextInputEditText(tilRules.context).apply {
                minLines = 3
                maxLines = 8
                if (existing != null) setText(existing.systemPromptAppend)
            }
            tilRules.addView(etRules)
            form.addView(tilRules)

            val firstCmd = existing?.slashCommands?.firstOrNull()
            val tilSlashCmd = TextInputLayout(context).apply {
                hint = "Opsional: Slash Command (contoh: /audit-ui)"
                setPadding(0, dp(8f), 0, 0)
            }
            val etSlashCmd = TextInputEditText(tilSlashCmd.context).apply {
                setSingleLine(true)
                if (firstCmd != null) setText(firstCmd.command)
            }
            tilSlashCmd.addView(etSlashCmd)
            form.addView(tilSlashCmd)

            val tilSlashPrompt = TextInputLayout(context).apply {
                hint = "Opsional: Prompt otomatis saat Slash Command dipilih"
                setPadding(0, dp(8f), 0, 0)
            }
            val etSlashPrompt = TextInputEditText(tilSlashPrompt.context).apply {
                minLines = 2
                maxLines = 5
                if (firstCmd != null) setText(firstCmd.prompt)
            }
            tilSlashPrompt.addView(etSlashPrompt)
            form.addView(tilSlashPrompt)

            MaterialAlertDialogBuilder(context)
                .setTitle(if (existing == null) "Buat Plugin Baru" else "Edit Plugin: ${existing.name}")
                .setView(formScroll)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Simpan Plugin") { _, _ ->
                    val name = etName.text?.toString()?.trim().orEmpty()
                    val ver = etVersion.text?.toString()?.trim().orEmpty().ifEmpty { "1.0.0" }
                    val desc = etDesc.text?.toString()?.trim().orEmpty()
                    val rules = etRules.text?.toString()?.trim().orEmpty()
                    val cmd = etSlashCmd.text?.toString()?.trim().orEmpty()
                    val cmdPrompt = etSlashPrompt.text?.toString()?.trim().orEmpty()

                    if (name.isEmpty() || desc.isEmpty()) {
                        SketchwareUtil.toastError("Nama dan deskripsi plugin wajib diisi")
                        return@setPositiveButton
                    }
                    val slashList = if (cmd.isNotEmpty() && cmdPrompt.isNotEmpty()) {
                        listOf(
                            PluginSlashCommand(
                                command = if (cmd.startsWith("/")) cmd else "/$cmd",
                                title = name,
                                subtitle = desc.take(80),
                                prompt = cmdPrompt
                            )
                        )
                    } else {
                        existing?.slashCommands ?: emptyList()
                    }
                    PluginManager.createOrUpdatePlugin(
                        id = existing?.id ?: name,
                        name = name,
                        version = ver,
                        description = desc,
                        enabled = existing?.enabled ?: true,
                        slashCommands = slashList,
                        systemPromptAppend = rules
                    )
                    SketchwareUtil.toast("Plugin '$name' berhasil disimpan!")
                    renderPlugins()
                    onChanged?.invoke()
                }
                .show()
        }

        fun showManifestViewerDialog(plugin: AgentPlugin) {
            val manifestFile = File(plugin.dirPath, "plugin.json")
            val jsonText = if (manifestFile.isFile) manifestFile.readText() else "(plugin.json tidak ditemukan)"
            val tv = TextView(context).apply {
                text = jsonText
                textSize = 11.5f
                typeface = Typeface.MONOSPACE
                setTextColor(p.foreground)
                setTextIsSelectable(true)
                setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
            }
            val s = ScrollView(context).apply { addView(tv) }
            MaterialAlertDialogBuilder(context)
                .setTitle("📄 ${plugin.id}/plugin.json")
                .setView(s)
                .setNeutralButton("Edit Plugin") { _, _ -> showCreatePluginDialog(plugin) }
                .setPositiveButton("Tutup", null)
                .show()
        }

        renderPlugins = {
            pluginsListContainer.removeAllViews()
            val allPlugins = PluginManager.listPlugins()
            val enabledCount = allPlugins.count { it.enabled }
            tvStats.text = "Total Plugin Terpasang: ${allPlugins.size}  ·  Aktif: $enabledCount"

            val q = etSearch.text?.toString()?.trim()?.lowercase().orEmpty()
            val filtered = if (q.isEmpty()) {
                allPlugins
            } else {
                allPlugins.filter { plug ->
                    plug.name.lowercase().contains(q) ||
                        plug.id.lowercase().contains(q) ||
                        plug.description.lowercase().contains(q) ||
                        plug.slashCommands.any { it.command.lowercase().contains(q) }
                }
            }

            if (filtered.isEmpty()) {
                pluginsListContainer.addView(TextView(context).apply {
                    text = "Tidak ada plugin yang cocok dengan pencarian '$q'."
                    textSize = 12f
                    setTextColor(p.mutedForeground)
                    setPadding(dp(8f), dp(12f), dp(8f), dp(12f))
                })
            }

            for (plug in filtered) {
                val card = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = rounded(
                        if (plug.enabled) p.card else p.secondary,
                        14f,
                        if (plug.enabled) p.ring else p.border
                    )
                    setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(10f)
                    }
                }

                val topRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val cb = MaterialCheckBox(context).apply {
                    isChecked = plug.enabled
                    text = plug.name
                    textSize = 14f
                    setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(p.foreground)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setOnCheckedChangeListener { _, checked ->
                        PluginManager.setPluginEnabled(plug.id, checked)
                        renderPlugins()
                        onChanged?.invoke()
                    }
                }
                topRow.addView(cb)

                topRow.addView(TextView(context).apply {
                    text = "v${plug.version}"
                    textSize = 10.5f
                    typeface = Typeface.MONOSPACE
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(if (plug.enabled) p.ring else p.mutedForeground)
                    background = rounded(ColorUtils.setAlphaComponent(p.ring, 24), 6f)
                    setPadding(dp(8f), dp(2f), dp(8f), dp(2f))
                })
                card.addView(topRow)

                card.addView(TextView(context).apply {
                    text = plug.description
                    textSize = 12f
                    setTextColor(p.mutedForeground)
                    setPadding(0, dp(4f), 0, dp(6f))
                })

                val badgeLines = mutableListOf<String>()
                if (plug.skillIds.isNotEmpty()) badgeLines.add("📚 Bundled Skills (${plug.skillIds.size}): ${plug.skillIds.joinToString(", ")}")
                if (plug.slashCommands.isNotEmpty()) badgeLines.add("⚡ Slash Commands: ${plug.slashCommands.joinToString(", ") { it.command }}")
                if (plug.mcpConnectorIds.isNotEmpty()) badgeLines.add("🔌 MCP Connectors: ${plug.mcpConnectorIds.joinToString(", ")}")
                if (plug.systemPromptAppend.isNotBlank()) badgeLines.add("📜 Domain Rules: ${plug.systemPromptAppend.take(90)}${if (plug.systemPromptAppend.length > 90) "…" else ""}")

                if (badgeLines.isNotEmpty()) {
                    card.addView(TextView(context).apply {
                        text = badgeLines.joinToString("\n")
                        textSize = 11f
                        typeface = Typeface.MONOSPACE
                        setTextColor(p.foreground)
                        background = rounded(p.secondary, 8f, p.border)
                        setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
                    })
                }

                val btnRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                    setPadding(0, dp(8f), 0, 0)
                }
                btnRow.addView(smallBtn("plugin.json") { showManifestViewerDialog(plug) })
                btnRow.addView(smallBtn("Edit") { showCreatePluginDialog(plug) }.apply {
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6f) }
                })
                if (onUsePrompt != null && plug.slashCommands.isNotEmpty()) {
                    val firstSlash = plug.slashCommands.first()
                    btnRow.addView(smallBtn("Jalankan ${firstSlash.command}") {
                        onUsePrompt.invoke(firstSlash.prompt)
                        dialog.dismiss()
                    }.apply {
                        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6f) }
                    })
                }
                btnRow.addView(smallBtn("Hapus", p.destructive) {
                    MaterialAlertDialogBuilder(context)
                        .setTitle("Hapus Plugin '${plug.name}'?")
                        .setMessage("Folder plugin '${plug.id}' akan dihapus dari perangkat.")
                        .setNegativeButton("Batal", null)
                        .setPositiveButton("Hapus") { _, _ ->
                            PluginManager.deletePlugin(plug.id)
                            renderPlugins()
                            onChanged?.invoke()
                            SketchwareUtil.toast("Plugin '${plug.name}' dihapus")
                        }
                        .show()
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6f) }
                })

                card.addView(btnRow)
                pluginsListContainer.addView(card)
            }
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                renderPlugins()
            }
        })

        btnInstallHeader.setOnClickListener { showInstallPluginDialog() }
        btnInstallArchive.setOnClickListener { showInstallPluginDialog() }
        btnCreateCustomPlugin.setOnClickListener { showCreatePluginDialog(null) }

        renderPlugins()

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
