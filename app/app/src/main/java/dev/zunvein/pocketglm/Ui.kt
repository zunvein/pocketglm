package dev.zunvein.pocketglm

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.util.TypedValue
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils

/**
 * The whole design system: a handful of builders over plain Views.
 * Colors come from res/values(-night)/colors.xml, so dark mode is automatic.
 */
object Ui {
    enum class Kind { PRIMARY, TONAL, DANGER, TEXT }

    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
    fun color(ctx: Context, id: Int): Int = ContextCompat.getColor(ctx, id)

    fun shape(fill: Int, radiusPx: Float, stroke: Int = 0, strokePx: Int = 0) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radiusPx
        if (strokePx > 0) setStroke(strokePx, stroke)
    }

    fun text(ctx: Context, s: CharSequence, sp: Float = 14f, colorId: Int = R.color.text, bold: Boolean = false) =
        TextView(ctx).apply {
            text = s
            textSize = sp
            setTextColor(color(ctx, colorId))
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    fun mono(ctx: Context, sp: Float = 12f) = TextView(ctx).apply {
        typeface = Typeface.MONOSPACE
        textSize = sp
        setTextColor(color(ctx, R.color.text))
        setTextIsSelectable(true)
    }

    fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(ctx, 16)
        setPadding(p, p, p, p)
        background = shape(color(ctx, R.color.surface), dp(ctx, 16).toFloat(), color(ctx, R.color.stroke), dp(ctx, 1))
    }

    fun gap(ctx: Context, dpv: Int) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(ctx, dpv))
    }

    fun button(ctx: Context, label: String, kind: Kind = Kind.TONAL): Button = Button(ctx).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        stateListAnimator = null
        minHeight = dp(ctx, 48)
        minimumHeight = dp(ctx, 48)
        setPadding(dp(ctx, 16), 0, dp(ctx, 16), 0)
        style(kind)
    }



    /** A small secondary button for headers and corners (36 dp tall). */
    fun compact(ctx: Context, label: String, kind: Kind = Kind.TONAL): Button = Button(ctx).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        stateListAnimator = null
        minHeight = dp(ctx, 36)
        minimumHeight = dp(ctx, 36)
        setPadding(dp(ctx, 12), 0, dp(ctx, 12), 0)
        style(kind)
    }

    fun pill(ctx: Context, label: String, colorId: Int): TextView = text(ctx, label, 12f, colorId, true).apply {
        setPadding(dp(ctx, 10), dp(ctx, 4), dp(ctx, 10), dp(ctx, 4))
        background = shape(ColorUtils.setAlphaComponent(color(ctx, colorId), 0x26), dp(ctx, 12).toFloat())
    }



    /** Label on the left, value on the right. */
    fun statRow(ctx: Context, label: String): Pair<View, TextView> {
        val value = text(ctx, "-", 14f, R.color.text, true).apply { gravity = Gravity.END }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(ctx, 36)
            addView(text(ctx, label, 14f, R.color.textDim), LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(value)
        }
        return row to value
    }

    fun switchRow(ctx: Context, title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit): Pair<View, SwitchCompat> {
        val sw = SwitchCompat(ctx).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }
        val left = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(ctx, title, 15f, R.color.text, true))
            addView(text(ctx, sub, 13f, R.color.textDim))
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(ctx, 56)
            addView(left, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(ctx, 12) })
            addView(sw)
            setOnClickListener { sw.toggle() }
        }
        return row to sw
    }

    fun input(ctx: Context, hint: String): EditText = EditText(ctx).apply {
        this.hint = hint
        setHintTextColor(color(ctx, R.color.textDim))
        setTextColor(color(ctx, R.color.text))
        textSize = 15f
        val p = dp(ctx, 12)
        setPadding(p, p, p, p)
        background = shape(color(ctx, R.color.surfaceAlt), dp(ctx, 12).toFloat())
    }

    fun iconButton(ctx: Context, resId: Int, desc: String, tintId: Int): ImageView = ImageView(ctx).apply {
        setImageResource(resId)
        setColorFilter(color(ctx, tintId))
        contentDescription = desc
        val p = dp(ctx, 8)
        setPadding(p, p, p, p)
        val tv = TypedValue()
        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        if (tv.resourceId != 0) setBackgroundResource(tv.resourceId)
        isClickable = true
    }

    fun mono2(ctx: Context): TextView = mono(ctx).apply {
        val p = dp(ctx, 12)
        setPadding(p, p, p, p)
        background = shape(color(ctx, R.color.surfaceAlt), dp(ctx, 10).toFloat())
    }
}

fun Button.style(kind: Ui.Kind) {
    val c = context
    val fill = when (kind) {
        Ui.Kind.PRIMARY -> Ui.color(c, R.color.accent)
        Ui.Kind.TONAL -> Ui.color(c, R.color.surfaceAlt)
        Ui.Kind.DANGER -> Ui.color(c, R.color.bad)
        Ui.Kind.TEXT -> Color.TRANSPARENT
    }
    val fg = when (kind) {
        Ui.Kind.PRIMARY, Ui.Kind.DANGER -> Ui.color(c, R.color.onAccent)
        Ui.Kind.TONAL -> Ui.color(c, R.color.text)
        Ui.Kind.TEXT -> Ui.color(c, R.color.accent)
    }
    val r = Ui.dp(c, 12).toFloat()
    setTextColor(fg)
    background = RippleDrawable(
        ColorStateList.valueOf(ColorUtils.setAlphaComponent(fg, 0x33)),
        Ui.shape(fill, r),
        Ui.shape(Color.WHITE, r),
    )
}

fun TextView.setPill(label: String, colorId: Int) {
    text = label
    setTextColor(Ui.color(context, colorId))
    background = Ui.shape(ColorUtils.setAlphaComponent(Ui.color(context, colorId), 0x26), Ui.dp(context, 12).toFloat())
}
