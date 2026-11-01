package pro.sketchware.ai.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import pro.sketchware.R
import pro.sketchware.ai.agent.AgentChatEntry
import pro.sketchware.ai.agent.AgentMemoryStore
import pro.sketchware.ai.agent.AgentOrchestrator
import pro.sketchware.ai.agent.AgentPlan
import pro.sketchware.ai.agent.ChatEntryType
import pro.sketchware.ai.agent.TaskStatus
import pro.sketchware.ai.config.AgentAutonomyMode
import pro.sketchware.ai.config.AiConfigManager
import pro.sketchware.ai.mcp.SketchwareMcpServer
import pro.sketchware.utility.SketchwareUtil
import pro.sketchware.utility.ThemeUtils
import java.util.UUID
import com.google.android.material.R as MR

/**
 * Full-screen DeerFlow 2.0-inspired agentic workspace for the on-device Sketchware AI Agent.
 *
 * Architecture & UX (modeled after https://github.com/bytedance/deer-flow):
 *  - Warm cream / warm charcoal shadcn-oklch palette ([DeerFlowTheme]) with gold "Ultra" accents.
 *  - Collapsible Workspace Sidebar: brand header, "+ New chat", Workspace links (Chats, Artifacts,
 *    Agent modes, Skills, Plugins, MCP connectors, Terminal, Checkpoints), Recent prompts jump-list,
 *    and footer actions (Settings, Language, Close studio).
 *  - Workspace Header: sidebar trigger, dynamic thread title + status dot, Artifacts drawer button
 *    with live deliverable badge, New chat button, and overflow menu.
 *  - Welcome Mode: animated "👋 Hello, again!" / "🚀 Ultra mode is ready!" with AuroraText gradient
 *    sweep, centered floating Input Box, and horizontal suggestion chips (Build, Fix, Design, Learn, Create ▾).
 *  - Execution Stream: user bubbles, markdown assistant replies, collapsible grouped tool timelines
 *    with special badges for Sub-Agent delegation (`delegate_subagent`) & active Skills (`use_skill`),
 *    and a 3-dot bouncing streaming indicator.
 *  - Stacked "To-dos" card directly above the floating Input Box.
 */
class AgentChatScreen(
    private val activity: Activity,
    private val scId: String,
    private val activeScreenName: String?
) {
    private val config = AiConfigManager(activity)
    private val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar)

    // region Theme (DeerFlow 2.0 warm cream / charcoal palette)
    private fun attr(id: Int) = ThemeUtils.getColor(activity, id)
    private val isDark = ColorUtils.calculateLuminance(attr(MR.attr.colorSurface)) < 0.5
    private val deer = DeerFlowTheme.palette(isDark)
    private val cSurface = deer.background
    private val cSurfaceLow = deer.muted
    private val cSurfaceCont = deer.card
    private val cSurfaceHigh = deer.secondary
    private val cOnSurface = deer.foreground
    private val cOnSurfaceVar = deer.mutedForeground
    private val cOutline = deer.border
    private val cPrimary = deer.primary
    private val cOnPrimary = deer.primaryForeground
    private val cError = deer.destructive
    private val cSuccess = deer.success
    private val cWarning = deer.warning
    private val cGold = deer.gold
    private val cPopover = deer.popover
    private val cSidebar = deer.sidebar
    private val cSidebarAccent = deer.sidebarAccent
    private val tx get() = DeerText(config.agentLanguage)
    // endregion

    // region Views
    private lateinit var tvSubtitle: TextView
    private lateinit var statusDot: View
    private lateinit var scroll: NestedScrollView
    private lateinit var messages: LinearLayout
    private lateinit var emptyState: View
    private lateinit var jumpButton: View
    private lateinit var thinkingRow: LinearLayout
    private lateinit var tvThinking: TextView
    private lateinit var tvThinkingStep: TextView
    private lateinit var planPanel: LinearLayout
    private lateinit var planHeaderTitle: TextView
    private lateinit var planHeaderSub: TextView
    private lateinit var planProgress: LinearProgressIndicator
    private lateinit var planListScroll: NestedScrollView
    private lateinit var planList: LinearLayout
    private lateinit var planChevron: ImageView
    private lateinit var slashPanel: LinearLayout
    private lateinit var slashScroll: NestedScrollView
    private lateinit var slashList: LinearLayout
    private lateinit var etPrompt: EditText
    private lateinit var btnSend: FrameLayout
    private lateinit var ivSend: ImageView
    private lateinit var btnAddContext: View
    private lateinit var attachmentsScroll: HorizontalScrollView
    private lateinit var attachmentsRow: LinearLayout
    private lateinit var tvModelPill: TextView
    private lateinit var tvDisclaimer: TextView
    private lateinit var tvEmptySub: TextView
    private val suggestionTitleViews = mutableListOf<TextView>()
    private val attachedContexts = mutableListOf<AttachedContextItem>()

    // DeerFlow workspace shell views
    private lateinit var tvThreadTitle: TextView
    private lateinit var tvArtifactsBadge: TextView
    private lateinit var tvModePill: TextView
    private lateinit var configBanner: View
    private lateinit var composerBox: View
    private lateinit var bottomComposerSlot: LinearLayout
    private lateinit var welcomeComposerSlot: LinearLayout
    private lateinit var tvGreeting: TextView
    private lateinit var tvGreetingEmoji: TextView
    private lateinit var sidebarPanel: LinearLayout
    private lateinit var sidebarScrim: View
    private lateinit var sidebarRecentList: LinearLayout
    private var sidebarOpen = false
    private var auroraAnimator: ValueAnimator? = null
    private val dotAnimators = mutableListOf<ObjectAnimator>()
    private val userMessageViews = mutableListOf<Pair<String, View>>()
    // endregion

    // region State
    private var renderedCount = 0
    private var stickToBottom = true
    private var currentGroup: ToolGroup? = null
    private val allGroups = mutableListOf<ToolGroup>()
    private var planExpanded = false
    private var isRunning = AgentOrchestrator.isRunning
    private var pulse: ObjectAnimator? = null
    private val historyLimit = 150
    // endregion

    private val listener = object : AgentOrchestrator.Listener {
        override fun onChatEntryAdded(scId: String, entry: AgentChatEntry) {
            if (scId != this@AgentChatScreen.scId || !dialog.isShowing) return
            renderEntry(entry, live = true)
            scrollToBottomIfSticky()
        }

        override fun onPlanUpdated(scId: String, plan: AgentPlan) {
            if (scId != this@AgentChatScreen.scId || !dialog.isShowing) return
            renderPlan(plan)
        }

        override fun onStatusChanged(scId: String, isRunning: Boolean, statusText: String, step: Int, maxSteps: Int) {
            if (scId != this@AgentChatScreen.scId || !dialog.isShowing) return
            setRunning(isRunning, statusText, step, maxSteps)
        }
    }

    fun show() {
        val root = buildRoot()
        dialog.setContentView(root)
        dialog.window?.let { w ->
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            w.setBackgroundDrawable(ColorDrawable(cSurface))
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            WindowCompat.setDecorFitsSystemWindows(w, false)
            @Suppress("DEPRECATION")
            w.statusBarColor = Color.TRANSPARENT
            @Suppress("DEPRECATION")
            w.navigationBarColor = cSurface
            WindowInsetsControllerCompat(w, w.decorView).apply {
                isAppearanceLightStatusBars = !isDark
                isAppearanceLightNavigationBars = !isDark
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            if (ime.bottom > 0) scrollToBottomIfSticky()
            WindowInsetsCompat.CONSUMED
        }

        loadHistory()
        renderPlan(AgentMemoryStore.loadPlan(scId))
        setRunning(AgentOrchestrator.isRunning, AgentOrchestrator.currentStatusText, 0, 0)
        refreshHeader()

        AgentOrchestrator.addListener(listener)
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && sidebarOpen) {
                if (event.action == android.view.KeyEvent.ACTION_UP) closeSidebar()
                true
            } else {
                false
            }
        }
        dialog.setOnDismissListener {
            AgentOrchestrator.removeListener(listener)
            pulse?.cancel()
            auroraAnimator?.cancel()
            dotAnimators.forEach { it.cancel() }
        }
        dialog.show()
    }

    fun prefill(text: String) {
        etPrompt.setText(text)
        etPrompt.setSelection(text.length)
    }

    // ------------------------------------------------------------------------------------------
    // DeerFlow Workspace Shell & Sidebar
    // ------------------------------------------------------------------------------------------

    private fun buildRoot(): View {
        val frame = FrameLayout(activity).apply {
            setBackgroundColor(cSurface)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cSurface)
        }
        frame.addView(root, FrameLayout.LayoutParams(-1, -1))
        root.addView(buildTopBar())
        root.addView(View(activity).apply { setBackgroundColor(cOutline) }, lp(-1, 1))

        val body = FrameLayout(activity)
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))

        scroll = NestedScrollView(activity).apply {
            isFillViewport = true
            clipToPadding = false
            isVerticalScrollBarEnabled = false
        }
        messages = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(20))
        }
        scroll.addView(messages, ViewGroup.LayoutParams(-1, -2))
        body.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        thinkingRow = buildThinkingRow()

        emptyState = buildEmptyState()
        body.addView(emptyState, FrameLayout.LayoutParams(-1, -1))

        jumpButton = circleIconButton(R.drawable.ic_mtrl_arrow_down, cSurfaceCont, cOnSurface, 36) {
            stickToBottom = true
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }.apply {
            visibility = View.GONE
            elevation = dp(3).toFloat()
        }
        body.addView(jumpButton, FrameLayout.LayoutParams(dp(36), dp(36)).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(10)
        })

        scroll.setOnScrollChangeListener(NestedScrollView.OnScrollChangeListener { v, _, scrollY, _, _ ->
            val child = v.getChildAt(0) ?: return@OnScrollChangeListener
            val distance = child.height - (scrollY + v.height)
            stickToBottom = distance < dp(48)
            jumpButton.visibility = if (stickToBottom || renderedCount == 0) View.GONE else View.VISIBLE
        })

        root.addView(buildSlashPanel(), lp(-1, -2).apply {
            leftMargin = dp(14); rightMargin = dp(14); topMargin = dp(4)
        })

        configBanner = buildConfigBanner()
        root.addView(configBanner, lp(-1, -2).apply {
            leftMargin = dp(14); rightMargin = dp(14); topMargin = dp(6)
        })

        bottomComposerSlot = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        bottomComposerSlot.addView(buildPlanPanel(), lp(-1, -2).apply {
            leftMargin = dp(14); rightMargin = dp(14); bottomMargin = -dp(16)
        })
        composerBox = buildComposer()
        bottomComposerSlot.addView(composerBox, composerLp())
        root.addView(bottomComposerSlot, lp(-1, -2).apply { topMargin = dp(6) })

        val ui = pro.sketchware.ai.config.AiStrings.current(activity)
        tvDisclaimer = TextView(activity).apply {
            text = ui.disclaimerText
            textSize = 10.5f
            gravity = Gravity.CENTER
            setTextColor(cOnSurfaceVar)
            alpha = 0.7f
        }
        root.addView(tvDisclaimer, lp(-1, -2).apply { topMargin = dp(2); bottomMargin = dp(6) })

        // Sidebar overlay (scrim + sliding panel)
        sidebarScrim = View(activity).apply {
            setBackgroundColor(Color.argb(if (isDark) 150 else 90, 0, 0, 0))
            visibility = View.GONE
            alpha = 0f
            isClickable = true
            setOnClickListener { closeSidebar() }
        }
        frame.addView(sidebarScrim, FrameLayout.LayoutParams(-1, -1))
        sidebarPanel = buildSidebar()
        frame.addView(sidebarPanel, FrameLayout.LayoutParams(sidebarWidth(), -1, Gravity.START))
        return frame
    }

    private fun composerLp() = LinearLayout.LayoutParams(-1, -2).apply {
        leftMargin = dp(12); rightMargin = dp(12); bottomMargin = dp(4)
    }

    private fun sidebarWidth() = minOf(dp(300), (activity.resources.displayMetrics.widthPixels * 0.84f).toInt())

    private fun buildTopBar(): View {
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            minimumHeight = dp(54)
        }
        bar.addView(iconButton(R.drawable.ic_mtrl_sidebar, tx.workspace) { openSidebar() })

        val titleCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
            isClickable = true
            background = ripple(rounded(Color.TRANSPARENT, 10f))
            setOnClickListener { openSidebar() }
        }
        tvThreadTitle = TextView(activity).apply {
            text = tx.newChat
            textSize = 15.5f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(cOnSurface)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        titleCol.addView(tvThreadTitle)
        val subRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = View(activity).apply { background = oval(cSuccess) }
        subRow.addView(statusDot, lp(dp(6), dp(6)).apply { rightMargin = dp(6) })
        tvSubtitle = TextView(activity).apply {
            textSize = 11.5f
            setTextColor(cOnSurfaceVar)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        subRow.addView(tvSubtitle)
        titleCol.addView(subRow)
        bar.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f))

        // Artifacts button with live counter badge (DeerFlow "Show artifacts of this conversation")
        val artifactsBtn = FrameLayout(activity).apply {
            contentDescription = tx.artifacts
            isClickable = true
            background = RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(cOnSurface, 30)), null, null)
            addView(tintedIcon(R.drawable.ic_mtrl_package, cOnSurface, 21), FrameLayout.LayoutParams(dp(21), dp(21), Gravity.CENTER))
            setOnClickListener { showArtifactsSheet() }
        }
        tvArtifactsBadge = TextView(activity).apply {
            textSize = 9f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(cOnPrimary)
            gravity = Gravity.CENTER
            minWidth = dp(15)
            setPadding(dp(3), 0, dp(3), 0)
            background = rounded(cPrimary, 8f)
            visibility = View.GONE
        }
        artifactsBtn.addView(tvArtifactsBadge, FrameLayout.LayoutParams(-2, dp(15), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(7); rightMargin = dp(6)
        })
        bar.addView(artifactsBtn, lp(dp(44), dp(44)))
        bar.addView(iconButton(R.drawable.ic_mtrl_terminal, tx.terminal) { TerminalScreen.show(activity, scId) })
        bar.addView(iconButton(R.drawable.ic_mtrl_edit, tx.newChat) { confirmNewChat() })
        lateinit var more: View
        more = iconButton(R.drawable.ic_mtrl_more_vertical, "More") { showOverflow(more) }
        bar.addView(more)
        return bar
    }

    private fun buildConfigBanner(): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(8), dp(8))
            background = rounded(ColorUtils.setAlphaComponent(cWarning, 26), 12f, ColorUtils.setAlphaComponent(cWarning, 90))
            visibility = View.GONE
        }
        row.addView(tintedIcon(R.drawable.ic_mtrl_warning, cWarning, 16))
        row.addView(TextView(activity).apply {
            text = tx.modelNotConfigured
            textSize = 12.5f
            setTextColor(cOnSurface)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        row.addView(TextView(activity).apply {
            text = tx.configureNow
            textSize = 12.5f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(cOnSurface)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = ripple(rounded(Color.TRANSPARENT, 10f, cOutline))
            isClickable = true
            setOnClickListener { openModelSettings() }
        })
        return row
    }

    private fun buildSidebar(): LinearLayout {
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cSidebar)
            elevation = dp(12).toFloat()
            visibility = View.GONE
            isClickable = true
        }
        val brand = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(4), dp(8))
        }
        brand.addView(agentAvatar(30))
        val brandCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        brandCol.addView(TextView(activity).apply {
            text = "Sketchware Agent"
            textSize = 15.5f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(cOnSurface)
        })
        brand.addView(brandCol, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(10) })
        brand.addView(iconButton(R.drawable.ic_mtrl_sidebar, "Close sidebar") { closeSidebar() })
        panel.addView(brand)

        val newChat = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = ripple(rounded(cSurfaceCont, 12f, cOutline))
            isClickable = true
            setOnClickListener { closeSidebar(); confirmNewChat() }
        }
        newChat.addView(tintedIcon(R.drawable.ic_mtrl_edit, cOnSurface, 17))
        newChat.addView(TextView(activity).apply {
            text = tx.newChat
            textSize = 13.5f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(cOnSurface)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(10) })
        panel.addView(newChat, lp(-1, -2).apply { leftMargin = dp(12); rightMargin = dp(12); bottomMargin = dp(6) })

        val navScroll = NestedScrollView(activity).apply { isVerticalScrollBarEnabled = false }
        val nav = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(8))
        }
        navScroll.addView(nav, ViewGroup.LayoutParams(-1, -2))
        panel.addView(navScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        nav.addView(sidebarSection(tx.workspace))
        nav.addView(sidebarItem(R.drawable.ic_mtrl_chat, tx.chats, selected = true) { closeSidebar() })
        nav.addView(sidebarItem(R.drawable.ic_mtrl_package, tx.artifacts) { closeSidebar(); showArtifactsSheet() })
        nav.addView(sidebarItem(R.drawable.ic_mtrl_tune, tx.agentModes, trailing = "${modeTitle(config.autonomyMode)} · ${DeerFlowTheme.modeTag(config.autonomyMode)}") {
            closeSidebar(); showModePicker()
        })
        nav.addView(sidebarItem(R.drawable.ic_mtrl_star, tx.skills) { closeSidebar(); openSkills() })
        nav.addView(sidebarItem(R.drawable.ic_mtrl_puzzle, tx.plugins) { closeSidebar(); openPlugins() })
        nav.addView(sidebarItem(R.drawable.ic_mtrl_link, tx.mcp) { closeSidebar(); openMcp() })
        nav.addView(sidebarItem(R.drawable.ic_mtrl_terminal, tx.terminal) { closeSidebar(); TerminalScreen.show(activity, scId) })
        nav.addView(sidebarItem(R.drawable.ic_mtrl_history, tx.checkpoints) { closeSidebar(); showCheckpointsDialog() })

        nav.addView(sidebarSection(tx.recent), lp(-1, -2).apply { topMargin = dp(10) })
        sidebarRecentList = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        nav.addView(sidebarRecentList, lp(-1, -2))

        panel.addView(View(activity).apply { setBackgroundColor(cOutline) }, lp(-1, 1))
        val footer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(8))
        }
        footer.addView(sidebarItem(R.drawable.ic_mtrl_settings, tx.settings) {
            closeSidebar(); AiSettingsDialog.show(activity) { refreshHeader() }
        })
        footer.addView(sidebarItem(R.drawable.ic_mtrl_web, tx.language, trailing = config.agentLanguage.shortBadge) {
            closeSidebar()
            pro.sketchware.ai.config.AiStrings.showLanguagePickerDialog(activity) { rebuildSidebar(); refreshHeader() }
        })
        footer.addView(sidebarItem(R.drawable.ic_mtrl_exit, tx.closeStudio) { closeSidebar(); dialog.dismiss() })
        panel.addView(footer)
        return panel
    }

    private fun rebuildSidebar() {
        if (!::sidebarPanel.isInitialized) return
        val parent = sidebarPanel.parent as? FrameLayout ?: return
        val idx = parent.indexOfChild(sidebarPanel)
        parent.removeView(sidebarPanel)
        sidebarPanel = buildSidebar()
        parent.addView(sidebarPanel, idx, FrameLayout.LayoutParams(sidebarWidth(), -1, Gravity.START))
    }

    private fun sidebarSection(title: String): View = TextView(activity).apply {
        text = title
        textSize = 11.5f
        setTextColor(cOnSurfaceVar)
        setPadding(dp(10), dp(8), dp(10), dp(4))
    }

    private fun sidebarItem(
        iconRes: Int,
        label: String,
        selected: Boolean = false,
        trailing: String? = null,
        onClick: () -> Unit
    ): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = ripple(rounded(if (selected) cSidebarAccent else Color.TRANSPARENT, 10f))
            isClickable = true
            setOnClickListener { onClick() }
        }
        row.addView(tintedIcon(iconRes, if (selected) cOnSurface else cOnSurfaceVar, 18))
        row.addView(TextView(activity).apply {
            text = label
            textSize = 13.5f
            setTextColor(cOnSurface)
            if (selected) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
        if (!trailing.isNullOrBlank()) {
            row.addView(TextView(activity).apply {
                text = trailing
                textSize = 11f
                setTextColor(cOnSurfaceVar)
                setPadding(dp(7), dp(1), dp(7), dp(1))
                background = rounded(Color.TRANSPARENT, 8f, cOutline)
                maxLines = 1
            })
        }
        return row
    }

    private fun refreshSidebarRecent() {
        if (!::sidebarRecentList.isInitialized) return
        sidebarRecentList.removeAllViews()
        if (userMessageViews.isEmpty()) {
            sidebarRecentList.addView(TextView(activity).apply {
                text = tx.noRecent
                textSize = 12f
                setTextColor(cOnSurfaceVar)
                setPadding(dp(10), dp(6), dp(10), dp(6))
            })
            return
        }
        for ((text, view) in userMessageViews.asReversed().take(40)) {
            val row = TextView(activity).apply {
                this.text = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                textSize = 13f
                setTextColor(cOnSurface)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = ripple(rounded(Color.TRANSPARENT, 10f))
                isClickable = true
                setOnClickListener {
                    closeSidebar()
                    stickToBottom = false
                    scroll.post { scroll.smoothScrollTo(0, (view.top - dp(12)).coerceAtLeast(0)) }
                }
            }
            sidebarRecentList.addView(row, lp(-1, -2))
        }
    }

    private fun openSidebar() {
        if (sidebarOpen) return
        sidebarOpen = true
        hideKeyboard()
        refreshSidebarRecent()
        val w = sidebarWidth().toFloat()
        sidebarScrim.visibility = View.VISIBLE
        sidebarScrim.animate().alpha(1f).setDuration(180).start()
        sidebarPanel.visibility = View.VISIBLE
        sidebarPanel.translationX = -w
        sidebarPanel.animate().translationX(0f).setDuration(220)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    private fun closeSidebar() {
        if (!sidebarOpen) return
        sidebarOpen = false
        val w = sidebarWidth().toFloat()
        sidebarScrim.animate().alpha(0f).setDuration(160).withEndAction { sidebarScrim.visibility = View.GONE }.start()
        sidebarPanel.animate().translationX(-w).setDuration(180)
            .withEndAction { sidebarPanel.visibility = View.GONE }.start()
    }

    private fun openSkills() {
        SkillsPage(
            activity,
            onUseInChat = { promptText ->
                prefill(promptText)
                focusComposer()
            },
            onClosed = { refreshHeader() }
        ).show()
    }

    private fun openPlugins() {
        PluginsPage(activity) { refreshHeader() }.show()
    }

    private fun openMcp() {
        McpPage(activity) { refreshHeader() }.show()
    }

    private fun openModelSettings() {
        ModelSettingsPage(activity, onChanged = { refreshHeader() }, onClosed = { refreshHeader() }).show()
    }

    private fun showArtifactsSheet() {
        WorkspaceArtifactsManager.showArtifactsSheet(
            activity = activity,
            scId = scId,
            palette = deer,
            tx = tx,
            onAttachToChat = { addContextAttachment(it) }
        )
    }

    private fun refreshArtifactsBadge() {
        if (!::tvArtifactsBadge.isInitialized) return
        val count = try {
            WorkspaceArtifactsManager.collectArtifacts(scId).size
        } catch (_: Throwable) {
            0
        }
        if (count > 0) {
            tvArtifactsBadge.text = if (count > 99) "99+" else count.toString()
            tvArtifactsBadge.visibility = View.VISIBLE
        } else {
            tvArtifactsBadge.visibility = View.GONE
        }
    }

    // ------------------------------------------------------------------------------------------
    // DeerFlow Welcome Screen (AuroraText + Centered Input Box + Suggestion Chips)
    // ------------------------------------------------------------------------------------------

    private fun buildEmptyState(): View {
        val wrap = NestedScrollView(activity).apply {
            isFillViewport = true
            setBackgroundColor(cSurface)
            isVerticalScrollBarEnabled = false
        }
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(24), dp(8), dp(24))
            clipChildren = false
            clipToPadding = false
        }
        wrap.addView(col, ViewGroup.LayoutParams(-1, -1))

        val greetRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        tvGreetingEmoji = TextView(activity).apply {
            text = "👋"
            textSize = 26f
            includeFontPadding = false
        }
        greetRow.addView(tvGreetingEmoji)
        tvGreeting = TextView(activity).apply {
            textSize = 26f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(cOnSurface)
            includeFontPadding = false
            gravity = Gravity.CENTER
        }
        greetRow.addView(tvGreeting, lp(-2, -2).apply { leftMargin = dp(10) })
        col.addView(greetRow, lp(-1, -2).apply { leftMargin = dp(16); rightMargin = dp(16) })

        tvEmptySub = TextView(activity).apply {
            textSize = 13.5f
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.25f)
            setTextColor(cOnSurfaceVar)
        }
        col.addView(tvEmptySub, lp(-1, -2).apply {
            topMargin = dp(10); bottomMargin = dp(22); leftMargin = dp(20); rightMargin = dp(20)
        })

        welcomeComposerSlot = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        col.addView(welcomeComposerSlot, lp(-1, -2))

        // Suggestion chips (DeerFlow: Build · Fix · Design · Learn · Create ▾)
        suggestionTitleViews.clear()
        val chipsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
        }
        val chipsScroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            addView(chipsRow, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL))
        }
        val screen = activeScreenName ?: "main.xml"
        val chips = listOf(
            Triple(R.drawable.ic_mtrl_deployed_code, { tx.chipBuild },
                "Rancang dan bangun aplikasi lengkap di proyek ini beserta UI modern, variabel, komponen, logika blok di setiap tombol/event, lalu jalankan save_and_verify_compile sampai sukses."),
            Triple(R.drawable.ic_mtrl_bug_report, { tx.chipFix },
                "Periksa proyek ini, jalankan save_and_verify_compile, analisis jika ada compile error atau bug logika, dan perbaiki secara otomatis hingga build sukses."),
            Triple(R.drawable.ic_mtrl_palette, { tx.chipDesign },
                "Baca XML layar $screen saat ini, lalu desain ulang menjadi tampilan modern, rapi, dan proporsional tanpa merusak ID view yang sudah dipakai di blok logika."),
            Triple(R.drawable.ic_mtrl_article, { tx.chipLearn }, null)
        )
        for ((icon, label, prompt) in chips) {
            val chip = suggestionChip(icon, label()) {
                prefill(prompt ?: tx.promptLearn)
                focusComposer()
            }
            chipsRow.addView(chip, lp(-2, -2).apply { rightMargin = dp(8) })
        }
        lateinit var createChip: View
        createChip = suggestionChip(R.drawable.ic_mtrl_add_circle, tx.chipCreate + "  ▾") { showCreateMenu(createChip) }
        chipsRow.addView(createChip, lp(-2, -2))
        col.addView(chipsScroll, lp(-1, -2).apply { topMargin = dp(14) })

        refreshWelcomeTexts()
        return wrap
    }

    private fun suggestionChip(iconRes: Int, label: String, onClick: () -> Unit): View {
        val chip = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(7), dp(14), dp(7))
            background = ripple(rounded(Color.TRANSPARENT, 18f, cOutline))
            isClickable = true
            setOnClickListener { onClick() }
        }
        chip.addView(tintedIcon(iconRes, cOnSurfaceVar, 15))
        val tv = TextView(activity).apply {
            text = label
            textSize = 13f
            setTextColor(cOnSurface)
            maxLines = 1
        }
        suggestionTitleViews.add(tv)
        chip.addView(tv, lp(-2, -2).apply { leftMargin = dp(6) })
        return chip
    }

    private fun showCreateMenu(anchor: View) {
        val t = tx
        PopupMenu(activity, anchor).apply {
            menu.add(0, 1, 0, t.createScreen)
            menu.add(0, 2, 1, t.createLogic)
            menu.add(0, 3, 2, t.createLibrary)
            menu.add(0, 4, 3, t.createSkill)
            menu.add(0, 5, 4, "🧬 Clone Proyek dari File APK (REA)")
            setOnMenuItemClickListener {
                if (it.itemId == 5) {
                    ContextAttachmentPicker.showFileOrFolderPicker(
                        context = activity,
                        scId = scId,
                        mode = ContextAttachmentPicker.PickerMode.APK_ONLY
                    ) { item ->
                        addContextAttachment(item)
                        prefill("Lakukan Full Clone & Reverse Engineering (REA) pada file APK yang saya lampirkan ini ke proyek Sketchware ini. Dekompilasi seluruh layout XML menjadi layar Activity & CustomView, impor semua drawable/asset/font/permission, analisis class & method DEX-nya, rekonstruksi penuh seluruh blok logika & custom Java di setiap layar, dan jalankan save_and_verify_compile hingga BUILD SUKSES!")
                        focusComposer()
                    }
                    return@setOnMenuItemClickListener true
                }
                prefill(
                    when (it.itemId) {
                        1 -> t.promptCreateScreen
                        2 -> t.promptCreateLogic
                        3 -> t.promptCreateLibrary
                        else -> t.promptCreateSkill
                    }
                )
                focusComposer()
                true
            }
        }.show()
    }

    private fun refreshWelcomeTexts() {
        if (!::tvGreeting.isInitialized) return
        val t = tx
        val ultra = config.autonomyMode == AgentAutonomyMode.AUTOPILOT
        tvGreetingEmoji.text = if (ultra) "🚀" else "👋"
        tvGreeting.text = if (ultra) t.greetingUltra else t.greeting
        tvEmptySub.text = "${t.welcomeDesc}\n\n${t.projectLabel} #$scId" + (activeScreenName?.let { " · $it" } ?: "")
        if (suggestionTitleViews.size >= 5) {
            suggestionTitleViews[0].text = t.chipBuild
            suggestionTitleViews[1].text = t.chipFix
            suggestionTitleViews[2].text = t.chipDesign
            suggestionTitleViews[3].text = t.chipLearn
            suggestionTitleViews[4].text = t.chipCreate + "  ▾"
        }
        applyAurora(
            tvGreeting,
            if (ultra) intArrayOf(deer.goldPale, deer.goldLight, deer.gold, deer.goldLight)
            else intArrayOf(cOnSurface, ColorUtils.blendARGB(cOnSurface, cOnSurfaceVar, 0.55f), cOnSurface)
        )
    }

    private fun applyAurora(tv: TextView, colors: IntArray) {
        auroraAnimator?.cancel()
        tv.post {
            val w = tv.paint.measureText(tv.text.toString()).coerceAtLeast(1f)
            val shader = android.graphics.LinearGradient(
                0f, 0f, w, 0f, colors + colors.first(), null, android.graphics.Shader.TileMode.MIRROR
            )
            val matrix = android.graphics.Matrix()
            tv.paint.shader = shader
            tv.invalidate()
            auroraAnimator = ValueAnimator.ofFloat(0f, w * 2f).apply {
                duration = 6000
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                addUpdateListener { a ->
                    matrix.setTranslate(a.animatedValue as Float, 0f)
                    shader.setLocalMatrix(matrix)
                    tv.invalidate()
                }
                start()
            }
        }
        if (::tvGreetingEmoji.isInitialized) {
            tvGreetingEmoji.pivotX = tvGreetingEmoji.width * 0.7f
            tvGreetingEmoji.pivotY = tvGreetingEmoji.height * 0.9f
            ObjectAnimator.ofFloat(tvGreetingEmoji, "rotation", 0f, 20f, 0f, 20f, 0f).apply {
                duration = 1200
                startDelay = 250
                start()
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Streaming Indicator & Stacked To-dos Card
    // ------------------------------------------------------------------------------------------

    private fun buildThinkingRow(): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(10), 0, dp(4))
            visibility = View.GONE
        }
        val dots = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        dotAnimators.clear()
        repeat(3) { i ->
            val dot = View(activity).apply { background = oval(cOnSurface) }
            dots.addView(dot, lp(dp(6), dp(6)).apply { if (i > 0) leftMargin = dp(4) })
            dotAnimators.add(ObjectAnimator.ofFloat(dot, "translationY", 0f, -dp(5).toFloat()).apply {
                duration = 450
                startDelay = i * 150L
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
            })
        }
        row.addView(dots, lp(-2, dp(14)))
        tvThinking = TextView(activity).apply {
            text = "Berpikir…"
            textSize = 13f
            setTextColor(cOnSurfaceVar)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        row.addView(tvThinking, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
        tvThinkingStep = TextView(activity).apply {
            textSize = 11f
            setTextColor(cOnSurfaceVar)
            typeface = Typeface.MONOSPACE
            setPadding(dp(7), dp(1), dp(7), dp(1))
            background = rounded(Color.TRANSPARENT, 8f, cOutline)
        }
        row.addView(tvThinkingStep, lp(-2, -2).apply { leftMargin = dp(8) })
        pulse = ObjectAnimator.ofFloat(tvThinking, "alpha", 1f, 0.45f).apply {
            duration = 900
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
        }
        return row
    }

    private fun buildPlanPanel(): View {
        planPanel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                val r = dp(16f).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setColor(cSurfaceLow)
                setStroke(maxOf(1, dp(1f)), cOutline)
            }
            setPadding(0, 0, 0, dp(16))
            visibility = View.GONE
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(9), dp(8), dp(7))
            background = ripple(null)
            isClickable = true
            setOnClickListener {
                planExpanded = !planExpanded
                applyPlanExpansion()
            }
        }
        header.addView(tintedIcon(R.drawable.ic_mtrl_checklist, cOnSurface, 16))
        val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        planHeaderTitle = TextView(activity).apply {
            textSize = 12.5f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(cOnSurface)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        planHeaderSub = TextView(activity).apply {
            textSize = 11.5f
            setTextColor(cOnSurfaceVar)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        col.addView(planHeaderTitle)
        col.addView(planHeaderSub)
        header.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(10) })
        planChevron = tintedIcon(R.drawable.ic_mtrl_chevron_right_24, cOnSurfaceVar, 18).apply { rotation = -90f }
        header.addView(planChevron)
        planPanel.addView(header)

        planProgress = LinearProgressIndicator(activity).apply {
            isIndeterminate = false
            max = 100
            trackThickness = dp(2)
            trackCornerRadius = dp(1)
            setIndicatorColor(cOnSurface)
            trackColor = cSurfaceHigh
        }
        planPanel.addView(planProgress, lp(-1, -2).apply { leftMargin = dp(14); rightMargin = dp(14); bottomMargin = dp(2) })

        planListScroll = NestedScrollView(activity).apply {
            visibility = View.GONE
            isNestedScrollingEnabled = true
        }
        planList = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(6))
        }
        planListScroll.addView(planList, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        planPanel.addView(planListScroll, lp(-1, 0))
        return planPanel
    }

    private fun applyPlanExpansion() {
        if (!::planListScroll.isInitialized) return
        if (planExpanded) {
            planChevron.animate().rotation(90f).setDuration(150).start()
            planListScroll.visibility = View.VISIBLE
            planList.post {
                if (!planExpanded) return@post
                val cap = (activity.resources.displayMetrics.heightPixels * 0.28f).toInt()
                val widthAvail = (planPanel.width.takeIf { it > 0 }
                    ?: (activity.resources.displayMetrics.widthPixels - dp(24)))
                val wSpec = View.MeasureSpec.makeMeasureSpec(widthAvail, View.MeasureSpec.EXACTLY)
                val hSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                planList.measure(wSpec, hSpec)
                val measuredH = planList.measuredHeight
                val p = planListScroll.layoutParams
                p.height = if (measuredH > cap) cap else ViewGroup.LayoutParams.WRAP_CONTENT
                planListScroll.layoutParams = p
                planPanel.requestLayout()
            }
        } else {
            planChevron.animate().rotation(-90f).setDuration(150).start()
            val p = planListScroll.layoutParams
            p.height = 0
            planListScroll.layoutParams = p
            planListScroll.visibility = View.GONE
            planPanel.requestLayout()
        }
    }

    // ------------------------------------------------------------------------------------------
    // Slash Commands & Floating Input Box
    // ------------------------------------------------------------------------------------------

    private data class SlashCommandItem(
        val command: String,
        val title: String,
        val subtitle: String,
        val iconRes: Int,
        val prompt: String
    )

    private fun slashCommands(): List<SlashCommandItem> {
        val screen = activeScreenName ?: "main.xml"
        val base = listOf(
            SlashCommandItem(
                command = "/cek-error",
                title = "Cek & Perbaiki Error (Self-Heal)",
                subtitle = "Compile proyek, analisis error/bug & perbaiki otomatis hingga build sukses",
                iconRes = R.drawable.ic_mtrl_bug_report,
                prompt = "Periksa proyek ini, jalankan save_and_verify_compile, analisis jika ada compile error atau bug logika, dan perbaiki secara otomatis hingga build sukses."
            ),
            SlashCommandItem(
                command = "/bangun-apk",
                title = "Bangun Aplikasi dari 0 → APK",
                subtitle = "Rancang UI modern, variabel, komponen, logika blok & compile APK sampai sukses",
                iconRes = R.drawable.ic_mtrl_deployed_code,
                prompt = "Rancang dan bangun aplikasi lengkap di proyek ini beserta UI modern, variabel, komponen, logika blok di setiap tombol/event, lalu jalankan save_and_verify_compile sampai sukses."
            ),
            SlashCommandItem(
                command = "/percantik-ui",
                title = "Percantik UI Layar Ini ($screen)",
                subtitle = "Desain ulang XML layar saat ini agar modern & rapi tanpa merusak ID view",
                iconRes = R.drawable.ic_mtrl_palette,
                prompt = "Baca XML layar $screen saat ini, lalu desain ulang menjadi tampilan modern, rapi, dan proporsional tanpa merusak ID view yang sudah dipakai di blok logika."
            ),
            SlashCommandItem(
                command = "/subagent-audit",
                title = "Delegasi Sub-Agent: Audit Arsitektur & Blok",
                subtitle = "Jalankan sub-agent terisolasi (DeerFlow Ultra) untuk mengaudit layar & logika",
                iconRes = R.drawable.ic_mtrl_team,
                prompt = "Gunakan delegate_subagent(role=\"block_auditor\", task=\"Periksa seluruh layar, komponen, dan blok logika di proyek ini, lalu buat laporan temuan & rekomendasi arsitektur\"), kemudian simpan laporannya ke panel Artifacts menggunakan manage_artifacts(action=\"write\", file_name=\"audit_report.md\")."
            ),
            SlashCommandItem(
                command = "/audit",
                title = "Audit & Jelaskan Proyek",
                subtitle = "Baca seluruh layar/event, jelaskan arsitektur & berikan saran pengembangan",
                iconRes = R.drawable.ic_mtrl_article,
                prompt = "Baca ringkasan proyek dan seluruh layar/event, lalu berikan penjelasan lengkap arsitektur proyek ini beserta saran pengembangan."
            ),
            SlashCommandItem(
                command = "/fix-library",
                title = "Periksa & Perbaiki Local Library",
                subtitle = "Cek library lokal yang hilang/korup (.jar/.dex/res) & perbaiki otomatis",
                iconRes = R.drawable.ic_mtrl_folder_code,
                prompt = "Periksa seluruh local library di proyek ini menggunakan manage_local_libraries, perbaiki resource korup atau nonaktifkan library yang file JAR/DEX-nya hilang, buat class stub pengganti jika diperlukan, lalu jalankan save_and_verify_compile sampai sukses."
            ),
            SlashCommandItem(
                command = "/cek-crash",
                title = "Cek Log Crash & Runtime Error",
                subtitle = "Inspeksi logcat runtime & perbaiki penyebab force close / bug",
                iconRes = R.drawable.ic_mtrl_terminal,
                prompt = "Periksa log runtime dan crash terbaru proyek ini menggunakan inspect_runtime_logs, analisis penyebab crash atau bug saat aplikasi berjalan, lalu perbaiki blok logika/kode dan compile ulang."
            ),
            SlashCommandItem(
                command = "/lanjutkan",
                title = "Lanjutkan Rencana Tugas",
                subtitle = "Teruskan pengerjaan dari langkah terakhir di Long-Horizon Plan",
                iconRes = R.drawable.ic_mtrl_circle_play,
                prompt = "Lanjutkan pengerjaan dari langkah terakhir berdasarkan Long-Horizon Plan hingga semua tugas selesai dan save_and_verify_compile sukses."
            ),
            SlashCommandItem(
                command = "/terminal-linux",
                title = "Cek & Bootstrap Linux Debian",
                subtitle = "Pastikan Debian + apt/curl/git/python3/pip siap untuk akses keluar penuh",
                iconRes = R.drawable.ic_mtrl_terminal,
                prompt = "Periksa status Linux userland dengan manage_linux_env(action=\"status\"), pastikan distro 'debian' aktif (gunakan action=\"bootstrap\" bila perlu), lalu verifikasi koneksi keluar dan tool (curl, git, python3, pip) menggunakan run_shell."
            )
        )
        val pluginCmds = try {
            pro.sketchware.ai.extensions.PluginManager.getActivePluginSlashCommands().map { pc ->
                SlashCommandItem(
                    command = pc.command,
                    title = pc.title,
                    subtitle = pc.subtitle,
                    iconRes = R.drawable.ic_mtrl_folder_code,
                    prompt = pc.prompt
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
        val skillCmds = try {
            pro.sketchware.ai.extensions.SkillManager.listEnabledSkills().map { sk ->
                SlashCommandItem(
                    command = "/skill:${sk.id}",
                    title = "Skill: ${sk.name}",
                    subtitle = sk.description,
                    iconRes = R.drawable.ic_mtrl_star,
                    prompt = "Gunakan skill '${sk.id}' (panggil use_skill(action=\"read\", skill_id=\"${sk.id}\")) untuk menyelesaikan tugas di proyek ini."
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
        return base + pluginCmds + skillCmds
    }

    private fun buildSlashPanel(): View {
        slashPanel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cSurfaceCont, 16f, cOutline)
            elevation = dp(4).toFloat()
            visibility = View.GONE
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(10), dp(6))
        }
        header.addView(TextView(activity).apply {
            text = "Prompt Cepat · Slash Commands"
            textSize = 11.5f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(cOnSurfaceVar)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(activity).apply {
            text = "Tutup"
            textSize = 11.5f
            setTextColor(cPrimary)
            setPadding(dp(8), dp(2), dp(8), dp(2))
            background = ripple(rounded(Color.TRANSPARENT, 8f))
            isClickable = true
            setOnClickListener {
                if (etPrompt.text?.toString()?.trim() == "/") {
                    etPrompt.setText("")
                } else {
                    hideSlashPanel()
                }
            }
        })
        slashPanel.addView(header)
        slashPanel.addView(View(activity).apply { setBackgroundColor(cOutline) }, lp(-1, 1))

        slashScroll = NestedScrollView(activity).apply {
            isNestedScrollingEnabled = true
        }
        slashList = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(6))
        }
        slashScroll.addView(slashList, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        slashPanel.addView(slashScroll, lp(-1, -2))
        return slashPanel
    }

    private fun hideSlashPanel() {
        if (!::slashPanel.isInitialized) return
        val p = slashScroll.layoutParams
        p.height = 0
        slashScroll.layoutParams = p
        slashPanel.visibility = View.GONE
    }

    private fun updateSlashSuggestions(rawText: String, forceShowAll: Boolean = false) {
        if (!::slashPanel.isInitialized) return
        val trimmed = rawText.trimStart()
        if (!forceShowAll && (!trimmed.startsWith("/") || trimmed.contains('\n'))) {
            hideSlashPanel()
            return
        }
        val query = if (forceShowAll) "" else trimmed.removePrefix("/").trim().lowercase()
        val matches = slashCommands().filter { item ->
            query.isEmpty() ||
                item.command.lowercase().contains(query) ||
                item.title.lowercase().contains(query) ||
                item.subtitle.lowercase().contains(query)
        }
        if (matches.isEmpty()) {
            hideSlashPanel()
            return
        }
        slashList.removeAllViews()
        for (item in matches) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(9), dp(10), dp(9))
                background = ripple(rounded(Color.TRANSPARENT, 10f))
                isClickable = true
                setOnClickListener {
                    hideSlashPanel()
                    prefill(item.prompt)
                    focusComposer()
                }
            }
            row.addView(tintedIcon(item.iconRes, cPrimary, 18))
            val col = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
            }
            val topRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            topRow.addView(TextView(activity).apply {
                text = item.title
                textSize = 13.5f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(cOnSurface)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1f))
            topRow.addView(TextView(activity).apply {
                text = item.command
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextColor(cPrimary)
                setPadding(dp(6), dp(1), dp(6), dp(1))
                background = rounded(cSurfaceHigh, 6f)
            }, lp(-2, -2).apply { leftMargin = dp(8) })
            col.addView(topRow)
            col.addView(TextView(activity).apply {
                text = item.subtitle
                textSize = 11.5f
                setTextColor(cOnSurfaceVar)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, lp(-1, -2).apply { topMargin = dp(2) })
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(10) })
            slashList.addView(row, lp(-1, -2))
        }
        slashPanel.visibility = View.VISIBLE
        slashList.post {
            if (slashPanel.visibility != View.VISIBLE) return@post
            val cap = (activity.resources.displayMetrics.heightPixels * 0.32f).toInt()
            val widthAvail = (slashPanel.width.takeIf { it > 0 }
                ?: (activity.resources.displayMetrics.widthPixels - dp(24)))
            val wSpec = View.MeasureSpec.makeMeasureSpec(widthAvail, View.MeasureSpec.EXACTLY)
            val hSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            slashList.measure(wSpec, hSpec)
            val measuredH = slashList.measuredHeight
            val p = slashScroll.layoutParams
            p.height = if (measuredH > cap) cap else ViewGroup.LayoutParams.WRAP_CONTENT
            slashScroll.layoutParams = p
            slashPanel.requestLayout()
        }
    }

    /**
     * DeerFlow-style floating input box:
     *  [attachments]
     *  textarea ("How can I assist you today?")
     *  [+ add context] [mode ▾ (Flash/Pro/Ultra)] [model ▾] ............ [● send]
     */
    private fun buildComposer(): View {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cSurfaceCont, 22f, ColorUtils.blendARGB(cOutline, cOnSurfaceVar, 0.18f))
            elevation = dp(if (isDark) 0 else 3).toFloat()
            setPadding(dp(12), dp(6), dp(8), dp(8))
        }

        attachmentsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(4), dp(2), dp(2))
        }
        attachmentsScroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            addView(attachmentsRow)
        }
        box.addView(attachmentsScroll, lp(-1, -2))

        etPrompt = EditText(activity).apply {
            background = null
            hint = pro.sketchware.ai.config.AiStrings.current(activity).composerHint
            textSize = 15f
            setTextColor(cOnSurface)
            setHintTextColor(ColorUtils.setAlphaComponent(cOnSurfaceVar, 190))
            minLines = 2
            maxLines = 7
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(4), dp(10), dp(4), dp(6))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    updateSendButton()
                    updateSlashSuggestions(s?.toString().orEmpty())
                }
            })
        }
        box.addView(etPrompt, lp(-1, -2))

        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // Bottom-left "+" (Add Context): File, Folder, APK, Image, Quick Prompt, Agent Mode
        btnAddContext = FrameLayout(activity).apply {
            isClickable = true
            contentDescription = "Add Context"
            ViewCompat.setTooltipText(this, "Add Context")
            background = ripple(GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(maxOf(1, dp(1f)), cOutline)
            })
            addView(tintedIcon(R.drawable.ic_mtrl_add, cOnSurface, 18), FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
            setOnClickListener { showAddContextSheet() }
        }
        actions.addView(btnAddContext, lp(dp(32), dp(32)).apply { rightMargin = dp(6) })

        tvModePill = ghostPill(R.drawable.ic_mtrl_sprint) { showModePicker() }
        tvModelPill = ghostPill(R.drawable.ic_mtrl_bulb) { openModelSettings() }
        val pills = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        pills.addView(tvModePill)
        pills.addView(tvModelPill, lp(-2, -2).apply { leftMargin = dp(2) })
        val pillScroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(pills)
        }
        actions.addView(pillScroll, LinearLayout.LayoutParams(0, -2, 1f))

        ivSend = tintedIcon(R.drawable.ic_mtrl_arrow_up, cOnPrimary, 18)
        btnSend = FrameLayout(activity).apply {
            isClickable = true
            contentDescription = "Kirim"
            addView(ivSend, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
            setOnClickListener {
                if (isRunning) AgentOrchestrator.cancelRun() else submit(etPrompt.text?.toString().orEmpty())
            }
        }
        actions.addView(btnSend, lp(dp(34), dp(34)).apply { leftMargin = dp(8) })
        box.addView(actions, lp(-1, -2))
        updateSendButton()
        return box
    }

    private fun ghostPill(iconRes: Int, onClick: () -> Unit): TextView = TextView(activity).apply {
        textSize = 12.5f
        setTextColor(cOnSurfaceVar)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        maxWidth = dp(165)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(6), dp(8), dp(6))
        compoundDrawablePadding = dp(5)
        val d = activity.getDrawable(iconRes)?.mutate()
        d?.setTint(cOnSurfaceVar)
        d?.setBounds(0, 0, dp(15), dp(15))
        val chev = activity.getDrawable(R.drawable.ic_mtrl_chevron_right_24)?.mutate()
        chev?.setTint(cOnSurfaceVar)
        chev?.setBounds(0, 0, dp(13), dp(13))
        setCompoundDrawables(d, null, chev, null)
        background = ripple(rounded(Color.TRANSPARENT, 14f))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun modeIcon(mode: AgentAutonomyMode) = when (mode) {
        AgentAutonomyMode.ASK -> R.drawable.ic_mtrl_help
        AgentAutonomyMode.COPILOT -> R.drawable.ic_mtrl_sprint
        AgentAutonomyMode.AGENT -> R.drawable.ic_mtrl_bulb
        AgentAutonomyMode.AUTOPILOT -> R.drawable.ic_mtrl_deployed_code
        AgentAutonomyMode.TUTOR -> R.drawable.ic_mtrl_article
    }

    private fun refreshModePill() {
        if (!::tvModePill.isInitialized) return
        val mode = config.autonomyMode
        val ultra = mode == AgentAutonomyMode.AUTOPILOT
        val color = if (ultra) cGold else cOnSurfaceVar
        tvModePill.text = "${modeTitle(mode)} · ${DeerFlowTheme.modeTag(mode)}"
        tvModePill.setTextColor(color)
        if (ultra) tvModePill.setTypeface(Typeface.DEFAULT, Typeface.BOLD) else tvModePill.typeface = Typeface.DEFAULT
        val d = activity.getDrawable(modeIcon(mode))?.mutate()
        d?.setTint(color)
        d?.setBounds(0, 0, dp(15), dp(15))
        val chev = activity.getDrawable(R.drawable.ic_mtrl_chevron_right_24)?.mutate()
        chev?.setTint(color)
        chev?.setBounds(0, 0, dp(13), dp(13))
        tvModePill.setCompoundDrawables(d, null, chev, null)
    }

    private fun placeComposer(welcome: Boolean) {
        if (!::composerBox.isInitialized || !::welcomeComposerSlot.isInitialized) return
        val target = if (welcome) welcomeComposerSlot else bottomComposerSlot
        if (composerBox.parent === target) return
        (composerBox.parent as? ViewGroup)?.removeView(composerBox)
        target.addView(composerBox, composerLp())
        if (::tvDisclaimer.isInitialized) tvDisclaimer.visibility = if (welcome) View.GONE else View.VISIBLE
    }

    private fun addContextAttachment(item: AttachedContextItem) {
        attachedContexts.removeAll { it.absolutePath == item.absolutePath }
        attachedContexts.add(item)
        renderAttachedContextChips()
        updateSendButton()
        refreshHeader()
    }

    private fun renderAttachedContextChips() {
        if (!::attachmentsRow.isInitialized) return
        attachmentsRow.removeAllViews()
        if (attachedContexts.isEmpty()) {
            attachmentsScroll.visibility = View.GONE
            return
        }
        attachmentsScroll.visibility = View.VISIBLE
        for (item in attachedContexts) {
            val chip = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(cSurfaceHigh, 14f, cOutline)
                setPadding(dp(10), dp(5), dp(8), dp(5))
            }
            chip.addView(tintedIcon(item.iconRes(), cPrimary, 15))
            chip.addView(TextView(activity).apply {
                text = "${item.displayName} (${item.badge})"
                textSize = 11.5f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(cOnSurface)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
                maxWidth = dp(200)
            }, lp(-2, -2).apply { leftMargin = dp(6); rightMargin = dp(6) })
            val btnRemove = tintedIcon(R.drawable.ic_mtrl_close, cOnSurfaceVar, 15).apply {
                isClickable = true
                setOnClickListener {
                    attachedContexts.remove(item)
                    renderAttachedContextChips()
                    updateSendButton()
                    refreshHeader()
                }
            }
            chip.addView(btnRemove)
            attachmentsRow.addView(chip, lp(-2, -2).apply { rightMargin = dp(6) })
        }
    }

    // ------------------------------------------------------------------------------------------
    // Conversation Rendering
    // ------------------------------------------------------------------------------------------

    private fun loadHistory() {
        messages.removeAllViews()
        renderedCount = 0
        currentGroup = null
        allGroups.clear()
        userMessageViews.clear()
        val entries = AgentMemoryStore.loadChatEntries(scId)
        val start = (entries.size - historyLimit).coerceAtLeast(0)
        if (start > 0) {
            val more = TextView(activity).apply {
                text = "Tampilkan $start pesan sebelumnya"
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(cPrimary)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = ripple(rounded(cSurfaceLow, 16f, cOutline))
                isClickable = true
            }
            more.setOnClickListener { loadAll(entries) }
            messages.addView(more, lp(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(8) })
        }
        for (i in start until entries.size) renderEntry(entries[i], live = false)
        allGroups.dropLast(1).forEach { it.setExpanded(false) }
        ensureThinkingRowLast()
        updateEmptyState()
        refreshArtifactsBadge()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun loadAll(entries: List<AgentChatEntry>) {
        messages.removeAllViews()
        renderedCount = 0
        currentGroup = null
        allGroups.clear()
        userMessageViews.clear()
        entries.forEach { renderEntry(it, live = false) }
        allGroups.dropLast(1).forEach { it.setExpanded(false) }
        ensureThinkingRowLast()
        updateEmptyState()
    }

    private fun renderEntry(entry: AgentChatEntry, live: Boolean) {
        when (entry.type) {
            ChatEntryType.TOOL_CALL -> {
                val group = currentGroup ?: newGroup()
                val tName = toolNameOf(entry.title, "Tool Call:")
                group.add(ToolStep(tName, entry.body))
            }

            ChatEntryType.TOOL_RESULT -> {
                val name = toolNameOf(entry.title, "Result:")
                val pending = currentGroup?.findPending(name)
                if (pending != null) {
                    pending.complete(entry.body, looksLikeError(entry.body))
                    currentGroup?.refreshHeader()
                } else {
                    val group = currentGroup ?: newGroup()
                    val step = ToolStep(entry.title.replace("??", "").trim(), "")
                    group.add(step)
                    step.complete(entry.body, looksLikeError(entry.body))
                    group.refreshHeader()
                }
                if (live) refreshArtifactsBadge()
            }

            ChatEntryType.USER -> {
                closeGroup(live)
                val v = userBubble(entry.body)
                addMessageView(v)
                val cleanLine = entry.body.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                if (cleanLine.isNotEmpty()) {
                    userMessageViews.add(cleanLine to v)
                    if (userMessageViews.size == 1 && ::tvThreadTitle.isInitialized) {
                        tvThreadTitle.text = cleanLine
                    }
                }
            }

            ChatEntryType.ASSISTANT -> {
                if (entry.body.isBlank()) return
                closeGroup(live)
                addMessageView(assistantMessage(entry))
            }

            ChatEntryType.STATUS -> {
                closeGroup(live)
                addMessageView(statusNote(entry))
            }

            ChatEntryType.ERROR -> {
                if (entry.title.contains("[") && entry.title.contains("]")) {
                    val group = currentGroup ?: newGroup()
                    val step = ToolStep(entry.title.replace("??", "").trim(), "")
                    group.add(step)
                    step.complete(entry.body, true)
                    group.refreshHeader()
                } else {
                    closeGroup(live)
                    addMessageView(errorCard(entry))
                }
            }
        }
        ensureThinkingRowLast()
        updateEmptyState()
    }

    private fun newGroup(): ToolGroup {
        val g = ToolGroup()
        currentGroup = g
        allGroups.add(g)
        addMessageView(g.root)
        return g
    }

    private fun closeGroup(live: Boolean) {
        val g = currentGroup ?: return
        currentGroup = null
        if (live && g.size > 4 && g.errorCount == 0) g.setExpanded(false)
    }

    private fun addMessageView(v: View) {
        messages.addView(v, lp(-1, -2).apply { bottomMargin = dp(14) })
        renderedCount++
    }

    private fun ensureThinkingRowLast() {
        (thinkingRow.parent as? ViewGroup)?.removeView(thinkingRow)
        messages.addView(thinkingRow, lp(-1, -2))
    }

    private fun updateEmptyState() {
        val empty = renderedCount == 0 && !isRunning
        emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        placeComposer(welcome = empty)
        if (empty && ::tvThreadTitle.isInitialized) {
            tvThreadTitle.text = tx.newChat
        }
    }

    private fun userBubble(text: String): View {
        val displayBody = text.substringBefore("\n\n---Perhatian Agent:").trim()
        val wrap = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        val tv = TextView(activity).apply {
            this.text = displayBody
            textSize = 15f
            setTextColor(cOnSurface)
            setLineSpacing(0f, 1.15f)
            setTextIsSelectable(true)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(cSurfaceHigh, 18f, cOutline)
            maxWidth = (activity.resources.displayMetrics.widthPixels * 0.84f).toInt()
        }
        wrap.addView(tv, lp(-2, -2))
        return wrap
    }

    private fun assistantMessage(entry: AgentChatEntry): View {
        val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(agentAvatar(22))
        head.addView(TextView(activity).apply {
            text = "Sketchware Agent"
            textSize = 13f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(cOnSurface)
        }, lp(-2, -2).apply { leftMargin = dp(8) })
        Regex("Step\\s*(\\d+)").find(entry.title)?.let { m ->
            head.addView(TextView(activity).apply {
                text = "· ${tx.stepsThinking} ${m.groupValues[1]}"
                textSize = 11.5f
                setTextColor(cOnSurfaceVar)
            }, lp(-2, -2).apply { leftMargin = dp(6) })
        }
        col.addView(head)
        col.addView(markdownView(entry.body), lp(-1, -2).apply { topMargin = dp(6) })

        val actions = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(smallAction(R.drawable.ic_mtrl_file, tx.copy) { copy(entry.body, "Respons disalin") })
        col.addView(actions, lp(-2, -2).apply { topMargin = dp(2) })
        return col
    }

    private fun statusNote(entry: AgentChatEntry): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(dp(2), 0, 0, 0)
        }
        row.addView(tintedIcon(R.drawable.ic_mtrl_info, cOnSurfaceVar, 15), lp(dp(15), dp(15)).apply { topMargin = dp(2) })
        val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(activity).apply {
            text = cleanTitle(entry.title)
            textSize = 12.5f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(cOnSurfaceVar)
        })
        if (entry.body.isNotBlank()) {
            col.addView(TextView(activity).apply {
                text = entry.body
                textSize = 12f
                setTextColor(cOnSurfaceVar)
                alpha = 0.85f
                setTextIsSelectable(true)
            })
        }
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        return row
    }

    private fun errorCard(entry: AgentChatEntry): View {
        val ui = pro.sketchware.ai.config.AiStrings.current(activity)
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(ColorUtils.setAlphaComponent(cError, 22), 14f, ColorUtils.setAlphaComponent(cError, 90))
        }
        val head = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(tintedIcon(R.drawable.ic_mtrl_warning, cError, 18))
        head.addView(TextView(activity).apply {
            text = cleanTitle(entry.title)
            textSize = 13.5f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(cError)
        }, lp(-2, -2).apply { leftMargin = dp(8) })
        card.addView(head)
        card.addView(TextView(activity).apply {
            text = entry.body
            textSize = 12.5f
            setTextColor(cOnSurface)
            setTextIsSelectable(true)
        }, lp(-1, -2).apply { topMargin = dp(6) })
        val retry = TextView(activity).apply {
            text = ui.continueLastStepText
            textSize = 12.5f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(cError)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = ripple(rounded(Color.TRANSPARENT, 16f, ColorUtils.setAlphaComponent(cError, 120)))
            isClickable = true
            setOnClickListener { submit("Lanjutkan") }
        }
        card.addView(retry, lp(-2, -2).apply { topMargin = dp(10) })
        return card
    }

    // ------------------------------------------------------------------------------------------
    // DeerFlow Tool Step Visualizer (with Sub-Agent & Skill Badges)
    // ------------------------------------------------------------------------------------------

    private inner class ToolGroup {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cSurfaceLow, 14f, cOutline)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        private val steps = mutableListOf<ToolStep>()
        private val headerText: TextView
        private val headerIcon: ImageView
        private val chevron: ImageView
        private val body: LinearLayout
        private val bodyRow: LinearLayout
        private var expanded = true
        val size get() = steps.size
        val errorCount get() = steps.count { it.isError }

        init {
            val header = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(2), dp(4), dp(4), dp(4))
                background = ripple(null)
                isClickable = true
                setOnClickListener { setExpanded(!expanded) }
            }
            headerIcon = tintedIcon(R.drawable.ic_mtrl_terminal, cOnSurfaceVar, 16)
            header.addView(headerIcon)
            headerText = TextView(activity).apply {
                textSize = 12.5f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(cOnSurfaceVar)
            }
            header.addView(headerText, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
            chevron = tintedIcon(R.drawable.ic_mtrl_chevron_right_24, cOnSurfaceVar, 16).apply { rotation = 90f }
            header.addView(chevron, lp(dp(16), dp(16)).apply { leftMargin = dp(2) })
            root.addView(header)

            val bodyRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            bodyRow.addView(View(activity).apply { setBackgroundColor(cOutline) },
                LinearLayout.LayoutParams(dp(2), ViewGroup.LayoutParams.MATCH_PARENT).apply { leftMargin = dp(7); topMargin = dp(4); bottomMargin = dp(4) })
            body = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(2), 0, dp(2))
            }
            bodyRow.addView(body, LinearLayout.LayoutParams(0, -2, 1f))
            root.addView(bodyRow)
            this.bodyRow = bodyRow
            refreshHeader()
        }

        fun add(step: ToolStep) {
            steps.add(step)
            body.addView(step.root, lp(-1, -2))
            refreshHeader()
        }

        fun findPending(name: String): ToolStep? =
            steps.lastOrNull { !it.done && it.toolName == name } ?: steps.lastOrNull { !it.done }

        fun setExpanded(value: Boolean) {
            expanded = value
            bodyRow.visibility = if (value) View.VISIBLE else View.GONE
            chevron.rotation = if (value) 90f else 0f
        }

        fun refreshHeader() {
            val ui = pro.sketchware.ai.config.AiStrings.current(activity)
            val running = steps.any { !it.done }
            val errors = errorCount
            headerText.text = when {
                running -> String.format(ui.workingStepsFormat, steps.size)
                errors > 0 -> String.format(ui.failedStepsFormat, steps.size, errors)
                else -> String.format(ui.completedStepsFormat, steps.size)
            }
            headerIcon.imageTintList = ColorStateList.valueOf(if (errors > 0 && !running) cWarning else cOnSurfaceVar)
        }
    }

    private inner class ToolStep(val toolName: String, private val argsJson: String) {
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        private val statusHolder = FrameLayout(activity)
        private val details: LinearLayout
        private val chevron: ImageView
        var done = false
            private set
        var isError = false
            private set
        private var expanded = false

        init {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(6), dp(4), dp(6))
                background = ripple(null)
                isClickable = true
                setOnClickListener { toggle() }
            }
            row.addView(statusHolder, lp(dp(16), dp(16)))

            val isSubagent = toolName == "delegate_subagent"
            val isSkill = toolName == "use_skill"
            val isRea = toolName == "reconstruct_apk_project" || toolName.startsWith("mcp__rea_mcp__")
            if (isSubagent || isSkill || isRea) {
                row.addView(TextView(activity).apply {
                    text = when {
                        isRea -> "REA CLONE"
                        isSubagent -> "SUB-AGENT"
                        else -> "SKILL"
                    }
                    textSize = 9.5f
                    typeface = Typeface.MONOSPACE
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(if (isSubagent || isRea) cGold else cPrimary)
                    setPadding(dp(6), dp(1), dp(6), dp(1))
                    background = rounded(cSurfaceHigh, 6f, if (isSubagent || isRea) cGold else cOutline)
                }, lp(-2, -2).apply { leftMargin = dp(8) })
            }

            row.addView(TextView(activity).apply {
                text = humanizeTool(toolName)
                textSize = 13f
                setTextColor(cOnSurface)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, lp(-2, -2).apply { leftMargin = dp(8) })
            val summary = argSummary(argsJson)
            row.addView(TextView(activity).apply {
                text = summary
                textSize = 11.5f
                typeface = Typeface.MONOSPACE
                setTextColor(cOnSurfaceVar)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
                visibility = if (summary.isEmpty()) View.GONE else View.VISIBLE
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
            if (summary.isEmpty()) row.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
            chevron = tintedIcon(R.drawable.ic_mtrl_chevron_right_24, cOnSurfaceVar, 16)
            row.addView(chevron, lp(dp(16), dp(16)).apply { leftMargin = dp(6) })
            root.addView(row)

            details = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), 0, 0, dp(6))
                visibility = View.GONE
            }
            if (argsJson.isNotBlank() && argsJson != "{}") {
                details.addView(codeBlock(prettyJson(argsJson), tx.input), lp(-1, -2).apply { bottomMargin = dp(6) })
            }
            root.addView(details)
            setStatusRunning()
        }

        private fun toggle() {
            expanded = !expanded
            details.visibility = if (expanded) View.VISIBLE else View.GONE
            chevron.animate().rotation(if (expanded) 90f else 0f).setDuration(150).start()
        }

        private fun setStatusRunning() {
            statusHolder.removeAllViews()
            statusHolder.addView(CircularProgressIndicator(activity).apply {
                isIndeterminate = true
                indicatorSize = dp(14)
                trackThickness = dp(2)
                setIndicatorColor(cPrimary)
            }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }

        fun complete(output: String, error: Boolean) {
            done = true
            isError = error
            statusHolder.removeAllViews()
            val icon = if (error) R.drawable.ic_mtrl_close else R.drawable.ic_mtrl_check
            val color = if (error) cError else cSuccess
            statusHolder.addView(tintedIcon(icon, color, 16), FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
            if (output.isNotBlank()) {
                details.addView(codeBlock(output.trim(), if (error) "error" else tx.output), lp(-1, -2))
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // State Updates
    // ------------------------------------------------------------------------------------------

    private fun setRunning(running: Boolean, statusText: String, step: Int, maxSteps: Int) {
        isRunning = running
        thinkingRow.visibility = if (running) View.VISIBLE else View.GONE
        if (running) {
            val ui = pro.sketchware.ai.config.AiStrings.current(activity)
            val mBounded = Regex("^Langkah\\s+(\\d+)/(\\d+)(?:\\s*\\[[^\\]]+\\])?:\\s*").find(statusText)
            val mUnlimited = Regex("^Langkah\\s+(\\d+)\\s*·\\s*∞(?:\\s*\\[[^\\]]+\\])?:\\s*").find(statusText)
            val clean = when {
                mUnlimited != null -> statusText.substring(mUnlimited.range.last + 1)
                mBounded != null -> statusText.substring(mBounded.range.last + 1)
                else -> statusText
            }
            tvThinking.text = clean.ifBlank { ui.thinkingText }
            val stepLabel = when {
                mUnlimited != null -> "${mUnlimited.groupValues[1]} · ∞"
                mBounded != null -> "${mBounded.groupValues[1]}/${mBounded.groupValues[2]}"
                step > 0 && maxSteps <= 0 -> "$step · ∞"
                step > 0 && maxSteps > 0 -> "$step/$maxSteps"
                else -> ""
            }
            tvThinkingStep.text = stepLabel
            tvThinkingStep.visibility = if (stepLabel.isEmpty()) View.GONE else View.VISIBLE
            if (pulse?.isStarted != true) pulse?.start()
            dotAnimators.forEach { if (!it.isStarted) it.start() }
            ensureThinkingRowLast()
            scrollToBottomIfSticky()
        } else {
            pulse?.cancel()
            dotAnimators.forEach { it.cancel() }
            tvThinking.alpha = 1f
        }
        updateSendButton()
        updateEmptyState()
    }

    private fun updateSendButton() {
        if (!::btnSend.isInitialized) return
        val hasInput = !etPrompt.text.isNullOrBlank() || attachedContexts.isNotEmpty()
        if (isRunning) {
            ivSend.setImageResource(R.drawable.ic_mtrl_stop)
            ivSend.imageTintList = ColorStateList.valueOf(cSurface)
            btnSend.background = ripple(oval(cOnSurface))
            btnSend.contentDescription = "Hentikan"
            btnSend.isEnabled = true
            btnSend.alpha = 1f
        } else {
            ivSend.setImageResource(R.drawable.ic_mtrl_arrow_up)
            ivSend.imageTintList = ColorStateList.valueOf(if (hasInput) cOnPrimary else cOnSurfaceVar)
            btnSend.background = ripple(oval(if (hasInput) cPrimary else cSurfaceHigh))
            btnSend.contentDescription = "Kirim"
            btnSend.isEnabled = hasInput
            btnSend.alpha = if (hasInput) 1f else 0.7f
        }
    }

    private fun renderPlan(plan: AgentPlan) {
        if (plan.tasks.isEmpty() && plan.goal.isBlank()) {
            planPanel.visibility = View.GONE
            return
        }
        val ui = pro.sketchware.ai.config.AiStrings.current(activity)
        planPanel.visibility = View.VISIBLE
        val total = plan.tasks.size
        val done = plan.tasks.count { it.status == TaskStatus.COMPLETED }
        val current = plan.tasks.firstOrNull { it.status == TaskStatus.IN_PROGRESS }
            ?: plan.tasks.firstOrNull { it.status == TaskStatus.PENDING }
        planHeaderTitle.text = if (total > 0) "${tx.todos} · $done/$total" else tx.todos
        planHeaderSub.text = current?.title ?: plan.goal.ifBlank { ui.allTasksCompleted }
        planProgress.setProgressCompat(if (total == 0) 0 else done * 100 / total, true)

        planList.removeAllViews()
        if (plan.goal.isNotBlank()) {
            planList.addView(TextView(activity).apply {
                text = plan.goal
                textSize = 12f
                setTextColor(cOnSurfaceVar)
            }, lp(-1, -2).apply { bottomMargin = dp(6) })
        }
        for (t in plan.tasks) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            val (glyph, color) = when (t.status) {
                TaskStatus.COMPLETED -> "✓" to cSuccess
                TaskStatus.IN_PROGRESS -> "◉" to cPrimary
                TaskStatus.FAILED -> "✕" to cError
                TaskStatus.PENDING -> "○" to cOnSurfaceVar
            }
            row.addView(TextView(activity).apply {
                text = glyph
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(color)
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            }, lp(dp(18), -2))
            val title = SpannableStringBuilder(t.title)
            if (t.status == TaskStatus.COMPLETED) {
                title.setSpan(StrikethroughSpan(), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            row.addView(TextView(activity).apply {
                text = title
                textSize = 13f
                setTextColor(if (t.status == TaskStatus.COMPLETED) cOnSurfaceVar else cOnSurface)
                if (t.status == TaskStatus.IN_PROGRESS) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
            if (t.milestone.isNotBlank() && t.milestone != "General") {
                row.addView(TextView(activity).apply {
                    text = t.milestone
                    textSize = 10.5f
                    setTextColor(cOnSurfaceVar)
                    setPadding(dp(6), dp(1), dp(6), dp(1))
                    background = rounded(cSurfaceHigh, 8f)
                    maxLines = 1
                }, lp(-2, -2).apply { leftMargin = dp(6) })
            }
            planList.addView(row)
        }
        applyPlanExpansion()
    }

    private fun refreshHeader() {
        val ui = pro.sketchware.ai.config.AiStrings.current(activity)
        val configured = config.isConfigured()
        val mcp = if (SketchwareMcpServer.isRunning()) " · MCP :${SketchwareMcpServer.boundPort}" else ""
        val provider = config.providerType.name.lowercase().replaceFirstChar { it.uppercase() }
        val modeBadge = " · ${modeTitle(config.autonomyMode)} (${DeerFlowTheme.modeTag(config.autonomyMode)})"
        tvSubtitle.text = if (configured) "$provider · ${config.getModelId()}$modeBadge$mcp" else "${ui.noApiKeyText}$modeBadge$mcp"
        statusDot.background = oval(if (configured) cSuccess else cWarning)
        tvModelPill.text = if (configured) config.getModelId() else ui.configureModelText
        if (::configBanner.isInitialized) {
            configBanner.visibility = if (configured) View.GONE else View.VISIBLE
        }
        refreshModePill()
        refreshWelcomeTexts()
        refreshArtifactsBadge()
        if (::etPrompt.isInitialized) {
            etPrompt.hint = ui.composerHint
        }
        if (::tvDisclaimer.isInitialized) {
            tvDisclaimer.text = ui.disclaimerText
        }
    }

    // ------------------------------------------------------------------------------------------
    // Actions & Sheets
    // ------------------------------------------------------------------------------------------

    private fun submit(raw: String) {
        val rawTrimmed = raw.trim()
        if (rawTrimmed.isEmpty() && attachedContexts.isEmpty()) return
        val matchedSlash = slashCommands().firstOrNull {
            it.command.equals(rawTrimmed, ignoreCase = true)
        }
        val baseText = matchedSlash?.prompt ?: rawTrimmed
        if (baseText == "/" && attachedContexts.isEmpty()) {
            updateSlashSuggestions("/", forceShowAll = true)
            return
        }
        if (isRunning) {
            SketchwareUtil.toast("Agent sedang bekerja. Tekan tombol stop untuk membatalkan.")
            return
        }
        hideSlashPanel()
        etPrompt.setText("")

        val isId = config.agentLanguage.resolved() == pro.sketchware.ai.config.AgentLanguage.ID
        val effectiveUserPrompt = if (baseText.isEmpty() && attachedContexts.isNotEmpty()) {
            if (isId) {
                "Analisis konteks/file yang saya lampirkan berikut dan bantu terapkan sesuai kebutuhan proyek ini."
            } else {
                "Analyze the attached context/files below and help integrate or apply them in this project."
            }
        } else {
            baseText
        }

        val text = if (attachedContexts.isEmpty()) {
            effectiveUserPrompt
        } else {
            buildString {
                appendLine(effectiveUserPrompt)
                appendLine()
                appendLine("---Perhatian Agent: Pengguna Melampirkan Konteks Berikut (Add Context)---")
                for (att in attachedContexts) {
                    appendLine(att.promptBlock.trim())
                    appendLine()
                }
            }.trim()
        }

        attachedContexts.clear()
        renderAttachedContextChips()
        refreshHeader()
        updateSendButton()
        stickToBottom = true
        if (!config.isConfigured()) {
            val userEntry = AgentChatEntry(UUID.randomUUID().toString(), ChatEntryType.USER, "Anda (MCP Bridge Queue)", text)
            AgentMemoryStore.appendChatEntry(scId, userEntry)
            renderEntry(userEntry, live = true)
            val info = AgentChatEntry(
                UUID.randomUUID().toString(), ChatEntryType.ASSISTANT,
                "MCP Server (Port ${SketchwareMcpServer.boundPort})",
                "Instruksi dicatat di memori proyek **#$scId**.\n\n" +
                    "- Agent eksternal (Antigravity / Claude Code / Cursor) yang terhubung ke MCP Server dapat langsung mengeksekusinya.\n" +
                    "- Atau atur API key lewat tombol model di bawah untuk menjalankan agent langsung di HP."
            )
            AgentMemoryStore.appendChatEntry(scId, info)
            renderEntry(info, live = true)
            scrollToBottomIfSticky()
            return
        }
        hideKeyboard()
        AgentOrchestrator.startUserTask(activity, scId, text, activeScreenName)
    }

    private fun confirmNewChat() {
        val ui = pro.sketchware.ai.config.AiStrings.current(activity)
        if (isRunning) {
            SketchwareUtil.toast("Hentikan agent terlebih dahulu.")
            return
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setTitle(ui.newChatTitle)
            .setMessage(ui.newChatConfirmMsg)
            .setNegativeButton(ui.cancelText, null)
            .setPositiveButton(ui.newChatStartBtn) { _, _ ->
                AgentMemoryStore.clearSession(scId)
                renderPlan(AgentPlan())
                loadHistory()
            }
            .show()
    }

    private fun showOverflow(anchor: View) {
        val ui = pro.sketchware.ai.config.AiStrings.current(activity)
        PopupMenu(activity, anchor).apply {
            menu.add(0, 8, 0, "📦 ${tx.artifacts}")
            menu.add(0, 1, 1, ui.menuSettings)
            menu.add(0, 7, 2, "${ui.menuLanguage} (${config.agentLanguage.shortBadge})")
            menu.add(0, 5, 3, ui.menuExtensions)
            menu.add(0, 4, 4, ui.menuTerminal)
            menu.add(0, 6, 5, ui.menuCheckpoints)
            menu.add(0, 2, 6, ui.menuCopyTranscript)
            menu.add(0, 3, 7, ui.newChatStartBtn)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    8 -> showArtifactsSheet()
                    1 -> AiSettingsDialog.show(activity) { refreshHeader() }
                    7 -> pro.sketchware.ai.config.AiStrings.showLanguagePickerDialog(activity) { rebuildSidebar(); refreshHeader() }
                    5 -> openMcp()
                    4 -> TerminalScreen.show(activity, scId)
                    6 -> showCheckpointsDialog()
                    2 -> copy(exportTranscript(), ui.copiedToast)
                    3 -> confirmNewChat()
                }
                true
            }
        }.show()
    }

    private fun showCheckpointsDialog() {
        val checkpoints = pro.sketchware.ai.project.ProjectGateway.listCheckpoints(scId)
        val items = mutableListOf("+ Buat Checkpoint Baru Sekarang")
        items.addAll(checkpoints.map { cp ->
            "↺ ${cp.id} (${cp.filesCount} berkas · ${cp.totalBytes / 1024} KB)"
        })
        com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setTitle("Checkpoint & Rollback Proyek #$scId")
            .setItems(items.toTypedArray()) { _, which ->
                if (which == 0) {
                    val path = pro.sketchware.ai.project.ProjectGateway.createCheckpoint(scId, "manual_user")
                    SketchwareUtil.toast("Checkpoint disimpan: ${java.io.File(path).name}")
                } else {
                    val target = checkpoints[which - 1]
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                        .setTitle("Pulihkan ke Checkpoint?")
                        .setMessage("Proyek #$scId akan dikembalikan ke '${target.id}'. Seluruh layar, blok logika, dan library akan di-refresh.")
                        .setNegativeButton("Batal", null)
                        .setPositiveButton("Restore Sekarang") { _, _ ->
                            val msg = pro.sketchware.ai.project.ProjectGateway.restoreCheckpoint(scId, target.id)
                            SketchwareUtil.toast(msg)
                        }
                        .show()
                }
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    /**
     * Unified "Add Context" Bottom Sheet (`+` button on bottom-left):
     * 1. Tambahkan File (Add File)
     * 2. Tambahkan Folder (Add Folder)
     * 3. Support File APK (Add / Inspect APK File)
     * 4. Tambahkan Gambar (Add Image / Screenshot)
     * 5. Prompt Cepat (Quick Prompts / Slash Commands)
     * 6. Mode Agent (Ask, Copilot · Flash, Agent · Pro, Autopilot · Ultra, Tutor)
     */
    private fun showAddContextSheet() {
        val isId = config.agentLanguage.resolved() == pro.sketchware.ai.config.AgentLanguage.ID
        val sheet = BottomSheetDialog(activity)
        val scroll = NestedScrollView(activity)
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cPopover)
            setPadding(dp(12), dp(16), dp(12), dp(24))
        }
        scroll.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), dp(10))
        }
        headerRow.addView(tintedIcon(R.drawable.ic_mtrl_add_circle, cPrimary, 22))
        headerRow.addView(TextView(activity).apply {
            text = "Add Context"
            textSize = 17f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(cOnSurface)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(10) })
        col.addView(headerRow)

        fun addMenuCard(
            iconRes: Int,
            title: String,
            subtitle: String,
            badgeText: String? = null,
            onClick: () -> Unit
        ) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = ripple(rounded(cSurfaceCont, 14f, cOutline))
                isClickable = true
                setOnClickListener {
                    sheet.dismiss()
                    onClick()
                }
            }
            row.addView(tintedIcon(iconRes, cPrimary, 22))
            val textCol = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
            }
            val topLine = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            topLine.addView(TextView(activity).apply {
                text = title
                textSize = 14.5f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(cOnSurface)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (!badgeText.isNullOrBlank()) {
                topLine.addView(TextView(activity).apply {
                    text = badgeText
                    textSize = 11f
                    setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(cPrimary)
                    setPadding(dp(8), dp(2), dp(8), dp(2))
                    background = rounded(cSurfaceHigh, 8f)
                }, lp(-2, -2).apply { leftMargin = dp(6) })
            }
            textCol.addView(topLine)
            textCol.addView(TextView(activity).apply {
                text = subtitle
                textSize = 12f
                setTextColor(cOnSurfaceVar)
            }, lp(-1, -2).apply { topMargin = dp(2) })
            row.addView(textCol, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
            row.addView(tintedIcon(R.drawable.ic_mtrl_chevron_right_24, cOnSurfaceVar, 18))
            col.addView(row, lp(-1, -2).apply { bottomMargin = dp(8) })
        }

        addMenuCard(
            iconRes = R.drawable.ic_mtrl_file,
            title = if (isId) "Tambahkan File" else "Add File",
            subtitle = if (isId) {
                "Lampirkan file kode (.java, .kt, .xml, .json), library (.jar, .dex, .aar), atau dokumen ke AI Agent"
            } else {
                "Attach code files (.java, .kt, .xml, .json), libraries (.jar, .dex, .aar), or documents to AI Agent"
            }
        ) {
            ContextAttachmentPicker.showFileOrFolderPicker(
                context = activity,
                scId = scId,
                mode = ContextAttachmentPicker.PickerMode.ANY_FILE,
                onAttached = { addContextAttachment(it) }
            )
        }

        addMenuCard(
            iconRes = R.drawable.ic_mtrl_folder,
            title = if (isId) "Tambahkan Folder" else "Add Folder",
            subtitle = if (isId) {
                "Berikan struktur & akses satu folder penuh dari penyimpanan perangkat ke AI Agent"
            } else {
                "Provide full folder tree & access from device storage to AI Agent"
            }
        ) {
            ContextAttachmentPicker.showFileOrFolderPicker(
                context = activity,
                scId = scId,
                mode = ContextAttachmentPicker.PickerMode.FOLDER,
                onAttached = { addContextAttachment(it) }
            )
        }

        addMenuCard(
            iconRes = R.drawable.ic_mtrl_apk_document,
            title = if (isId) "Tambahkan File APK (REA Full Clone)" else "Add APK File (REA Full Clone)",
            subtitle = if (isId) {
                "Reverse engineer & clone penuh APK (DEX, Manifest, Layout XML, Drawable, Assets) langsung menjadi proyek Sketchware"
            } else {
                "Reverse engineer & full-clone APK (DEX, Manifest, Layout XML, Drawables, Assets) directly into Sketchware project"
            },
            badgeText = "🧬 REA APK"
        ) {
            ContextAttachmentPicker.showFileOrFolderPicker(
                context = activity,
                scId = scId,
                mode = ContextAttachmentPicker.PickerMode.APK_ONLY,
                onAttached = { item ->
                    addContextAttachment(item)
                    if (etPrompt.text.isNullOrBlank() && item.badge.contains("REA CLONE")) {
                        prefill(
                            if (isId) {
                                "Lakukan Full Clone & Reverse Engineering (REA) pada file APK yang saya lampirkan ini ke proyek Sketchware ini hingga selesai dan BUILD SUKSES!"
                            } else {
                                "Perform a Full Clone & Reverse Engineering (REA) of this attached APK into this Sketchware project until finished and BUILD SUCCEEDS!"
                            }
                        )
                    }
                    focusComposer()
                }
            )
        }

        addMenuCard(
            iconRes = R.drawable.ic_mtrl_image,
            title = if (isId) "Tambahkan Gambar" else "Add Image",
            subtitle = if (isId) {
                "Lampirkan gambar / screenshot referensi desain UI atau impor langsung ke @drawable proyek"
            } else {
                "Attach UI reference image / screenshot or import directly into project @drawable"
            }
        ) {
            ContextAttachmentPicker.showFileOrFolderPicker(
                context = activity,
                scId = scId,
                mode = ContextAttachmentPicker.PickerMode.IMAGE_ONLY,
                onAttached = { addContextAttachment(it) }
            )
        }

        addMenuCard(
            iconRes = R.drawable.ic_mtrl_code,
            title = if (isId) "Prompt Cepat (Slash Commands)" else "Quick Prompts (Slash Commands)",
            subtitle = if (isId) {
                "Pilih template perintah cepat (/bangun-apk, /cek-error, /subagent-audit, Skill & Plugin)"
            } else {
                "Pick ready-to-use slash commands (/bangun-apk, /cek-error, /subagent-audit, Skills & Plugins)"
            },
            badgeText = "/ Prompt"
        ) {
            updateSlashSuggestions("", forceShowAll = true)
            focusComposer()
        }

        addMenuCard(
            iconRes = R.drawable.ic_mtrl_tune,
            title = if (isId) "Mode Agent" else "Agent Mode",
            subtitle = pro.sketchware.ai.config.AiStrings.modeSubtitle(config.autonomyMode, config.agentLanguage),
            badgeText = "${modeTitle(config.autonomyMode)} · ${DeerFlowTheme.modeTag(config.autonomyMode)}"
        ) {
            showModePicker()
        }

        sheet.setContentView(scroll)
        sheet.show()
    }

    private fun showModePicker() {
        val sheet = BottomSheetDialog(activity)
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cPopover)
            setPadding(dp(10), dp(16), dp(10), dp(24))
        }
        col.addView(TextView(activity).apply {
            text = tx.agentModes
            textSize = 16.5f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(cOnSurface)
            setPadding(dp(14), 0, dp(14), dp(8))
        })
        for (mode in AgentAutonomyMode.entries) {
            val selected = config.autonomyMode == mode
            val ultra = mode == AgentAutonomyMode.AUTOPILOT
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = ripple(if (selected) rounded(cSurfaceHigh, 12f, if (ultra) cGold else cOutline) else null)
                isClickable = true
                setOnClickListener {
                    config.autonomyMode = mode
                    rebuildSidebar()
                    refreshHeader()
                    sheet.dismiss()
                }
            }
            row.addView(tintedIcon(modeIcon(mode), if (ultra) cGold else cOnSurface, 20), lp(dp(20), dp(20)).apply { rightMargin = dp(12) })
            val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            val topLine = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            topLine.addView(TextView(activity).apply {
                text = modeTitle(mode)
                textSize = 14.5f
                setTextColor(if (ultra) cGold else cOnSurface)
                if (selected || ultra) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            })
            topLine.addView(TextView(activity).apply {
                text = DeerFlowTheme.modeTag(mode)
                textSize = 10.5f
                typeface = Typeface.MONOSPACE
                setTextColor(if (ultra) cGold else cOnSurfaceVar)
                setPadding(dp(6), dp(1), dp(6), dp(1))
                background = rounded(cSurfaceLow, 6f, if (ultra) cGold else cOutline)
            }, lp(-2, -2).apply { leftMargin = dp(8) })
            texts.addView(topLine)
            texts.addView(TextView(activity).apply {
                text = pro.sketchware.ai.config.AiStrings.modeSubtitle(mode, config.agentLanguage)
                textSize = 12f
                setTextColor(cOnSurfaceVar)
            }, lp(-1, -2).apply { topMargin = dp(2) })
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            if (selected) row.addView(tintedIcon(R.drawable.ic_mtrl_check, if (ultra) cGold else cPrimary, 20))
            col.addView(row, lp(-1, -2).apply { bottomMargin = dp(4) })
        }
        sheet.setContentView(col)
        sheet.show()
    }

    private fun exportTranscript(): String = buildString {
        for (e in AgentMemoryStore.loadChatEntries(scId)) {
            val who = when (e.type) {
                ChatEntryType.USER -> "Anda"
                ChatEntryType.ASSISTANT -> "Agent"
                ChatEntryType.TOOL_CALL -> "→ ${toolNameOf(e.title, "Tool Call:")}"
                ChatEntryType.TOOL_RESULT -> "← ${toolNameOf(e.title, "Result:")}"
                ChatEntryType.STATUS -> "• ${cleanTitle(e.title)}"
                ChatEntryType.ERROR -> "! ${cleanTitle(e.title)}"
            }
            append(who).append('\n').append(e.body.trim()).append("\n\n")
        }
    }

    // ------------------------------------------------------------------------------------------
    // Markdown
    // ------------------------------------------------------------------------------------------

    private fun markdownView(text: String): View {
        val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val parts = text.split("```")
        parts.forEachIndexed { i, part ->
            if (i % 2 == 1) {
                val nl = part.indexOf('\n')
                val lang = if (nl > 0) part.substring(0, nl).trim() else ""
                val code = if (nl >= 0) part.substring(nl + 1) else part
                col.addView(codeBlock(code.trimEnd(), lang.ifEmpty { "code" }), lp(-1, -2).apply {
                    topMargin = dp(6); bottomMargin = dp(6)
                })
            } else if (part.isNotBlank()) {
                col.addView(TextView(activity).apply {
                    this.text = renderInlineMarkdown(part.trim('\n'))
                    textSize = 15f
                    setTextColor(cOnSurface)
                    setLineSpacing(0f, 1.22f)
                    setTextIsSelectable(true)
                }, lp(-1, -2))
            }
        }
        return col
    }

    private fun renderInlineMarkdown(src: String): CharSequence {
        val out = SpannableStringBuilder()
        val lines = src.split('\n')
        lines.forEachIndexed { idx, rawLine ->
            var line = rawLine
            val start = out.length
            var heading = 0
            var bullet = false
            var quote = false
            when {
                line.startsWith("### ") -> { heading = 3; line = line.substring(4) }
                line.startsWith("## ") -> { heading = 2; line = line.substring(3) }
                line.startsWith("# ") -> { heading = 1; line = line.substring(2) }
                line.trimStart().let { it.startsWith("- ") || it.startsWith("* ") || it.startsWith("• ") } -> {
                    bullet = true
                    line = line.trimStart().substring(2)
                }
                line.startsWith("> ") -> { quote = true; line = line.substring(2) }
            }
            if (bullet) out.append("•  ")
            appendInline(out, line)
            val end = out.length
            if (heading > 0 && end > start) {
                out.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(RelativeSizeSpan(if (heading == 1) 1.3f else if (heading == 2) 1.18f else 1.06f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (bullet && end > start) {
                out.setSpan(LeadingMarginSpan.Standard(dp(4), dp(18)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (quote && end > start) {
                out.setSpan(ForegroundColorSpan(cOnSurfaceVar), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(LeadingMarginSpan.Standard(dp(12)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (idx < lines.size - 1) out.append('\n')
        }
        return out
    }

    private val inlineRegex = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`")

    private fun appendInline(out: SpannableStringBuilder, line: String) {
        var last = 0
        for (m in inlineRegex.findAll(line)) {
            out.append(line, last, m.range.first)
            val s = out.length
            val bold = m.groups[1]
            if (bold != null) {
                out.append(bold.value)
                out.setSpan(StyleSpan(Typeface.BOLD), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                out.append(m.groupValues[2])
                out.setSpan(TypefaceSpan("monospace"), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(BackgroundColorSpan(cSurfaceHigh), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(RelativeSizeSpan(0.9f), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            last = m.range.last + 1
        }
        out.append(line, last, line.length)
    }

    private fun codeBlock(code: String, label: String): View {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cSurfaceLow, 10f, cOutline)
        }
        val head = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(4), dp(4))
        }
        head.addView(TextView(activity).apply {
            text = label
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(if (label == "error") cError else cOnSurfaceVar)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(smallAction(R.drawable.ic_mtrl_file, tx.copy) { copy(code, "Disalin") })
        box.addView(head)
        box.addView(View(activity).apply { setBackgroundColor(cOutline) }, lp(-1, 1))
        val hs = HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false }
        hs.addView(TextView(activity).apply {
            text = code
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(cOnSurface)
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.15f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        })
        box.addView(hs, lp(-1, -2))
        return box
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private fun toolNameOf(title: String, prefix: String) = title.removePrefix(prefix).trim()

    private fun cleanTitle(title: String) = title.replace("??", "").replace(Regex("[\\x00-\\x1F]"), "").trim()

    private fun modeTitle(mode: AgentAutonomyMode) = mode.title.replace(Regex("[\\x00-\\x1F]"), "→")

    private fun looksLikeError(s: String): Boolean {
        val head = s.take(200).lowercase()
        return head.startsWith("error") || head.startsWith("gagal") || head.startsWith("failed") ||
            head.startsWith("❌") || head.contains("build gagal") || head.contains("exception:") ||
            head.startsWith("tool tidak dikenal") || head.startsWith("unknown tool")
    }

    private val toolLabels = mapOf(
        "get_project_summary" to "Membaca ringkasan proyek",
        "get_screen_state" to "Memeriksa state layar",
        "read_screen_xml" to "Membaca layout",
        "write_screen_xml" to "Menulis layout",
        "create_screen" to "Membuat layar baru",
        "manage_variable" to "Mengelola variabel",
        "manage_list" to "Mengelola list",
        "add_component" to "Menambah komponen",
        "create_more_block" to "Membuat MoreBlock",
        "search_blocks_catalog" to "Mencari katalog blok",
        "read_event_logic" to "Membaca logika event",
        "write_event_logic" to "Menulis logika event",
        "configure_library" to "Mengatur library",
        "add_permissions" to "Menambah permission",
        "add_custom_imports" to "Menambah import",
        "write_custom_java_file" to "Menulis file Java",
        "manage_sketchware_files" to "Mengelola file",
        "manage_local_libraries" to "Mengelola local library / Maven",
        "manage_project_resources" to "Mengelola resource & drawable",
        "configure_project_metadata" to "Mengatur metadata proyek",
        "search_project_code" to "Mencari di seluruh proyek",
        "manage_checkpoints" to "Mengelola checkpoint proyek",
        "preview_generated_source" to "Melihat source hasil generate",
        "inspect_runtime_logs" to "Memeriksa log runtime",
        "update_long_horizon_plan" to "Memperbarui To-dos",
        "save_and_verify_compile" to "Menyimpan & compile",
        "run_shell" to "Menjalankan terminal shell",
        "manage_linux_env" to "Mengelola sistem Linux Debian",
        "web_search" to "Mencari di web",
        "web_fetch" to "Mengambil halaman web",
        "use_skill" to "Memuat Skill",
        "manage_plugins" to "Mengelola Plugin",
        "manage_mcp_connectors" to "Mengelola konektor MCP",
        "call_mcp_tool" to "Memanggil tool MCP",
        "manage_artifacts" to "Mengelola Workspace Artifacts",
        "delegate_subagent" to "Delegasi Sub-Agent",
        "reconstruct_apk_project" to "REA Reverse Engineer & Clone APK"
    )

    private fun humanizeTool(name: String) = toolLabels[name]
        ?: name.replace('_', ' ').replaceFirstChar { it.uppercase() }

    private fun argSummary(json: String): String {
        if (json.isBlank()) return ""
        return try {
            val obj: JsonObject = JsonParser.parseString(json).asJsonObject
            val keys = listOf("role", "skill_id", "apk_path", "xml_entry", "file_name", "screen_name", "path", "target_id", "event_name", "name",
                "action", "library_names", "permissions", "goal", "task", "query")
            val parts = mutableListOf<String>()
            for (k in keys) {
                val v = obj.get(k) ?: continue
                if (!v.isJsonPrimitive) continue
                val s = v.asString.replace('\n', ' ').trim()
                if (s.isNotEmpty()) parts.add(s.take(48))
                if (parts.size >= 2) break
            }
            parts.joinToString(" · ")
        } catch (_: Exception) {
            ""
        }
    }

    private fun prettyJson(json: String): String = try {
        GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(JsonParser.parseString(json))
    } catch (_: Exception) {
        json
    }

    private fun scrollToBottomIfSticky() {
        if (stickToBottom) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun focusComposer() {
        etPrompt.requestFocus()
        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.showSoftInput(etPrompt, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(etPrompt.windowToken, 0)
        etPrompt.clearFocus()
    }

    private fun copy(text: String, toast: String) {
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("agent", text))
        SketchwareUtil.toast(toast)
    }

    private fun dp(v: Int) = SketchwareUtil.dpToPx(v.toFloat())
    private fun dp(v: Float) = SketchwareUtil.dpToPx(v)

    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)

    private fun rounded(color: Int, radiusDp: Float, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(color)
            if (stroke != null) setStroke(maxOf(1, dp(1f)), stroke)
        }

    private fun oval(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun ripple(content: Drawable?): Drawable = RippleDrawable(
        ColorStateList.valueOf(ColorUtils.setAlphaComponent(cOnSurface, 30)),
        content,
        (content as? GradientDrawable)?.constantState?.newDrawable()?.mutate()?.also { (it as GradientDrawable).setColor(Color.WHITE) }
            ?: ColorDrawable(Color.WHITE)
    )

    private fun tintedIcon(res: Int, tint: Int, sizeDp: Int) = ImageView(activity).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(tint)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun iconButton(res: Int, desc: String, onClick: () -> Unit): View = FrameLayout(activity).apply {
        contentDescription = desc
        isClickable = true
        background = RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(cOnSurface, 30)), null, null)
        addView(tintedIcon(res, cOnSurface, 22), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        setOnClickListener { onClick() }
    }

    private fun circleIconButton(res: Int, bg: Int, tint: Int, sizeDp: Int, onClick: () -> Unit): View =
        FrameLayout(activity).apply {
            isClickable = true
            background = ripple(oval(bg))
            addView(tintedIcon(res, tint, sizeDp / 2), FrameLayout.LayoutParams(dp(sizeDp / 2), dp(sizeDp / 2), Gravity.CENTER))
            setOnClickListener { onClick() }
        }

    private fun smallAction(iconRes: Int, label: String, onClick: () -> Unit): View = TextView(activity).apply {
        text = label
        textSize = 11.5f
        setTextColor(cOnSurfaceVar)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(4), dp(8), dp(4))
        compoundDrawablePadding = dp(4)
        val d = activity.getDrawable(iconRes)?.mutate()
        d?.setTint(cOnSurfaceVar)
        d?.setBounds(0, 0, dp(13), dp(13))
        setCompoundDrawables(d, null, null, null)
        background = ripple(rounded(Color.TRANSPARENT, 12f))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun agentAvatar(sizeDp: Int): View = FrameLayout(activity).apply {
        background = oval(cPrimary)
        addView(TextView(activity).apply {
            text = "🦌"
            textSize = sizeDp * 0.44f
            setTextColor(cOnPrimary)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }, FrameLayout.LayoutParams(-1, -1))
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }
}
