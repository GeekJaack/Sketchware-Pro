package pro.sketchware.ai.ui

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
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
import android.text.TextUtils
import android.text.TextWatcher
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
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import pro.sketchware.R
import pro.sketchware.ai.config.AiConfigManager
import pro.sketchware.ai.extensions.McpClientHub
import pro.sketchware.ai.extensions.McpConnectorConfig
import pro.sketchware.ai.extensions.McpRemoteTool
import pro.sketchware.ai.extensions.ReaMcpToolsCatalog
import pro.sketchware.ai.extensions.SkillManager
import pro.sketchware.ai.mcp.SketchwareMcpServer
import pro.sketchware.utility.SketchwareUtil
import java.util.concurrent.Executors
import com.google.android.material.R as MR

/**
 * Dedicated full-screen DeerFlow 2.0 Studio for:
 * 1. Inbound Sketchware Live MCP Server (Port, Enable/Disable, LAN & Local URL copy)
 * 2. Outbound MCP Connectors (`rea_mcp`, `debian_toolkit`, `context7`, custom HTTP & Stdio)
 * 3. Interactive 205-Tool REA MCP Catalog Explorer (Non-ADB) with live search & category filter chips
 */
object McpStudioScreen {

    private val bgExecutor = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())

    @JvmStatic
    fun show(
        context: Context,
        onUsePrompt: ((String) -> Unit)? = null,
        onChanged: (() -> Unit)? = null
    ) {
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

        fun copyText(label: String, value: String) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            cm.setPrimaryClip(ClipData.newPlainText(label, value))
            SketchwareUtil.toast("Disalin: $value")
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
            text = "🔌 MCP Studio & Katalog REA (${ReaMcpToolsCatalog.TOTAL_TOOLS_COUNT} Tools)"
            textSize = 16f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(p.foreground)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        titleCol.addView(TextView(context).apply {
            text = "Live MCP Server · Outbound MCP Client · ${ReaMcpToolsCatalog.TOTAL_TOOLS_COUNT} REA Tools (Tanpa ADB)"
            textSize = 11.5f
            setTextColor(p.mutedForeground)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        header.addView(titleCol)

        val btnAddConnectorHeader = MaterialButton(context).apply {
            text = "+ Konektor"
            textSize = 11.5f
            isAllCaps = false
            backgroundTintList = ColorStateList.valueOf(p.ring)
            setTextColor(Color.WHITE)
            cornerRadius = dp(10f)
            minHeight = dp(36f)
        }
        header.addView(btnAddConnectorHeader)
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

        fun sectionCard(): LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(p.card, 14f, p.border)
            setPadding(dp(14f), dp(12f), dp(14f), dp(14f))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12f)
            }
        }

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

        // ==================================================================
        // SECTION 1: Inbound Sketchware Live MCP Server
        // ==================================================================
        val inboundCard = sectionCard()
        val inboundTopRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        inboundTopRow.addView(TextView(context).apply {
            text = "🌐 Sketchware Live MCP Server (Inbound)"
            textSize = 14f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(p.foreground)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val tvInboundStatusBadge = TextView(context).apply {
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8f), dp(3f), dp(8f), dp(3f))
        }
        inboundTopRow.addView(tvInboundStatusBadge)
        inboundCard.addView(inboundTopRow)

        inboundCard.addView(TextView(context).apply {
            text = "Server MCP lokal di HP yang memungkinkan AI Agent eksternal (Antigravity, Claude Code, Cursor) mengendalikan proyek Sketchware Pro secara real-time."
            textSize = 11.5f
            setTextColor(p.mutedForeground)
            setPadding(0, dp(3f), 0, dp(8f))
        })

        val tvEndpointsInfo = TextView(context).apply {
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setTextColor(p.foreground)
            background = rounded(p.secondary, 10f, p.border)
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
        }
        inboundCard.addView(tvEndpointsInfo)

        val inboundControlsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8f), 0, 0)
        }
        val cbMcpServerEnabled = MaterialCheckBox(context).apply {
            text = "Aktifkan Server"
            isChecked = config.mcpServerEnabled
            setTextColor(p.foreground)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tilPort = TextInputLayout(context).apply {
            hint = "Port"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            layoutParams = LinearLayout.LayoutParams(dp(100f), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(8f)
            }
        }
        val etPort = TextInputEditText(tilPort.context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setText(config.mcpServerPort.toString())
            setTextColor(p.foreground)
            textSize = 12f
        }
        tilPort.addView(etPort)

        val btnApplyServer = smallBtn("Terapkan") {
            val newPort = etPort.text?.toString()?.toIntOrNull() ?: 8765
            config.mcpServerEnabled = cbMcpServerEnabled.isChecked
            config.mcpServerPort = newPort
            if (cbMcpServerEnabled.isChecked) {
                SketchwareMcpServer.ensureStarted(context)
            } else {
                SketchwareMcpServer.stop()
            }
            onChanged?.invoke()
        }
        inboundControlsRow.addView(cbMcpServerEnabled)
        inboundControlsRow.addView(tilPort)
        inboundControlsRow.addView(btnApplyServer)
        inboundCard.addView(inboundControlsRow)

        val urlCopyRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            setPadding(0, dp(6f), 0, 0)
        }
        inboundCard.addView(urlCopyRow)
        content.addView(inboundCard)

        fun refreshInboundServerUi() {
            val running = SketchwareMcpServer.isRunning()
            val port = if (running) SketchwareMcpServer.boundPort else config.mcpServerPort
            val lanIp = SketchwareMcpServer.getLanIpAddress()
            val localUrl = "http://127.0.0.1:$port/mcp"
            val lanUrl = "http://$lanIp:$port/mcp"

            tvInboundStatusBadge.text = if (running) "● AKTIF :$port" else "○ NONAKTIF"
            tvInboundStatusBadge.setTextColor(if (running) p.success else p.warning)
            tvInboundStatusBadge.background = rounded(
                ColorUtils.setAlphaComponent(if (running) p.success else p.warning, 28),
                8f,
                if (running) p.success else p.warning
            )
            tvEndpointsInfo.text = "Local URL : $localUrl\nWi-Fi LAN : $lanUrl"

            urlCopyRow.removeAllViews()
            urlCopyRow.addView(smallBtn("Salin Local URL") { copyText("mcp_local", localUrl) })
            urlCopyRow.addView(smallBtn("Salin Wi-Fi LAN URL") { copyText("mcp_lan", lanUrl) }.apply {
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8f) }
            })
        }
        btnApplyServer.setOnClickListener {
            val newPort = etPort.text?.toString()?.toIntOrNull() ?: 8765
            config.mcpServerEnabled = cbMcpServerEnabled.isChecked
            config.mcpServerPort = newPort
            if (cbMcpServerEnabled.isChecked) {
                SketchwareMcpServer.ensureStarted(context)
            } else {
                SketchwareMcpServer.stop()
            }
            refreshInboundServerUi()
            onChanged?.invoke()
            SketchwareUtil.toast("Konfigurasi Live MCP Server diperbarui")
        }
        refreshInboundServerUi()

        // ==================================================================
        // SECTION 2: Outbound MCP Connectors
        // ==================================================================
        val outboundCard = sectionCard()
        val outboundHeaderRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        outboundHeaderRow.addView(TextView(context).apply {
            text = "🔗 Konektor MCP Eksternal & Built-in (Outbound)"
            textSize = 14f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(p.foreground)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val btnSyncAll = smallBtn("Sync Semua") {}
        outboundHeaderRow.addView(btnSyncAll)
        outboundCard.addView(outboundHeaderRow)

        outboundCard.addView(TextView(context).apply {
            text = "Kelola server MCP yang dipakai AI Agent saat membedah APK, mengeksekusi Python/SQLite di Debian Linux, atau membaca dokumentasi live."
            textSize = 11.5f
            setTextColor(p.mutedForeground)
            setPadding(0, dp(3f), 0, dp(8f))
        })

        val connectorsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        outboundCard.addView(connectorsContainer)
        content.addView(outboundCard)

        // ==================================================================
        // SECTION 3: Interactive 205 REA & MCP Tools Catalog Explorer
        // ==================================================================
        val catalogCard = sectionCard()
        catalogCard.addView(TextView(context).apply {
            text = "🧬 Katalog & Pencarian ${ReaMcpToolsCatalog.TOTAL_TOOLS_COUNT} Tools REA MCP (Non-ADB)"
            textSize = 14f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(p.foreground)
        })
        catalogCard.addView(TextView(context).apply {
            text = "Seluruh 24 tool ADB telah dihapus karena berjalan di perangkat Android. AI Agent secara otomatis mencari & memakai 205 tool ini via `search_mcp_tools` dan `mcp__rea_mcp__<tool>`."
            textSize = 11.5f
            setTextColor(p.mutedForeground)
            setPadding(0, dp(3f), 0, dp(8f))
        })

        val tilSearch = TextInputLayout(context).apply {
            hint = "Cari dari ${ReaMcpToolsCatalog.TOTAL_TOOLS_COUNT} tools REA (contoh: apk, dex, manifest, jadx, sqlite, flutter, frida)…"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        val etSearch = TextInputEditText(tilSearch.context).apply {
            setSingleLine(true)
            setTextColor(p.foreground)
            textSize = 12.5f
        }
        tilSearch.addView(etSearch)
        catalogCard.addView(tilSearch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Horizontal category filter chips
        val catScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, dp(8f), 0, dp(6f))
        }
        val catRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        catScroll.addView(catRow)
        catalogCard.addView(catScroll)

        val tvCatalogCount = TextView(context).apply {
            textSize = 11.5f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(p.ring)
            setPadding(0, dp(2f), 0, dp(6f))
        }
        catalogCard.addView(tvCatalogCount)

        val toolsListContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        catalogCard.addView(toolsListContainer)
        content.addView(catalogCard)

        var selectedCategory = ""
        lateinit var renderCategoryChips: () -> Unit
        lateinit var renderCatalogTools: () -> Unit
        lateinit var renderConnectors: () -> Unit

        fun extractToolCategory(tool: McpRemoteTool): String {
            return if (tool.description.startsWith("[")) {
                tool.description.substringAfter("[").substringBefore("]").ifBlank { "General" }
            } else {
                "General"
            }
        }

        fun showToolDetailDialog(entry: McpRemoteTool) {
            val catName = extractToolCategory(entry)
            val body = buildString {
                appendLine("Kategori : $catName")
                appendLine("Qualified: ${entry.qualifiedName}")
                appendLine()
                appendLine(entry.description)
                appendLine()
                if (entry.parameters.isEmpty()) {
                    appendLine("Parameter: (tidak ada parameter wajib)")
                } else {
                    appendLine("Parameter (${entry.parameters.size}):")
                    for (param in entry.parameters) {
                        val req = if (param.required) "WAJIB" else "opsional"
                        appendLine("• ${param.name} (${param.type}, $req)")
                        if (param.description.isNotBlank()) {
                            appendLine("  ${param.description}")
                        }
                    }
                }
            }.trim()

            MaterialAlertDialogBuilder(context)
                .setTitle("🔧 ${entry.name}")
                .setMessage(body)
                .setNeutralButton("Salin Nama Tool") { _, _ ->
                    copyText("rea_tool", entry.qualifiedName)
                }
                .apply {
                    if (onUsePrompt != null) {
                        setPositiveButton("Pakai di Chat") { _, _ ->
                            onUsePrompt.invoke(
                                "Cari dan gunakan tool REA MCP `${entry.qualifiedName}` ($catName) sesuai kebutuhan proyek ini: "
                            )
                            dialog.dismiss()
                        }
                    } else {
                        setPositiveButton("Tutup", null)
                    }
                }
                .show()
        }

        renderCategoryChips = {
            catRow.removeAllViews()
            val categories = listOf("Semua (${ReaMcpToolsCatalog.TOTAL_TOOLS_COUNT})" to "") +
                ReaMcpToolsCatalog.listCategories().map { (cat, cnt) -> "$cat ($cnt)" to cat }

            for ((label, catValue) in categories) {
                val active = (selectedCategory == catValue)
                val pill = TextView(context).apply {
                    text = label
                    textSize = 11.5f
                    if (active) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(if (active) Color.WHITE else p.foreground)
                    background = rounded(
                        if (active) p.ring else p.secondary,
                        16f,
                        if (active) p.ring else p.border
                    )
                    setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
                    isClickable = true
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginEnd = dp(6f)
                    }
                    setOnClickListener {
                        selectedCategory = catValue
                        renderCategoryChips()
                        renderCatalogTools()
                    }
                }
                catRow.addView(pill)
            }
        }

        renderCatalogTools = {
            toolsListContainer.removeAllViews()
            val q = etSearch.text?.toString()?.trim().orEmpty()
            val matched = ReaMcpToolsCatalog.searchTools(
                query = q,
                category = selectedCategory,
                maxResults = 45
            )
            val totalInFilter = if (q.isEmpty() && selectedCategory.isEmpty()) {
                ReaMcpToolsCatalog.TOTAL_TOOLS_COUNT
            } else {
                ReaMcpToolsCatalog.searchTools(query = q, category = selectedCategory, maxResults = 500).size
            }

            tvCatalogCount.text = if (totalInFilter > matched.size) {
                "Menampilkan ${matched.size} dari $totalInFilter tools yang cocok (ketik kata kunci lebih spesifik untuk menyaring)"
            } else {
                "Menampilkan ${matched.size} tools REA (0 ADB tools)"
            }

            for (tool in matched) {
                val catName = extractToolCategory(tool)
                val itemCard = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = rounded(p.secondary, 11f, p.border)
                    setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(8f)
                    }
                    isClickable = true
                    setOnClickListener { showToolDetailDialog(tool) }
                }

                val topLine = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                topLine.addView(TextView(context).apply {
                    text = tool.name
                    textSize = 13f
                    typeface = Typeface.MONOSPACE
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(p.foreground)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                topLine.addView(TextView(context).apply {
                    text = catName
                    textSize = 10f
                    setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(p.ring)
                    background = rounded(ColorUtils.setAlphaComponent(p.ring, 26), 6f)
                    setPadding(dp(7f), dp(2f), dp(7f), dp(2f))
                })
                itemCard.addView(topLine)

                itemCard.addView(TextView(context).apply {
                    text = tool.description
                    textSize = 11.5f
                    setTextColor(p.mutedForeground)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(4f), 0, dp(4f))
                })

                val paramSummary = if (tool.parameters.isEmpty()) {
                    "Params: (none)"
                } else {
                    "Params: " + tool.parameters.joinToString(", ") {
                        "${it.name}${if (it.required) "*" else ""}:${it.type}"
                    }
                }

                val bottomRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                bottomRow.addView(TextView(context).apply {
                    text = paramSummary
                    textSize = 10.5f
                    typeface = Typeface.MONOSPACE
                    setTextColor(p.mutedForeground)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                if (onUsePrompt != null) {
                    bottomRow.addView(TextView(context).apply {
                        text = "Pakai di Chat →"
                        textSize = 11f
                        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                        setTextColor(p.ring)
                        setPadding(dp(8f), dp(2f), dp(4f), dp(2f))
                        setOnClickListener {
                            onUsePrompt.invoke(
                                "Gunakan tool REA MCP `${tool.qualifiedName}` ($catName) untuk proyek ini: "
                            )
                            dialog.dismiss()
                        }
                    })
                }
                itemCard.addView(bottomRow)
                toolsListContainer.addView(itemCard)
            }
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                renderCatalogTools()
            }
        })

        fun showAddConnectorDialog() {
            val formScroll = ScrollView(context)
            val form = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16f), dp(12f), dp(16f), dp(8f))
            }
            formScroll.addView(form)

            val tilName = TextInputLayout(context).apply { hint = "Nama Konektor (misal: Context7 / GitHub MCP)" }
            val etName = TextInputEditText(tilName.context).apply { setSingleLine(true) }
            tilName.addView(etName)
            form.addView(tilName)

            val transportGroup = ChipGroup(context).apply {
                isSingleSelection = true
                isSelectionRequired = true
                setPadding(0, dp(8f), 0, dp(4f))
            }
            var selectedTransport = "http"
            val tilTarget = TextInputLayout(context).apply {
                hint = "URL Endpoint HTTP MCP (contoh: https://mcp.context7.com/mcp)"
            }
            val etTarget = TextInputEditText(tilTarget.context).apply { setSingleLine(true) }
            tilTarget.addView(etTarget)

            val cbInLinux = MaterialCheckBox(context).apply {
                text = "Jalankan di dalam Linux / Debian (proot)"
                isChecked = true
                visibility = View.GONE
            }

            val chipHttp = Chip(context).apply {
                text = "HTTP / Streamable HTTP"
                isCheckable = true
                isChecked = true
                setOnClickListener {
                    selectedTransport = "http"
                    tilTarget.hint = "URL Endpoint HTTP MCP (contoh: https://mcp.context7.com/mcp)"
                    cbInLinux.visibility = View.GONE
                }
            }
            val chipStdio = Chip(context).apply {
                text = "Stdio (Perintah Shell / Debian)"
                isCheckable = true
                setOnClickListener {
                    selectedTransport = "stdio"
                    tilTarget.hint = "Perintah Stdio (contoh: python3 /path/server.py atau npx -y ...)"
                    cbInLinux.visibility = View.VISIBLE
                }
            }
            transportGroup.addView(chipHttp)
            transportGroup.addView(chipStdio)
            form.addView(transportGroup)
            form.addView(tilTarget)
            form.addView(cbInLinux)

            val tilHeader = TextInputLayout(context).apply {
                hint = "Opsional: Authorization / Bearer Token"
                setPadding(0, dp(8f), 0, 0)
            }
            val etHeader = TextInputEditText(tilHeader.context).apply { setSingleLine(true) }
            tilHeader.addView(etHeader)
            form.addView(tilHeader)

            MaterialAlertDialogBuilder(context)
                .setTitle("Tambah Konektor MCP Baru")
                .setView(formScroll)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Simpan & Sync") { _, _ ->
                    val name = etName.text?.toString()?.trim().orEmpty()
                    val target = etTarget.text?.toString()?.trim().orEmpty()
                    val token = etHeader.text?.toString()?.trim().orEmpty()
                    if (name.isEmpty() || target.isEmpty()) {
                        SketchwareUtil.toastError("Nama dan URL/Perintah wajib diisi")
                        return@setPositiveButton
                    }
                    val headers = if (token.isNotEmpty()) {
                        val authVal = if (token.startsWith("Bearer ", ignoreCase = true)) token else "Bearer $token"
                        mapOf("Authorization" to authVal)
                    } else emptyMap()
                    val saved = McpClientHub.addOrUpdateConnector(
                        McpConnectorConfig(
                            id = SkillManager.sanitizeId(name),
                            name = name,
                            transport = selectedTransport,
                            url = if (selectedTransport == "http") target else "",
                            command = if (selectedTransport == "stdio") target else "",
                            inLinux = cbInLinux.isChecked,
                            headers = headers,
                            enabled = true
                        )
                    )
                    renderConnectors()
                    onChanged?.invoke()
                    SketchwareUtil.toast("Menyinkronkan tool dari '${saved.name}'…")
                    bgExecutor.execute {
                        val msg = McpClientHub.syncConnectorTools(saved.id)
                        mainHandler.post {
                            SketchwareUtil.toast(msg.lines().firstOrNull() ?: msg)
                            renderConnectors()
                            onChanged?.invoke()
                        }
                    }
                }
                .show()
        }

        renderConnectors = {
            connectorsContainer.removeAllViews()
            val connectors = McpClientHub.listConnectors()
            for (conn in connectors) {
                val card = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = rounded(
                        if (conn.enabled) ColorUtils.setAlphaComponent(p.ring, 18) else p.secondary,
                        12f,
                        if (conn.enabled) p.ring else p.border
                    )
                    setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(8f)
                    }
                }

                val topRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val cb = MaterialCheckBox(context).apply {
                    isChecked = conn.enabled
                    text = conn.name
                    textSize = 13f
                    setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(p.foreground)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setOnCheckedChangeListener { _, checked ->
                        McpClientHub.setConnectorEnabled(conn.id, checked)
                        renderConnectors()
                        onChanged?.invoke()
                    }
                }
                topRow.addView(cb)
                topRow.addView(TextView(context).apply {
                    text = "${conn.transport.uppercase()} · ${conn.cachedTools.size} tools"
                    textSize = 10f
                    typeface = Typeface.MONOSPACE
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(p.ring)
                    background = rounded(ColorUtils.setAlphaComponent(p.ring, 26), 6f)
                    setPadding(dp(7f), dp(2f), dp(7f), dp(2f))
                })
                card.addView(topRow)

                val targetInfo = if (conn.transport == "http") conn.url else conn.command
                card.addView(TextView(context).apply {
                    text = "ID: ${conn.id} · Target: $targetInfo\nStatus: ${conn.lastStatus}"
                    textSize = 11f
                    setTextColor(p.mutedForeground)
                    setPadding(0, dp(2f), 0, dp(6f))
                })

                if (conn.cachedTools.isNotEmpty()) {
                    val previewNames = conn.cachedTools.take(12).joinToString(", ") { it.name }
                    val more = if (conn.cachedTools.size > 12) " … (+${conn.cachedTools.size - 12} lainnya)" else ""
                    card.addView(TextView(context).apply {
                        text = "Tools: $previewNames$more"
                        textSize = 10.5f
                        typeface = Typeface.MONOSPACE
                        setTextColor(p.foreground)
                        setPadding(0, 0, 0, dp(6f))
                    })
                }

                val btnRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                }
                btnRow.addView(smallBtn("Sync Tools") {
                    SketchwareUtil.toast("Menghubungi ${conn.name}…")
                    bgExecutor.execute {
                        val res = McpClientHub.syncConnectorTools(conn.id)
                        mainHandler.post {
                            SketchwareUtil.toast(res.lines().firstOrNull() ?: res)
                            renderConnectors()
                            onChanged?.invoke()
                        }
                    }
                })
                if (conn.source == "user") {
                    btnRow.addView(smallBtn("Hapus", p.destructive) {
                        McpClientHub.removeConnector(conn.id)
                        renderConnectors()
                        onChanged?.invoke()
                    }.apply {
                        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6f) }
                    })
                }
                card.addView(btnRow)
                connectorsContainer.addView(card)
            }
        }

        btnAddConnectorHeader.setOnClickListener { showAddConnectorDialog() }
        btnSyncAll.setOnClickListener {
            val enabledList = McpClientHub.listConnectors().filter { it.enabled }
            if (enabledList.isEmpty()) {
                SketchwareUtil.toast("Tidak ada konektor MCP yang aktif.")
            } else {
                SketchwareUtil.toast("Menyinkronkan ${enabledList.size} konektor MCP…")
                bgExecutor.execute {
                    enabledList.forEach { McpClientHub.syncConnectorTools(it.id) }
                    mainHandler.post {
                        SketchwareUtil.toast("Sinkronisasi MCP selesai!")
                        renderConnectors()
                        onChanged?.invoke()
                    }
                }
            }
        }

        renderConnectors()
        renderCategoryChips()
        renderCatalogTools()

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
