package dev.zunvein.pocketglm

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Design system for plain views (no AndroidX, no Material).
 *
 * Tokens: 4dp spacing grid (4/8/12/16/24), radii 6 (controls) / 12 (cards) /
 * 999 (pills), 48dp minimum touch height, 1dp hairlines, no shadows.
 * Colors come from res/values(-night)/colors.xml; one deep-green accent
 * carries primary actions and the active tab, everything else is neutral.
 * Type scale (sp): 12 fine, 13 label, 15 body, 17 title, 28 figure.
 */
object Ui {
    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    fun dip(ctx: Context, d: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, d.toFloat(), ctx.resources.displayMetrics).toInt()

    fun resColor(ctx: Context, id: Int): Int = ctx.getColor(id)

    private fun r(ctx: Context, dp: Int) = dip(ctx, dp).toFloat()

    private fun shape(ctx: Context, fill: Int, radiusDp: Int, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = r(ctx, radiusDp)
            if (strokeColor != null) setStroke(dip(ctx, 1), strokeColor)
        }

    /** Wraps a shape in a ripple so every control gets press feedback. */
    private fun ripple(ctx: Context, content: GradientDrawable): RippleDrawable {
        val tint = ColorStateList.valueOf(resColor(ctx, R.color.accentFg) and 0x00FFFFFF or 0x33000000)
        return RippleDrawable(tint, content, null)
    }

    // ---------- surfaces ----------

    fun cardBg(ctx: Context): GradientDrawable = shape(ctx, resColor(ctx, R.color.card), 12, resColor(ctx, R.color.line))

    fun space(ctx: Context, h: Int): View = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(MATCH, dip(ctx, h)) }
    fun hspace(ctx: Context, w: Int): View = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(dip(ctx, w), 1) }

    fun vertical(ctx: Context, paddingDp: Int = 0): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dip(ctx, paddingDp), dip(ctx, paddingDp), dip(ctx, paddingDp), dip(ctx, paddingDp))
    }

    fun horizontal(ctx: Context, centered: Boolean = true): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        if (centered) gravity = Gravity.CENTER_VERTICAL
    }

    /** A card: surface + hairline border, 16dp padding. */
    fun card(ctx: Context, title: String? = null, hint: String? = null): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg(ctx)
            setPadding(dip(ctx, 16), dip(ctx, 14), dip(ctx, 16), dip(ctx, 16))
            if (title != null) addView(cardHeader(ctx, title, hint))
        }

    private fun cardHeader(ctx: Context, title: String, hint: String?): View {
        val row = horizontal(ctx)
        row.addView(title(ctx, title), LinearLayout.LayoutParams(0, WRAP, 1f))
        if (!hint.isNullOrEmpty()) row.addView(fine(ctx, hint))
        row.setPadding(0, 0, 0, dip(ctx, 10))
        return row
    }

    fun divider(ctx: Context): View = View(ctx).apply {
        setBackgroundColor(resColor(ctx, R.color.line))
        layoutParams = LinearLayout.LayoutParams(MATCH, dip(ctx, 1))
    }

    // ---------- text ----------

    fun title(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(resColor(ctx, R.color.fg))
        textSize = 17f
        typeface = Typeface.DEFAULT_BOLD
    }

    fun body(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(resColor(ctx, R.color.fg))
        textSize = 15f
        setLineSpacing(dip(ctx, 2).toFloat(), 1f)
    }

    fun muted(ctx: Context, text: String): TextView = body(ctx, text).apply {
        setTextColor(resColor(ctx, R.color.muted))
        textSize = 14f
    }

    fun fine(ctx: Context, text: String): TextView = muted(ctx, text).apply { textSize = 12.5f }

    fun mono(ctx: Context, text: String): TextView = body(ctx, text).apply {
        typeface = Typeface.MONOSPACE
        textSize = 12.5f
    }

    /** Big number with a muted caption underneath; used in stat tiles. */
    fun stat(ctx: Context, label: String, value: String, sub: String = ""): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(ctx, resColor(ctx, R.color.sunk), 10, resColor(ctx, R.color.line))
            setPadding(dip(ctx, 12), dip(ctx, 10), dip(ctx, 12), dip(ctx, 10))
            addView(fine(ctx, label))
            addView(TextView(ctx).apply {
                tag = "value"
                text = value
                setTextColor(resColor(ctx, R.color.fg))
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dip(ctx, 2), 0, 0)
            })
            addView(fine(ctx, sub).apply { tag = "sub" })
        }

    fun setStat(tile: View, value: String, sub: String) {
        tile.findViewWithTag<TextView>("value")?.text = value
        tile.findViewWithTag<TextView>("sub")?.text = sub
    }

    // ---------- buttons ----------

    private fun base(ctx: Context, text: String, onClick: (View) -> Unit): Button = Button(ctx).apply {
        this.text = text
        textSize = 14f
        isAllCaps = false
        typeface = Typeface.DEFAULT_BOLD
        minHeight = dip(ctx, 48)
        minimumHeight = dip(ctx, 48)
        minWidth = 0
        minimumWidth = 0
        stateListAnimator = null
        elevation = 0f
        setPadding(dip(ctx, 16), 0, dip(ctx, 16), 0)
        setOnClickListener { v -> onClick(v) }
    }

    /** Secondary button: card fill, hairline border. */
    fun button(ctx: Context, text: String, onClick: (View) -> Unit): Button = base(ctx, text, onClick).apply {
        setTextColor(resColor(ctx, R.color.fg))
        background = ripple(ctx, shape(ctx, resColor(ctx, R.color.card), 8, resColor(ctx, R.color.inputline)))
    }

    /** The one filled button per screen area. */
    fun primary(ctx: Context, text: String, onClick: (View) -> Unit): Button = base(ctx, text, onClick).apply {
        setTextColor(resColor(ctx, R.color.onAccent))
        background = ripple(ctx, shape(ctx, resColor(ctx, R.color.accent), 8, resColor(ctx, R.color.accent)))
    }

    fun danger(ctx: Context, text: String, onClick: (View) -> Unit): Button = button(ctx, text, onClick).apply {
        setTextColor(resColor(ctx, R.color.err))
    }

    /** Compact row action: 40dp tall (the row's padding keeps the touch target >= 48dp). */
    fun small(ctx: Context, text: String, onClick: (View) -> Unit): Button = button(ctx, text, onClick).apply {
        textSize = 13f
        minHeight = dip(ctx, 44)
        minimumHeight = dip(ctx, 44)
        setPadding(dip(ctx, 10), 0, dip(ctx, 10), 0)
    }

    fun smallDanger(ctx: Context, text: String, onClick: (View) -> Unit): Button = small(ctx, text, onClick).apply {
        setTextColor(resColor(ctx, R.color.err))
    }

    /** Equal-width row of buttons. */
    fun buttonRow(ctx: Context, vararg buttons: View): LinearLayout = horizontal(ctx).apply {
        buttons.forEachIndexed { i, b ->
            addView(b, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i > 0) leftMargin = dip(ctx, 8) })
        }
    }

    fun icon(ctx: Context, res: Int, tintRes: Int, sizeDp: Int = 24): ImageView = ImageView(ctx).apply {
        setImageResource(res)
        setColorFilter(resColor(ctx, tintRes))
        layoutParams = LinearLayout.LayoutParams(dip(ctx, sizeDp), dip(ctx, sizeDp))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    // ---------- pills ----------

    fun pillBg(ctx: Context, bg: Int, bordered: Boolean = false): GradientDrawable =
        GradientDrawable().apply {
            setColor(bg)
            cornerRadius = 999f
            if (bordered) setStroke(dip(ctx, 1), resColor(ctx, R.color.line))
        }

    fun pill(ctx: Context, state: String, coolsIn: Int = 0): TextView = TextView(ctx).apply {
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dip(ctx, 10), dip(ctx, 3), dip(ctx, 10), dip(ctx, 3))
        applyState(this, state, coolsIn)
    }

    fun applyState(tv: TextView, state: String, coolsIn: Int = 0) {
        val ctx = tv.context
        when (state) {
            "ready" -> { tv.text = "ready"; tv.setTextColor(resColor(ctx, R.color.ok)); tv.background = pillBg(ctx, resColor(ctx, R.color.okbg)) }
            "cooling" -> { tv.text = "cooling ${coolsIn}s"; tv.setTextColor(resColor(ctx, R.color.cool)); tv.background = pillBg(ctx, resColor(ctx, R.color.coolbg)) }
            "quarantined" -> { tv.text = "parked"; tv.setTextColor(resColor(ctx, R.color.err)); tv.background = pillBg(ctx, resColor(ctx, R.color.errbg)) }
            "dead" -> { tv.text = "dead"; tv.setTextColor(resColor(ctx, R.color.err)); tv.background = pillBg(ctx, resColor(ctx, R.color.errbg)) }
            "off" -> { tv.text = "off"; tv.setTextColor(resColor(ctx, R.color.muted)); tv.background = pillBg(ctx, resColor(ctx, R.color.sunk), true) }
            else -> { tv.text = "not loaded"; tv.setTextColor(resColor(ctx, R.color.muted)); tv.background = pillBg(ctx, resColor(ctx, R.color.sunk), true) }
        }
    }

    /** Small toggle chip (used for the "hide admin calls" filter). */
    fun chip(ctx: Context, text: String, checked: Boolean, onChange: (Boolean) -> Unit): TextView = TextView(ctx).apply {
        var on = checked
        this.text = text
        textSize = 13f
        gravity = Gravity.CENTER
        minHeight = dip(ctx, 36)
        setPadding(dip(ctx, 12), 0, dip(ctx, 12), 0)
        isClickable = true
        fun paint() {
            setTextColor(resColor(ctx, if (on) R.color.accentFg else R.color.muted))
            background = ripple(ctx, if (on) shape(ctx, resColor(ctx, R.color.accentSoft), 999, resColor(ctx, R.color.accentFg))
            else shape(ctx, resColor(ctx, R.color.card), 999, resColor(ctx, R.color.inputline)))
            contentDescription = text.toString() + if (on) ", on" else ", off"
        }
        paint()
        setOnClickListener { on = !on; paint(); onChange(on) }
    }

    // ---------- inputs ----------

    fun input(ctx: Context, hint: String, secret: Boolean = false): EditText = EditText(ctx).apply {
        this.hint = hint
        textSize = 16f
        setTextColor(resColor(ctx, R.color.fg))
        setHintTextColor(resColor(ctx, R.color.muted))
        background = shape(ctx, resColor(ctx, R.color.card), 8, resColor(ctx, R.color.inputline))
        minHeight = dip(ctx, 48)
        setPadding(dip(ctx, 12), dip(ctx, 10), dip(ctx, 12), dip(ctx, 10))
        maxLines = 1
        imeOptions = EditorInfo.IME_ACTION_NEXT
        inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
    }

    fun bubbleBg(ctx: Context, user: Boolean): GradientDrawable =
        if (user) shape(ctx, resColor(ctx, R.color.accentSoft), 14)
        else shape(ctx, resColor(ctx, R.color.card), 14, resColor(ctx, R.color.line))

    // ---------- motion ----------

    /** Soft entrance: fade in while rising a few dp. Respects the system animator scale. */
    fun enter(v: View, delayMs: Long = 0) {
        v.alpha = 0f
        v.translationY = dip(v.context, 8).toFloat()
        v.animate().alpha(1f).translationY(0f).setStartDelay(delayMs).setDuration(220)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    /** Fades [v] in over 160 ms (screen switches). */
    fun fadeIn(v: View) {
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(160).start()
    }

    /** Fade-staggers every direct child of a screen column (late cards too, such as App update). */
    fun cascade(col: ViewGroup) {
        for (i in 0 until col.childCount) enter(col.getChildAt(i), minOf(i, 8) * 40L)
    }

    private fun onEnd(block: () -> Unit) = object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(a: android.animation.Animator) { block() }
    }

    /** Smoothly folds [v] away: height and opacity together, then GONE. */
    fun collapse(v: View) {
        if (v.visibility != View.VISIBLE) return
        val from = v.height
        if (from <= 0) { v.visibility = View.GONE; return }
        android.animation.ValueAnimator.ofInt(from, 0).apply {
            duration = 200
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { v.layoutParams.height = it.animatedValue as Int; v.alpha = 1f - it.animatedFraction; v.requestLayout() }
            addListener(onEnd { v.visibility = View.GONE; v.layoutParams.height = WRAP; v.alpha = 1f })
            start()
        }
    }

    /** Smoothly unfolds [v] to its natural height. */
    fun expand(v: View) {
        val pw = (v.parent as? View)?.width ?: 0
        v.layoutParams.height = WRAP
        v.visibility = View.VISIBLE
        if (pw <= 0) return
        v.measure(View.MeasureSpec.makeMeasureSpec(pw, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val to = v.measuredHeight
        android.animation.ValueAnimator.ofInt(0, to).apply {
            duration = 200
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { v.layoutParams.height = it.animatedValue as Int; v.alpha = it.animatedFraction; v.requestLayout() }
            addListener(onEnd { v.layoutParams.height = WRAP; v.alpha = 1f; v.requestLayout() })
            start()
        }
    }

    /** Checkbox in the app's colors; 48dp tall for the touch target. */
    fun checkbox(ctx: Context, text: String, checked: Boolean, onChange: (Boolean) -> Unit): android.widget.CheckBox =
        android.widget.CheckBox(ctx).apply {
            this.text = text
            isChecked = checked
            textSize = 14f
            setTextColor(resColor(ctx, R.color.fg))
            buttonTintList = ColorStateList.valueOf(resColor(ctx, R.color.accent))
            minHeight = dip(ctx, 48)
            minimumHeight = dip(ctx, 48)
            setPadding(dip(ctx, 6), 0, dip(ctx, 10), 0)
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }

    /** Quiet block behind streamed reasoning: sunk tone, left rule. */
    fun thinkBg(ctx: Context): GradientDrawable = shape(ctx, resColor(ctx, R.color.sunk), 10, resColor(ctx, R.color.line))

    // ---------- toggles ----------

    /**
     * Label plus explanation on the left, a switch on the right. [set] repaints
     * it after the rest of the app changed the setting, without firing [onChange].
     */
    class SwitchRow(
        ctx: Context,
        label: String,
        sub: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
    ) : LinearLayout(ctx) {
        private var quiet = false
        val toggle: android.widget.Switch = android.widget.Switch(ctx)

        init {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = Ui.dip(ctx, 56)
            val text = Ui.vertical(ctx)
            text.addView(Ui.body(ctx, label))
            if (sub.isNotEmpty()) text.addView(Ui.fine(ctx, sub))
            addView(text, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            val on = Ui.resColor(ctx, R.color.accent)
            val off = Ui.resColor(ctx, R.color.inputline)
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            toggle.apply {
                this.isChecked = checked
                contentDescription = label
                thumbTintList = ColorStateList(states, intArrayOf(Ui.resColor(ctx, R.color.onAccent), Ui.resColor(ctx, R.color.muted)))
                trackTintList = ColorStateList(states, intArrayOf(on, off))
                setOnCheckedChangeListener { _, v -> if (!quiet) onChange(v) }
            }
            addView(toggle)
        }

        fun set(on: Boolean) {
            if (toggle.isChecked == on) return
            quiet = true
            toggle.isChecked = on
            quiet = false
        }
    }

    fun switchRow(ctx: Context, label: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit): SwitchRow =
        SwitchRow(ctx, label, sub, checked, onChange)

    // ---------- dialogs ----------

    /** Confirmation dialog; the positive button runs [onOk]. */
    fun confirm(ctx: Context, title: String, message: String, okLabel: String, onOk: () -> Unit) {
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(okLabel) { _, _ -> onOk() }
            .show()
    }

    /** A dialog whose body is [body] (one input, or a column of them). */
    fun dialogWith(ctx: Context, title: String, body: View, okLabel: String, onOk: () -> Unit) {
        val pad = dip(ctx, 20)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dip(ctx, 8), pad, 0)
            addView(body, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(okLabel) { _, _ -> onOk() }
            .show()
    }
}
