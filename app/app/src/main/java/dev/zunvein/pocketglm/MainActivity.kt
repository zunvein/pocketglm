package dev.zunvein.pocketglm

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Single-screen control panel for the PocketGLM bridge.
 *
 *  - START/STOP toggles the foreground service that owns the Go process.
 *  - A status card polls the bridge's /health endpoint every 2 s while the
 *    process is up (breaker state, pool size, standby readiness).
 *  - "Open token farm" launches the WebView harvester activity (P1).
 *
 * Deliberately plain programmatic UI — no XML layouts — to keep the shell thin.
 */
class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var bridgeAlive = false
    private var pollJob: Job? = null

    private lateinit var toggleButton: Button
    private lateinit var farmButton: Button
    private lateinit var copyButton: Button
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        toggleButton = Button(this).apply { text = "▶ START bridge" }
        farmButton = Button(this).apply { text = "🌾 Open token farm" }
        copyButton = Button(this).apply { text = "📋 Copy all logs" }
        statusText = TextView(this).apply {
            setTextIsSelectable(true)
            setPadding(48, 24, 48, 24)
            text = "Bridge: stopped"
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(toggleButton)
            addView(farmButton)
            addView(copyButton)
            addView(ScrollView(this@MainActivity).apply { addView(statusText) })
        }
        setContentView(root)

        toggleButton.setOnClickListener { toggleBridge() }
        farmButton.setOnClickListener {
            startActivity(Intent(this, TokenFarmActivity::class.java))
        }
        copyButton.setOnClickListener { copyAllLogs() }
    }

    /**
     * One-tap bug report: current status card + the FULL bridge.log into the
     * clipboard, so a failure can be pasted straight into a chat without
     * fighting with text selection inside the ScrollView.
     */
    private fun copyAllLogs() {
        val sb = StringBuilder()
        sb.append(statusText.text)
        val log = File(filesDir, "bridge/bridge.log")
        if (log.exists()) {
            sb.append("\n\n—— bridge.log (full) ——\n").append(log.readText())
        } else {
            sb.append("\n\n(bridge.log does not exist yet)")
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("pocketglm-logs", sb.toString()))
        copyButton.text = "✓ Copied to clipboard"
        copyButton.postDelayed({ copyButton.text = "📋 Copy all logs" }, 2000)
    }

    private fun toggleBridge() {
        if (bridgeAlive) {
            startService(Intent(this, BridgeService::class.java).apply {
                action = BridgeService.ACTION_STOP
            })
            setBridgeState(false)
        } else {
            startForegroundService(Intent(this, BridgeService::class.java).apply {
                action = BridgeService.ACTION_START
            })
            // give the process a moment before the first poll
            setBridgeState(true, delay = true)
        }
    }

    private fun setBridgeState(alive: Boolean, delay: Boolean = false) {
        bridgeAlive = alive
        toggleButton.text = if (alive) "■ STOP bridge" else "▶ START bridge"
        if (alive) {
            startPolling(delay)
        } else {
            pollJob?.cancel()
            statusText.text = "Bridge: stopped"
        }
    }

    private fun startPolling(initialDelay: Boolean) {
        pollJob?.cancel()
        pollJob = scope.launch {
            if (initialDelay) delay(2500)
            while (isActive) {
                val status = withContext(Dispatchers.IO) { fetchHealth() }
                statusText.text = status
                delay(2000)
            }
        }
    }

    /** Fetch GET /health from the local bridge and render a compact summary. */
    private fun fetchHealth(): String {
        val health = try {
            val conn = java.net.Proxy.NO_PROXY.let { urlProxied ->
                URL("http://127.0.0.1:3001/health").openConnection(urlProxied) as HttpURLConnection
            }
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            conn.requestMethod = "GET"
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            renderHealth(body)
        } catch (e: Exception) {
            "Bridge: starting… (no response yet)\n${e.javaClass.simpleName}: ${e.message}"
        }
        // Append the tail of bridge.log when the bridge is not up yet —
        // this surfaces extraction/exec failures that never reach /health.
        if (!health.startsWith("Bridge: UP")) {
            val log = File(filesDir, "bridge/bridge.log")
            if (log.exists()) {
                val tail = log.readText().lines().takeLast(8).joinToString("\n")
                return health + "\n\n—— bridge.log tail ——\n" + tail
            }
        }
        return health
    }

    private fun renderHealth(body: String): String {
        return try {
            val o = JSONObject(body)
            val captcha = o.optJSONObject("captcha")
            val sb = StringBuilder("Bridge: UP\n")
            sb.append("healthy: ").append(o.optBoolean("healthy")).append('\n')
            sb.append("tokens: ").append(o.optInt("tokenCount", -1)).append('\n')
            if (captcha != null) {
                sb.append("breaker: ").append(captcha.optString("breaker")).append('\n')
                sb.append("computeOK: ").append(captcha.optLong("computeOK", 0)).append('\n')
                sb.append("computeFail: ").append(captcha.optLong("computeFail", 0)).append('\n')
                sb.append("standbyReady: ").append(captcha.optBoolean("standbyReady")).append('\n')
                sb.append("poolAgeSec: ").append(captcha.optLong("poolAgeSec", -1)).append('\n')
            }
            sb.toString()
        } catch (e: Exception) {
            "Bridge: UP (unparsed)\n$body"
        }
    }

    override fun onDestroy() {
        pollJob?.cancel()
        scope.cancel()
        // The bridge keeps running while the app is open in background —
        // stopping it is an explicit action, matching the on-demand model.
        super.onDestroy()
    }
}
