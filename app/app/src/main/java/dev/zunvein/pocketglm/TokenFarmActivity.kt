package dev.zunvein.pocketglm

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The visible farm run: shows the page the WebView loads and a live log.
 * The engine is [Farmer]; this screen only hosts it. A successful run closes
 * the screen by itself, whether it was opened by the button or the top-up.
 * "Reload" starts the whole run again (a fresh page and a fresh session).
 */
class TokenFarmActivity : Activity() {

    private var farmer: Farmer? = null
    private lateinit var logView: TextView
    private lateinit var host: FrameLayout
    private val ui = Handler(Looper.getMainLooper())
    private var auto = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auto = intent.getBooleanExtra(EXTRA_AUTO, false)
        val pad = Ui.dp(this, 16)

        logView = Ui.mono(this)
        host = FrameLayout(this)
        val reload = Ui.button(this, "Reload", Ui.Kind.TONAL).apply {
            setOnClickListener { startFarm() }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.text(this@TokenFarmActivity, "Token farm", 18f, R.color.text, true).apply {
                setPadding(pad, pad, pad, Ui.dp(this@TokenFarmActivity, 8))
            })
            addView(reload, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply {
                setMargins(pad, 0, pad, Ui.dp(this@TokenFarmActivity, 8))
            })
            addView(host, LinearLayout.LayoutParams(Ui.MATCH, 0, 2f))
            addView(ScrollView(this@TokenFarmActivity).apply {
                addView(logView.apply { setPadding(pad, pad, pad, pad) })
            }, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        })
        startFarm()
    }

    private fun startFarm() {
        farmer?.destroy()
        farmer = null
        host.removeAllViews()
        logView.text = "Starting token farm..."
        if (FarmState.running) {
            logView.text = "Another farm run is already in progress (the background farm). Try again in a minute."
            return
        }
        val f = Farmer(
            this, Farmer.DEFAULT_MINT,
            log = { m -> runOnUiThread { logView.append("\n" + m) } },
            onDone = { ok, _ ->
                setResult(if (ok) RESULT_OK else RESULT_CANCELED)
                if (ok) ui.postDelayed({ if (!isFinishing) finish() }, 2500L)
                else if (auto) ui.postDelayed({ if (!isFinishing) finish() }, 6000L)
            },
        )
        farmer = f
        host.addView(f.webView, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        f.start()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        farmer?.destroy()
        farmer = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_AUTO = "auto"
    }
}
