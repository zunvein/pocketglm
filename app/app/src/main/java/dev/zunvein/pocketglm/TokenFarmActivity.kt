package dev.zunvein.pocketglm

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The visible farm run: shows the page the WebView loads and a live log.
 * The engine is [Farmer]; this screen only hosts it.
 */
class TokenFarmActivity : Activity() {

    private var farmer: Farmer? = null
    private lateinit var logView: TextView
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val auto = intent.getBooleanExtra(EXTRA_AUTO, false)
        val pad = Ui.dp(this, 16)

        logView = Ui.mono(this).apply { text = "Starting token farm..." }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.text(this@TokenFarmActivity, "Token farm", 18f, R.color.text, true).apply {
                setPadding(pad, pad, pad, Ui.dp(this@TokenFarmActivity, 8))
            })
        }

        if (FarmState.running) {
            logView.text = "Another farm run is already in progress (the background farm). Try again in a minute."
            root.addView(logView.apply { setPadding(pad, 0, pad, pad) })
            setContentView(root)
            return
        }

        val f = Farmer(
            this, Farmer.DEFAULT_MINT,
            log = { m -> runOnUiThread { logView.append("\n" + m) } },
            onDone = { ok, _ ->
                setResult(if (ok) RESULT_OK else RESULT_CANCELED)
                if (auto) ui.postDelayed({ if (!isFinishing) finish() }, if (ok) 2500L else 6000L)
            },
        )
        farmer = f
        root.addView(f.webView, LinearLayout.LayoutParams(Ui.MATCH, 0, 2f))
        root.addView(ScrollView(this).apply {
            addView(logView.apply { setPadding(pad, pad, pad, pad) })
        }, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        setContentView(root)
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
