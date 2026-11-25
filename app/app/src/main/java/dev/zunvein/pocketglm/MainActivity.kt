package dev.zunvein.pocketglm

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Shell of the app: a content area and a bottom bar with five destinations.
 * Screens live in their own classes and are built once, so tab switches keep
 * their state.
 */
class MainActivity : Activity() {

    private class Tab(val name: String, val icon: Int, val screen: Screen) {
        lateinit var item: LinearLayout
        lateinit var iconView: ImageView
        lateinit var label: TextView
        lateinit var pill: View
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var tabs: List<Tab>
    private lateinit var content: FrameLayout
    private lateinit var nav: LinearLayout
    private var current = -1
    private var setupShown = false

    private val pulse = object : Runnable {
        override fun run() {
            tabs.getOrNull(current)?.screen?.onTick()
            handler.postDelayed(this, 5000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)

        tabs = listOf(
            Tab("Home", R.drawable.ic_home, HomeScreen(this)),
            Tab("Stats", R.drawable.ic_stats, StatsScreen(this)),
            Tab("Accounts", R.drawable.ic_accounts, AccountsScreen(this)),
            Tab("Chat", R.drawable.ic_chat, ChatScreen(this)),
            Tab("Settings", R.drawable.ic_settings, SettingsScreen(this))
        )

        content = FrameLayout(this)
        nav = buildNav()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.resColor(this@MainActivity, R.color.bg))
            addView(content, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
            addView(Ui.divider(this@MainActivity))
            addView(nav, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        }
        setContentView(root)
        hideNavWhenTyping(root)
        val start = savedInstanceState?.getInt("tab", 0) ?: 0
        select(if (start in tabs.indices) start else 0)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", current)
    }

    override fun onResume() {
        super.onResume()
        handler.post(pulse)
        val fresh = !Prefs.setupDone(this)
        if (fresh) {
            if (AccountStore.list(this).isNotEmpty()) {
                Prefs.setSetupDone(this, true)
            } else if (!setupShown) {
                setupShown = true
                startActivity(Intent(this, SetupActivity::class.java))
            }
        }
        tabs.getOrNull(current)?.screen?.onResume()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(pulse)
    }

    private fun buildNav(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Ui.resColor(this@MainActivity, R.color.card))
        }
        tabs.forEachIndexed { i, t ->
            val icon = ImageView(this).apply {
                setImageResource(t.icon)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            // The highlight is its own view so it can grow in and shrink out.
            val pill = View(this).apply {
                background = Ui.pillBg(this@MainActivity, Ui.resColor(this@MainActivity, R.color.accentSoft))
                alpha = 0f
                scaleX = 0.35f
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val iconBox = FrameLayout(this).apply {
                addView(pill, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
                addView(icon, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
            }
            val label = TextView(this).apply {
                text = t.name
                textSize = 11.5f
                gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                minimumHeight = Ui.dip(this@MainActivity, 64)
                setPadding(0, Ui.dip(this@MainActivity, 8), 0, Ui.dip(this@MainActivity, 8))
                isClickable = true
                isFocusable = true
                contentDescription = t.name
                setOnClickListener { select(i) }
                addView(iconBox, LinearLayout.LayoutParams(Ui.dip(this@MainActivity, 56), Ui.dip(this@MainActivity, 30)).apply { gravity = Gravity.CENTER_HORIZONTAL })
                addView(label, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { topMargin = Ui.dip(this@MainActivity, 2) })
            }
            t.item = item; t.iconView = icon; t.label = label; t.pill = pill
            bar.addView(item, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        }
        return bar
    }

    private fun tint(from: Int, to: Int, apply: (Int) -> Unit) {
        android.animation.ValueAnimator.ofObject(android.animation.ArgbEvaluator(), from, to).apply {
            duration = 180
            addUpdateListener { apply(it.animatedValue as Int) }
            start()
        }
    }

    fun select(i: Int) {
        if (i == current) return
        tabs.getOrNull(current)?.screen?.active = false
        val first = current == -1
        current = i
        content.removeAllViews()
        val s = tabs[i].screen
        content.addView(s.root, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        s.animateIn()
        s.active = true
        s.onShow()
        tabs.forEachIndexed { j, t ->
            val on = j == i
            val iconTo = Ui.resColor(this, if (on) R.color.accentFg else R.color.muted)
            val labelTo = Ui.resColor(this, if (on) R.color.fg else R.color.muted)
            t.item.isSelected = on
            t.item.contentDescription = t.name + if (on) ", selected" else ""
            if (first) {
                t.iconView.setColorFilter(iconTo)
                t.label.setTextColor(labelTo)
                t.pill.alpha = if (on) 1f else 0f
                t.pill.scaleX = if (on) 1f else 0.35f
                return@forEachIndexed
            }
            val iconFrom = Ui.resColor(this, if (on) R.color.muted else R.color.accentFg)
            val labelFrom = Ui.resColor(this, if (on) R.color.muted else R.color.fg)
            tint(iconFrom, iconTo) { t.iconView.setColorFilter(it) }
            tint(labelFrom, labelTo) { t.label.setTextColor(it) }
            t.pill.animate().cancel()
            t.pill.animate().alpha(if (on) 1f else 0f).scaleX(if (on) 1f else 0.35f).setDuration(if (on) 260 else 160)
                .setInterpolator(if (on) android.view.animation.OvershootInterpolator(1.1f) else android.view.animation.DecelerateInterpolator()).start()
            if (on) {
                t.iconView.scaleX = 0.8f; t.iconView.scaleY = 0.8f
                t.iconView.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(android.view.animation.OvershootInterpolator(2f)).start()
            }
        }
    }

    /** The soft keyboard shrinks the window; hide the bar so the input keeps its room. */
    private fun hideNavWhenTyping(root: View) {
        val frame = Rect()
        root.viewTreeObserver.addOnGlobalLayoutListener {
            root.getWindowVisibleDisplayFrame(frame)
            val full = root.rootView.height
            val keyboard = full - frame.bottom > full * 0.15
            val v = if (keyboard) View.GONE else View.VISIBLE
            if (nav.visibility != v) nav.visibility = v
        }
    }

    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    /** Ask once, when the bridge is first started: the foreground service needs it to show its notification. */
    fun askNotifications() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    /** Copies [text]; secrets are flagged sensitive (hidden from clipboard previews) and wiped after [clearAfterSec]. */
    fun copy(label: String, text: String, sensitive: Boolean = false, clearAfterSec: Int = 0) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        if (sensitive && Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        cm.setPrimaryClip(clip)
        if (clearAfterSec > 0) {
            handler.postDelayed({
                try {
                    if (cm.primaryClipDescription?.label == label && Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip()
                } catch (ignored: Exception) {
                }
            }, clearAfterSec * 1000L)
        }
    }

    companion object {
        const val LOG_AUTHORITY = "dev.zunvein.pocketglm.fileprovider"
    }
}
