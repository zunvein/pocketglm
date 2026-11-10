package dev.zunvein.pocketglm

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * The visible farm run: the page the WebView loads and a live log.
 * The engine is [Farmer]; this screen only hosts it.
 *
 * Every farm line is also appended to bridge.log, so a run that closes by
 * itself on success is still readable from the Logs tab.
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
        val gap = Ui.dp(this, 8)

        logView = Ui.mono(this)
        host = FrameLayout(this)

        val title = Ui.text(this, "Token farm", 18f, R.color.text, true)
        val reload = Ui.compact(this, "Reload").apply { setOnClickListener { startFarm() } }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, gap)
            addView(title, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            addView(reload)
        }

        val copy = Ui.compact(this, "Copy log").apply { setOnClickListener { copyLog() } }
        val share = Ui.compact(this, "Share log").apply { setOnClickListener { shareLog() } }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, gap, pad, pad)
            addView(copy, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { marginEnd = gap })
            addView(share, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(host, LinearLayout.LayoutParams(Ui.MATCH, 0, 2f))
            addView(ScrollView(this@TokenFarmActivity).apply {
                addView(logView.apply { setPadding(pad, pad, pad, pad) })
            }, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
            addView(actions)
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
            log = { m ->
                runOnUiThread { logView.append("\n" + m) }
                LogFile.append(this, "[farm] $m")
            },
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

    private fun copyLog() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("pocketglm-farm", Redact.text(this, logView.text.toString())))
        Toast.makeText(this, "Farm log copied", Toast.LENGTH_SHORT).show()
    }

    private fun shareLog() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, Redact.text(this@TokenFarmActivity, logView.text.toString()))
        }
        startActivity(Intent.createChooser(send, "Share farm log"))
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
