package dev.zunvein.pocketglm

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * The visible farm run: the page the WebView loads and a live log. The engine
 * is [Farmer]; this screen only hosts it. Every farm line also lands in
 * bridge.log, so a run that closes by itself on success stays readable in Stats.
 */
class TokenFarmActivity : Activity() {

    private var farmer: Farmer? = null
    private lateinit var logView: TextView
    private lateinit var host: FrameLayout
    private val ui = Handler(Looper.getMainLooper())
    private var auto = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        auto = intent.getBooleanExtra(EXTRA_AUTO, false)
        val pad = Ui.dip(this, 16)
        val gap = Ui.dip(this, 8)

        logView = Ui.mono(this, "").apply {
            textSize = 11.5f
            setTextIsSelectable(true)
            background = Ui.thinkBg(this@TokenFarmActivity)
            setPadding(pad, pad, pad, pad)
        }
        host = FrameLayout(this)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, gap)
            addView(Ui.title(this@TokenFarmActivity, "Token farm"), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            addView(Ui.small(this@TokenFarmActivity, "Reload") { startFarm() })
        }

        val copy = Ui.small(this, "Copy log") { copyLog() }
        val share = Ui.small(this, "Share log") { shareLog() }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, gap, pad, pad)
            addView(copy, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { rightMargin = gap })
            addView(share, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.resColor(this@TokenFarmActivity, R.color.bg))
            addView(header)
            addView(host, LinearLayout.LayoutParams(Ui.MATCH, 0, 2f))
            addView(ScrollView(this@TokenFarmActivity).apply {
                isVerticalScrollBarEnabled = false
                addView(logView, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
            }, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
            addView(actions)
        })
        startFarm()
    }

    private fun startFarm() {
        farmer?.destroy()
        farmer = null
        host.removeAllViews()
        logView.text = "Starting the token farm…"
        if (FarmState.running) {
            logView.text = "Another farm run is already going (the background farm). Try again in a minute."
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
        startActivity(Intent.createChooser(send, "Share the farm log"))
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
