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
import android.widget.HorizontalScrollView
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
import pro.sketchware.ai.extensions.AgentSkill
import pro.sketchware.ai.extensions.SkillManager
import pro.sketchware.utility.SketchwareUtil
import java.io.File
import java.util.concurrent.Executors
import com.google.android.material.R as MR

/**
 * Dedicated full-screen DeerFlow 2.0 Studio exclusively for browsing, searching, reading,
 * creating, editing, and importing `SKILL.md` Skills.
 *
 * Separated completely from API Key Settings, MCP Connectors, and Plugins views.
 */
object SkillStudioScreen {

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
            text = "📚 Skills Studio (SKILL.md)"
            textSize = 16.5f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(p.foreground)
        })
        titleCol.addView(TextView(context).apply {
            text = "Pustaka Instruksi & Metodologi Khusus untuk Sketchware AI Agent"
            textSize = 11.5f
            setTextColor(p.mutedForeground)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        header.addView(titleCol)

        val btnNewSkillHeader = MaterialButton(context).apply {
            text = "+ Skill Baru"
            textSize = 11.5f
            isAllCaps = false
            backgroundTintList = ColorStateList.valueOf(p.ring)
            setTextColor(Color.WHITE)
            cornerRadius = dp(10f)
            minHeight = dp(36f)
        }
        header.addView(btnNewSkillHeader)
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

        // Top search & filter card
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
            text = "Skill disimpan di `.sketchware/ai/skills/<id>/SKILL.md`. AI Agent otomatis membaca daftar skill di System Prompt dan memanggil `use_skill(action=\"read\", skill_id=\"...\")` saat mengerjakan tugas yang relevan."
            textSize = 11.5f
            setTextColor(p.mutedForeground)
            setPadding(0, dp(3f), 0, dp(8f))
        })

        val tilSearch = TextInputLayout(context).apply {
            hint = "Cari skill berdasarkan nama, ID, atau topik (contoh: rea, apk, material, rest, terminal)…"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        val etSearch = TextInputEditText(tilSearch.context).apply {
            setSingleLine(true)
            setTextColor(p.foreground)
            textSize = 12.5f
        }
        tilSearch.addView(etSearch)
        topCard.addView(tilSearch)

        val filterScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, dp(8f), 0, dp(4f))
        }
        val filterRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        filterScroll.addView(filterRow)
        topCard.addView(filterScroll)

        val actionBtnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            setPadding(0, dp(6f), 0, 0)
        }
        val btnCreateSkill = smallBtn("✍️ Tulis SKILL.md Baru") {}
        val btnImportUrl = smallBtn("🌐 Impor SKILL.md dari URL") {}.apply {
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8f) }
        }
        actionBtnRow.addView(btnCreateSkill)
        actionBtnRow.addView(btnImportUrl)
        topCard.addView(actionBtnRow)
        content.addView(topCard)

        val skillsListContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(skillsListContainer)

        var selectedFilter = "ALL" // ALL, PATENT, STANDALONE, PLUGIN
        lateinit var renderFilterPills: () -> Unit
        lateinit var renderSkills: () -> Unit

        fun showSkillEditorDialog(existing: AgentSkill? = null) {
            val formScroll = ScrollView(context)
            val form = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16f), dp(12f), dp(16f), dp(8f))
            }
            formScroll.addView(form)

            val tilName = TextInputLayout(context).apply { hint = "Nama Skill (contoh: Clean Architecture Android)" }
            val etName = TextInputEditText(tilName.context).apply {
                setSingleLine(true)
                if (existing != null) setText(existing.name)
            }
            tilName.addView(etName)
            form.addView(tilName)

            val tilDesc = TextInputLayout(context).apply {
                hint = "Deskripsi kapan AI Agent harus membaca skill ini"
                setPadding(0, dp(8f), 0, 0)
            }
            val etDesc = TextInputEditText(tilDesc.context).apply {
                if (existing != null) setText(existing.description)
            }
            tilDesc.addView(etDesc)
            form.addView(tilDesc)

            val tilBody = TextInputLayout(context).apply {
                hint = "Isi Instruksi Markdown (SKILL.md)"
                setPadding(0, dp(8f), 0, 0)
            }
            val existingBody = if (existing != null && File(existing.skillFilePath).isFile) {
                File(existing.skillFilePath).readText()
            } else ""
            val etBody = TextInputEditText(tilBody.context).apply {
                minLines = 8
                maxLines = 18
                gravity = Gravity.TOP or Gravity.START
                typeface = Typeface.MONOSPACE
                textSize = 12f
                setText(existingBody)
            }
            tilBody.addView(etBody)
            form.addView(tilBody)

            MaterialAlertDialogBuilder(context)
                .setTitle(if (existing == null) "Buat Skill Baru (SKILL.md)" else "Edit Skill: ${existing.name}")
                .setView(formScroll)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Simpan Skill") { _, _ ->
                    val nameText = etName.text?.toString()?.trim().orEmpty()
                    val desc = etDesc.text?.toString()?.trim().orEmpty()
                    val body = etBody.text?.toString()?.trim().orEmpty()
                    if (nameText.isEmpty() || body.isEmpty()) {
                        SketchwareUtil.toastError("Nama dan isi Markdown SKILL.md wajib diisi")
                        return@setPositiveButton
                    }
                    val id = existing?.id ?: SkillManager.sanitizeId(nameText)
                    SkillManager.createOrUpdateSkill(
                        id = id,
                        name = nameText,
                        description = desc.ifBlank { "Panduan khusus untuk $nameText" },
                        bodyMarkdown = body
                    )
                    SketchwareUtil.toast("Skill '$nameText' berhasil disimpan!")
                    renderFilterPills()
                    renderSkills()
                    onChanged?.invoke()
                }
                .show()
        }

        fun showImportUrlDialog() {
            val form = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16f), dp(12f), dp(16f), dp(8f))
            }
            val tilUrl = TextInputLayout(context).apply {
                hint = "URL HTTP/HTTPS ke file SKILL.md (contoh: raw GitHub URL)"
            }
            val etUrl = TextInputEditText(tilUrl.context).apply { setSingleLine(true) }
            tilUrl.addView(etUrl)
            form.addView(tilUrl)

            val tilId = TextInputLayout(context).apply {
                hint = "Opsional: ID Skill kustom"
                setPadding(0, dp(8f), 0, 0)
            }
            val etId = TextInputEditText(tilId.context).apply { setSingleLine(true) }
            tilId.addView(etId)
            form.addView(tilId)

            MaterialAlertDialogBuilder(context)
                .setTitle("Impor SKILL.md dari URL")
                .setView(form)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Unduh & Pasang") { _, _ ->
                    val url = etUrl.text?.toString()?.trim().orEmpty()
                    val customId = etId.text?.toString()?.trim().orEmpty().ifBlank { null }
                    if (url.isEmpty()) {
                        SketchwareUtil.toastError("Masukkan URL terlebih dahulu")
                        return@setPositiveButton
                    }
                    SketchwareUtil.toast("Mengunduh SKILL.md…")
                    bgExecutor.execute {
                        val res = SkillManager.installSkillFromUrl(url, customId)
                        mainHandler.post {
                            SketchwareUtil.toast(res)
                            renderFilterPills()
                            renderSkills()
                            onChanged?.invoke()
                        }
                    }
                }
                .show()
        }

        fun showSkillReaderDialog(skill: AgentSkill) {
            val mdContent = if (File(skill.skillFilePath).isFile) {
                File(skill.skillFilePath).readText()
            } else {
                SkillManager.readSkillContent(skill.id)
            }
            val tv = TextView(context).apply {
                text = mdContent
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setTextColor(p.foreground)
                setTextIsSelectable(true)
                setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
            }
            val s = ScrollView(context).apply { addView(tv) }
            MaterialAlertDialogBuilder(context)
                .setTitle("📖 ${skill.name}")
                .setView(s)
                .setNeutralButton("Edit SKILL.md") { _, _ -> showSkillEditorDialog(skill) }
                .apply {
                    if (onUsePrompt != null) {
                        setPositiveButton("Pakai di Chat") { _, _ ->
                            onUsePrompt.invoke(
                                "Gunakan skill '${skill.id}' (panggil use_skill(action=\"read\", skill_id=\"${skill.id}\")) untuk menyelesaikan tugas berikut: "
                            )
                            dialog.dismiss()
                        }
                    } else {
                        setPositiveButton("Tutup", null)
                    }
                }
                .show()
        }

        renderFilterPills = {
            filterRow.removeAllViews()
            val all = SkillManager.listAllSkills()
            val patentCount = all.count { it.id.startsWith("patent-") }
            val standaloneCount = all.count { it.source == "standalone" && !it.id.startsWith("patent-") }
            val pluginCount = all.count { it.source.startsWith("plugin:") }

            val filters = listOf(
                "Semua (${all.size})" to "ALL",
                "🏅 Patent Doctrines ($patentCount)" to "PATENT",
                "📄 Standalone ($standaloneCount)" to "STANDALONE",
                "🧩 Dari Plugin ($pluginCount)" to "PLUGIN"
            )
            for ((label, key) in filters) {
                val active = (selectedFilter == key)
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
                        selectedFilter = key
                        renderFilterPills()
                        renderSkills()
                    }
                }
                filterRow.addView(pill)
            }
        }

        renderSkills = {
            skillsListContainer.removeAllViews()
            val allSkills = SkillManager.listAllSkills()
            val enabledCount = allSkills.count { it.enabled }
            tvStats.text = "Total Skill Terpasang: ${allSkills.size}  ·  Aktif: $enabledCount"

            val q = etSearch.text?.toString()?.trim()?.lowercase().orEmpty()
            val filtered = allSkills.filter { s ->
                val matchesCategory = when (selectedFilter) {
                    "PATENT" -> s.id.startsWith("patent-")
                    "STANDALONE" -> s.source == "standalone" && !s.id.startsWith("patent-")
                    "PLUGIN" -> s.source.startsWith("plugin:")
                    else -> true
                }
                val matchesQuery = q.isEmpty() ||
                    s.name.lowercase().contains(q) ||
                    s.id.lowercase().contains(q) ||
                    s.description.lowercase().contains(q)
                matchesCategory && matchesQuery
            }

            if (filtered.isEmpty()) {
                skillsListContainer.addView(TextView(context).apply {
                    text = "Tidak ada skill yang cocok dengan filter saat ini."
                    textSize = 12f
                    setTextColor(p.mutedForeground)
                    setPadding(dp(8f), dp(12f), dp(8f), dp(12f))
                })
            }

            for (s in filtered) {
                val isPatent = s.id.startsWith("patent-")
                val card = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = rounded(
                        if (s.enabled) p.card else p.secondary,
                        14f,
                        if (isPatent && s.enabled) p.gold else if (s.enabled) p.ring else p.border
                    )
                    setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(10f)
                    }
                    isClickable = true
                    setOnClickListener { showSkillReaderDialog(s) }
                }

                val topRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val cb = MaterialCheckBox(context).apply {
                    isChecked = s.enabled
                    text = s.name
                    textSize = 13.5f
                    setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(if (isPatent) p.gold else p.foreground)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setOnCheckedChangeListener { _, checked ->
                        SkillManager.setSkillEnabled(s.id, checked)
                        renderSkills()
                        onChanged?.invoke()
                    }
                }
                topRow.addView(cb)

                val badgeColor = if (isPatent) p.gold else p.ring
                topRow.addView(TextView(context).apply {
                    text = if (isPatent) "PATENT" else s.source.uppercase()
                    textSize = 10f
                    typeface = Typeface.MONOSPACE
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(badgeColor)
                    background = rounded(ColorUtils.setAlphaComponent(badgeColor, 26), 6f)
                    setPadding(dp(7f), dp(2f), dp(7f), dp(2f))
                })
                card.addView(topRow)

                card.addView(TextView(context).apply {
                    text = s.description
                    textSize = 12f
                    setTextColor(p.mutedForeground)
                    setPadding(0, dp(3f), 0, dp(6f))
                })

                card.addView(TextView(context).apply {
                    text = "ID: ${s.id}  ·  ${s.skillFilePath}"
                    textSize = 10.5f
                    typeface = Typeface.MONOSPACE
                    setTextColor(p.mutedForeground)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.MIDDLE
                })

                val btnRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                    setPadding(0, dp(8f), 0, 0)
                }
                btnRow.addView(smallBtn("Baca SKILL.md") { showSkillReaderDialog(s) })
                btnRow.addView(smallBtn("Edit") { showSkillEditorDialog(s) }.apply {
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6f) }
                })
                if (onUsePrompt != null) {
                    btnRow.addView(smallBtn("Pakai di Chat") {
                        onUsePrompt.invoke(
                            "Gunakan skill '${s.id}' (panggil use_skill(action=\"read\", skill_id=\"${s.id}\")) untuk menyelesaikan tugas berikut: "
                        )
                        dialog.dismiss()
                    }.apply {
                        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6f) }
                    })
                }
                if (s.source == "standalone") {
                    btnRow.addView(smallBtn("Hapus", p.destructive) {
                        MaterialAlertDialogBuilder(context)
                            .setTitle("Hapus Skill '${s.name}'?")
                            .setMessage("Berkas '${s.id}/SKILL.md' akan dihapus.")
                            .setNegativeButton("Batal", null)
                            .setPositiveButton("Hapus") { _, _ ->
                                SkillManager.deleteSkill(s.id)
                                renderFilterPills()
                                renderSkills()
                                onChanged?.invoke()
                                SketchwareUtil.toast("Skill '${s.name}' dihapus")
                            }
                            .show()
                    }.apply {
                        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6f) }
                    })
                }
                card.addView(btnRow)
                skillsListContainer.addView(card)
            }
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                renderSkills()
            }
        })

        btnNewSkillHeader.setOnClickListener { showSkillEditorDialog(null) }
        btnCreateSkill.setOnClickListener { showSkillEditorDialog(null) }
        btnImportUrl.setOnClickListener { showImportUrlDialog() }

        renderFilterPills()
        renderSkills()

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
