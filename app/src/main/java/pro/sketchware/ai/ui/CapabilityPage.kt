package pro.sketchware.ai.ui

import android.app.Activity
import android.app.Dialog
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import pro.sketchware.R
import pro.sketchware.ai.config.AiConfigManager
import pro.sketchware.utility.ThemeUtils
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.google.android.material.R as MR

internal data class FilterDef(val key: String, val label: String)

/** What a page rendered: [total] items exist, [shown] pass the current search/filter, [enabled] are switched on. */
internal class ListStats(val total: Int, val shown: Int, val enabled: Int)

/**
 * Full-screen page scaffold (DeerFlow "capability" style): header with close button, action row,
 * search box, filter chips, a one-line summary and a scrolling card list. Skills, Plugins and MCP
 * each subclass this, so each has its own dedicated view instead of sharing one dialog.
 */
internal abstract class CapabilityPage(
    protected val activity: Activity,
    private val titleText: String,
    private val subtitleText: String,
    private val headerIconRes: Int,
    private val onClosed: (() -> Unit)? = null
) {
    protected val config = AiConfigManager(activity)
    protected val isDark: Boolean = ColorUtils.calculateLuminance(ThemeUtils.getColor(activity, MR.attr.colorSurface)) < 0.5
    protected val p: DeerPalette = DeerFlowTheme.palette(isDark)
    protected val ui = DeerUi(activity, p, isDark)
    protected val x = ExtText(config.agentLanguage)

    private val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar)
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()

    protected var query: String = ""
    protected var filterKey: String = "all"

    private lateinit var chipRow: LinearLayout
    private lateinit var summary: TextView
    private lateinit var listBox: LinearLayout

    protected abstract fun filters(): List<FilterDef>
    protected abstract fun actions(): List<View>
    protected abstract fun renderItems(box: LinearLayout, query: String, filterKey: String): ListStats
    protected abstract fun emptyView(): View

    fun show() {
        val root = buildRoot()
        applyFullScreen(dialog, root, p, isDark)
        dialog.setOnDismissListener {
            worker.shutdown()
            onClosed?.invoke()
        }
        dialog.show()
        refresh()
    }

    protected fun dismiss() = dialog.dismiss()

    /** Re-reads data and redraws chips, summary and list. Safe to call after any change. */
    fun refresh() {
        if (!dialog.isShowing && !::listBox.isInitialized) return
        rebuildChips()
        listBox.removeAllViews()
        val stats = renderItems(listBox, query, filterKey)
        summary.text = if (stats.total == 0) "" else
            x.enabledOfTotal(stats.enabled, stats.total) + if (stats.shown != stats.total) "  ·  " + x.t("${stats.shown} ditampilkan", "${stats.shown} shown") else ""
        if (stats.total == 0) {
            listBox.addView(emptyView())
        } else if (stats.shown == 0) {
            listBox.addView(
                ui.emptyState(
                    R.drawable.ic_mtrl_search,
                    x.t("Tidak ada hasil", "No results"),
                    x.t("Coba kata kunci atau filter lain.", "Try another keyword or filter.")
                )
            )
        }
    }

    protected fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()

    protected fun confirm(title: String, message: String, positive: String, onYes: () -> Unit) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(x.t("Batal", "Cancel"), null)
            .setPositiveButton(positive) { _, _ -> onYes() }
            .show()
    }

    /** Scrollable, padded container for dialog forms. */
    protected fun formBox(vararg children: View): View =
        ScrollView(activity).apply {
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(4))
                children.forEach { addView(it) }
            })
        }

    /** Dialog whose positive button only closes it when [onSubmit] returns true (so validation errors keep the form open). */
    protected fun formDialog(title: String, content: View, positive: String, onSubmit: () -> Boolean) {
        val d = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(content)
            .setNegativeButton(x.t("Batal", "Cancel"), null)
            .setPositiveButton(positive, null)
            .create()
        d.setOnShowListener {
            d.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener { if (onSubmit()) d.dismiss() }
        }
        d.show()
    }

    /** Runs [work] off the main thread and delivers the result on it. */
    protected fun <T> runInBackground(work: () -> T, done: (Result<T>) -> Unit) {
        worker.execute {
            val result = runCatching(work)
            activity.runOnUiThread { if (dialog.isShowing) done(result) }
        }
    }

    // ---------- layout ----------

    private fun buildRoot(): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.background)
        }

        root.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(16), ui.dp(12), ui.dp(8), ui.dp(4))
            addView(ui.iconTile(titleText, headerIconRes, 40))
            addView(ui.column().apply {
                setPadding(ui.dp(12), 0, ui.dp(8), 0)
                addView(ui.text(titleText, 19f, p.foreground, bold = true))
                addView(ui.text(subtitleText, 12.5f, p.mutedForeground, maxLines = 2))
            }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ui.icon(R.drawable.ic_mtrl_close, p.foreground, 22).apply {
                setPadding(ui.dp(9), ui.dp(9), ui.dp(9), ui.dp(9))
                layoutParams = LinearLayout.LayoutParams(ui.dp(44), ui.dp(44))
                background = ui.ripple(null, 22)
                isClickable = true
                isFocusable = true
                contentDescription = x.t("Tutup", "Close")
                setOnClickListener { dismiss() }
            })
        })

        val actionViews = actions()
        if (actionViews.isNotEmpty()) {
            root.addView(HorizontalScrollView(activity).apply {
                isHorizontalScrollBarEnabled = false
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(4))
                    actionViews.forEach { addView(it, ui.lpMargin(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, r = 8)) }
                })
            })
        }

        root.addView(
            ui.searchField(x.t("Cari…", "Search…")) { q ->
                query = q
                refresh()
            },
            ui.lpMargin(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, l = 16, t = 8, r = 16)
        )

        chipRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ui.dp(16), ui.dp(2), ui.dp(16), ui.dp(2))
        }
        root.addView(HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipRow)
        }, ui.lpMargin(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, t = 10))

        summary = ui.text("", 12f, p.mutedForeground).apply { setPadding(ui.dp(20), ui.dp(10), ui.dp(20), ui.dp(6)) }
        root.addView(summary)

        listBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(28))
        }
        root.addView(ScrollView(activity).apply {
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            addView(listBox)
        }, ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun rebuildChips() {
        chipRow.removeAllViews()
        for (f in filters()) {
            chipRow.addView(
                ui.chip(f.label, f.key == filterKey) {
                    filterKey = f.key
                    refresh()
                },
                ui.lpMargin(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, r = 8)
            )
        }
    }
}

/** Shared full-screen window + system-bar inset handling for the DeerFlow-style pages. */
internal fun applyFullScreen(dialog: Dialog, root: View, p: DeerPalette, isDark: Boolean) {
    dialog.setContentView(root)
    dialog.window?.let { w ->
        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        w.setBackgroundDrawable(ColorDrawable(p.background))
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        WindowCompat.setDecorFitsSystemWindows(w, false)
        @Suppress("DEPRECATION")
        w.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        w.navigationBarColor = p.background
        WindowInsetsControllerCompat(w, w.decorView).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
    }
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
        WindowInsetsCompat.CONSUMED
    }
}
