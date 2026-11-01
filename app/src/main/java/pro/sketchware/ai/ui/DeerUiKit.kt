package pro.sketchware.ai.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import pro.sketchware.R

internal enum class ButtonKind { PRIMARY, OUTLINE, TEXT, DANGER }

internal class UiField(val layout: TextInputLayout, val edit: TextInputEditText)

/**
 * View factory shared by the Skills, Plugins, MCP and Model pages. Everything is derived from the
 * DeerFlow palette so the pages look like the rest of the agent studio in light and dark mode.
 */
internal class DeerUi(val context: Context, val p: DeerPalette, val isDark: Boolean) {

    private val density = context.resources.displayMetrics.density

    fun dp(v: Int): Int = (v * density + 0.5f).toInt()
    fun dpf(v: Float): Float = v * density

    fun alpha(color: Int, a: Int): Int = Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))

    fun rounded(fill: Int, radiusDp: Int, stroke: Int? = null, strokeDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dpf(radiusDp.toFloat())
            if (stroke != null) setStroke(dp(strokeDp), stroke)
        }

    fun ripple(content: Drawable?, radiusDp: Int = 12): Drawable =
        RippleDrawable(ColorStateList.valueOf(alpha(p.foreground, 30)), content, rounded(Color.WHITE, radiusDp))

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h, weight)

    fun lpMargin(w: Int, h: Int, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0, weight: Float = 0f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }

    // ---------- primitives ----------

    fun text(value: CharSequence, sizeSp: Float, color: Int, bold: Boolean = false, mono: Boolean = false, maxLines: Int = 0): TextView =
        TextView(context).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            typeface = when {
                mono -> Typeface.MONOSPACE
                bold -> Typeface.DEFAULT_BOLD
                else -> Typeface.DEFAULT
            }
            if (maxLines > 0) {
                this.maxLines = maxLines
                ellipsize = TextUtils.TruncateAt.END
            }
        }

    fun pill(label: String, fg: Int, bg: Int): TextView =
        text(label, 10.5f, fg, bold = true).apply {
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = rounded(bg, 8)
            maxLines = 1
        }

    fun icon(res: Int, tint: Int, sizeDp: Int = 20): ImageView =
        ImageView(context).apply {
            setImageResource(res)
            setColorFilter(tint)
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        }

    private val toneBgLight = intArrayOf(c("#E0F2FE"), c("#FEF3C7"), c("#EDE9FE"), c("#D1FAE5"))
    private val toneFgLight = intArrayOf(c("#0284C7"), c("#B45309"), c("#7C3AED"), c("#047857"))
    private val toneBgDark = intArrayOf(c("#0C2A3F"), c("#3A2A0A"), c("#2A1F4D"), c("#0A3A2A"))
    private val toneFgDark = intArrayOf(c("#7DD3FC"), c("#FCD34D"), c("#C4B5FD"), c("#6EE7B7"))

    private fun c(hex: String) = Color.parseColor(hex)

    /** Rounded square in one of four tints (chosen from [name]) holding an icon or the name's initials. */
    fun iconTile(name: String, iconRes: Int?, sizeDp: Int = 44): View {
        val tone = CapabilityLogic.tone(name)
        val bg = if (isDark) toneBgDark[tone] else toneBgLight[tone]
        val fg = if (isDark) toneFgDark[tone] else toneFgLight[tone]
        return FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
            background = rounded(bg, 12)
            if (iconRes != null) {
                addView(icon(iconRes, fg, sizeDp / 2).apply {
                    layoutParams = FrameLayout.LayoutParams(dp(sizeDp / 2), dp(sizeDp / 2), Gravity.CENTER)
                })
            } else {
                addView(text(CapabilityLogic.initials(name), 14f, fg, bold = true).apply {
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
                })
            }
        }
    }

    fun statusDot(color: Int, sizeDp: Int = 8): View =
        View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
        }

    fun switchView(checked: Boolean, onChange: (Boolean) -> Unit): MaterialSwitch =
        MaterialSwitch(context).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }

    // ---------- containers ----------

    fun card(): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(12))
            background = rounded(p.card, 14, p.border)
            layoutParams = lpMargin(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, b = 10)
        }

    fun row(gravity: Int = Gravity.CENTER_VERTICAL): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            this.gravity = gravity
        }

    fun column(): LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    /** Wrapping row of small views (pills/chips); uses ChipGroup purely as a flow layout. */
    fun flow(): ChipGroup =
        ChipGroup(context).apply {
            chipSpacingHorizontal = dp(6)
            chipSpacingVertical = dp(6)
        }

    fun sectionLabel(label: String): TextView =
        text(label.uppercase(), 11f, p.mutedForeground, bold = true).apply {
            letterSpacing = 0.08f
            setPadding(dp(2), dp(18), 0, dp(8))
        }

    fun divider(): View =
        View(context).apply {
            setBackgroundColor(p.border)
            layoutParams = lpMargin(ViewGroup.LayoutParams.MATCH_PARENT, 1, t = 8, b = 8)
        }

    // ---------- controls ----------

    fun button(label: String, kind: ButtonKind, iconRes: Int? = null, onClick: () -> Unit): MaterialButton =
        MaterialButton(context).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            cornerRadius = dp(10)
            insetTop = 0
            insetBottom = 0
            minHeight = dp(40)
            minimumHeight = dp(40)
            setPadding(dp(14), 0, dp(14), 0)
            val fg: Int
            when (kind) {
                ButtonKind.PRIMARY -> {
                    fg = p.primaryForeground
                    backgroundTintList = ColorStateList.valueOf(p.primary)
                    strokeWidth = 0
                }
                ButtonKind.OUTLINE -> {
                    fg = p.foreground
                    backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                    strokeColor = ColorStateList.valueOf(p.border)
                    strokeWidth = dp(1)
                }
                ButtonKind.TEXT -> {
                    fg = p.mutedForeground
                    backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                    strokeWidth = 0
                }
                ButtonKind.DANGER -> {
                    fg = p.destructive
                    backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                    strokeColor = ColorStateList.valueOf(alpha(p.destructive, 90))
                    strokeWidth = dp(1)
                }
            }
            setTextColor(fg)
            if (iconRes != null) {
                setIconResource(iconRes)
                iconSize = dp(18)
                iconTint = ColorStateList.valueOf(fg)
                iconPadding = dp(6)
            }
            setOnClickListener { onClick() }
        }

    fun chip(label: String, selected: Boolean, onClick: () -> Unit): TextView =
        text(label, 12.5f, if (selected) p.primaryForeground else p.mutedForeground, bold = true).apply {
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = ripple(
                if (selected) rounded(p.primary, 20) else rounded(p.card, 20, p.border),
                20
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    fun searchField(hintText: String, onQuery: (String) -> Unit): LinearLayout =
        row().apply {
            setPadding(dp(12), 0, dp(12), 0)
            background = rounded(p.card, 12, p.border)
            addView(icon(R.drawable.ic_mtrl_search, p.mutedForeground, 18))
            val edit = EditText(context).apply {
                hint = hintText
                setHintTextColor(p.mutedForeground)
                setTextColor(p.foreground)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                background = null
                inputType = InputType.TYPE_CLASS_TEXT
                maxLines = 1
                setPadding(dp(10), dp(12), 0, dp(12))
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) = onQuery(s?.toString().orEmpty())
                })
            }
            addView(edit, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

    /** Outlined Material text field themed with the DeerFlow palette. */
    fun field(hintText: String, value: String = "", password: Boolean = false, mono: Boolean = false, lines: Int = 1): UiField {
        val layout = TextInputLayout(context, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = hintText
            boxStrokeColor = p.ring
            setBoxCornerRadii(dpf(10f), dpf(10f), dpf(10f), dpf(10f))
            if (password) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            layoutParams = lpMargin(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, b = 10)
        }
        val edit = TextInputEditText(layout.context).apply {
            setText(value)
            setTextColor(p.foreground)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            if (mono) typeface = Typeface.MONOSPACE
            inputType = when {
                password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                lines > 1 -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            if (lines > 1) {
                minLines = lines
                gravity = Gravity.TOP or Gravity.START
            } else {
                maxLines = 1
            }
        }
        layout.addView(edit)
        return UiField(layout, edit)
    }

    fun emptyState(iconRes: Int, title: String, description: String, actionLabel: String? = null, onAction: (() -> Unit)? = null): View =
        column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(48), dp(24), dp(24))
            addView(FrameLayout(context).apply {
                layoutParams = lpMargin(dp(56), dp(56), b = 14)
                background = rounded(p.secondary, 28)
                addView(icon(iconRes, p.mutedForeground, 26).apply {
                    layoutParams = FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER)
                })
            })
            addView(text(title, 16f, p.foreground, bold = true).apply { gravity = Gravity.CENTER })
            addView(text(description, 13f, p.mutedForeground).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, dp(16))
            })
            if (actionLabel != null && onAction != null) {
                addView(button(actionLabel, ButtonKind.PRIMARY, R.drawable.ic_mtrl_add) { onAction() }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }

    /**
     * The shared capability card: tinted icon tile, name + source pill, optional status line,
     * description, small chips and a right-aligned action row; the switch toggles the capability.
     */
    fun capabilityCard(
        name: String,
        iconRes: Int?,
        description: String,
        sourceLabel: String,
        enabled: Boolean,
        onToggle: ((Boolean) -> Unit)?,
        statusColor: Int? = null,
        statusText: String? = null,
        chips: List<String> = emptyList(),
        monoLine: String? = null,
        actions: List<View> = emptyList(),
        extra: View? = null
    ): LinearLayout {
        val dim = if (enabled || onToggle == null) 1f else 0.62f
        val card = card()
        val head = row().apply {
            addView(iconTile(name, iconRes).apply { alpha = dim })
            val titleCol = column().apply {
                setPadding(dp(12), 0, dp(8), 0)
                addView(text(name, 15f, p.foreground, bold = true, maxLines = 1))
                addView(row().apply {
                    setPadding(0, dp(4), 0, 0)
                    addView(pill(sourceLabel, p.mutedForeground, p.secondary))
                    if (statusColor != null && statusText != null) {
                        addView(statusDot(statusColor).apply { (layoutParams as LinearLayout.LayoutParams).setMargins(dp(10), 0, dp(5), 0) })
                        addView(text(statusText, 11.5f, p.mutedForeground, maxLines = 1))
                    }
                })
            }
            addView(titleCol, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (onToggle != null) addView(switchView(enabled, onToggle))
        }
        card.addView(head)
        if (description.isNotBlank()) {
            card.addView(text(description, 13f, p.mutedForeground, maxLines = 3).apply {
                alpha = dim
                setPadding(0, dp(10), 0, 0)
                setLineSpacing(0f, 1.1f)
            })
        }
        if (!monoLine.isNullOrBlank()) {
            card.addView(text(monoLine, 11.5f, p.mutedForeground, mono = true, maxLines = 1).apply {
                setPadding(0, dp(8), 0, 0)
            })
        }
        if (chips.isNotEmpty()) {
            card.addView(flow().apply {
                setPadding(0, dp(10), 0, 0)
                chips.forEach { addView(pill(it, p.foreground, p.accent)) }
            })
        }
        if (extra != null) card.addView(extra)
        if (actions.isNotEmpty()) {
            card.addView(row(Gravity.END or Gravity.CENTER_VERTICAL).apply {
                setPadding(0, dp(8), 0, 0)
                actions.forEach { addView(it, lpMargin(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, l = 4)) }
            })
        }
        return card
    }
}
