package dev.zunvein.pocketglm

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * One bottom-nav destination. Screens are built once and kept alive, so
 * state such as the chat transcript survives tab switches. [onTick] runs
 * every few seconds while the screen is the visible one.
 */
abstract class Screen(protected val act: MainActivity) {
    protected val ctx: Context get() = act
    private val main = Handler(Looper.getMainLooper())

    val root: View by lazy { build() }
    var active = false

    abstract fun build(): View
    open fun onShow() {}

    /** The activity is back in front (the user returned from another screen). */
    open fun onResume() {}

    /** Called by the shell right after the screen is attached: staggered fade-in of the cards. */
    open fun animateIn() {
        val sv = root as? ScrollView ?: return
        (sv.getChildAt(0) as? LinearLayout)?.let { Ui.cascade(it) }
    }
    open fun onTick() {}

    /** Network and disk work: off the UI thread, never crashes the app. */
    protected fun bg(block: () -> Unit) {
        Thread {
            try { block() } catch (e: Exception) { Log.w("PocketGLM", "background task failed: ${e.javaClass.simpleName}") }
        }.start()
    }

    protected fun post(block: () -> Unit) {
        main.post { if (!act.isDestroyed && !act.isFinishing) block() }
    }

    protected fun toast(s: String) = act.toast(s)
    protected fun key(): String = ApiKeyStore.get(ctx)

    /** Scrolling page: a title block, then whatever the caller puts into the returned column. */
    protected fun page(title: String, sub: String? = null): Pair<ScrollView, LinearLayout> {
        val box = Ui.vertical(ctx, 16)
        box.addView(TextView(ctx).apply {
            text = title
            textSize = 22f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Ui.resColor(ctx, R.color.fg))
            heading(this)
        })
        if (!sub.isNullOrEmpty()) box.addView(Ui.muted(ctx, sub).apply { setPadding(0, Ui.dip(ctx, 2), 0, 0) })
        val sv = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(box, android.view.ViewGroup.LayoutParams(Ui.MATCH, Ui.WRAP))
        }
        return sv to box
    }

    /** A title the screen reader announces as a heading (View.setAccessibilityHeading is API 28). */
    private fun heading(v: TextView) {
        if (android.os.Build.VERSION.SDK_INT >= 28) v.isAccessibilityHeading = true
    }

    /** Adds [v] to this column with [top] dp of space above it, keeping a fixed height (dividers, spacers). */
    protected fun <T : View> LinearLayout.put(v: T, top: Int = 12): T {
        val h = (v.layoutParams as? LinearLayout.LayoutParams)?.height ?: Ui.WRAP
        addView(v, LinearLayout.LayoutParams(Ui.MATCH, h).apply { topMargin = Ui.dip(ctx, top) })
        return v
    }

    /** Two equal tiles side by side. */
    protected fun tiles(a: View, b: View): LinearLayout = Ui.horizontal(ctx, false).apply {
        addView(a, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        addView(b, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { leftMargin = Ui.dip(ctx, 8) })
    }

    /** Full-width rule used between rows inside a card. */
    protected fun rule(): View = Ui.divider(ctx)
}
