package dev.zunvein.pocketglm

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
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        toggleButton = Button(this).apply { text = "▶ START bridge" }
        farmButton = Button(this).apply { text = "🌾 Open token farm" }
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
            addView(ScrollView(this@MainActivity).apply { addView(statusText) })
        }
        setContentView(root)

        toggleButton.setOnClickListener { toggleBridge() }
        farmButton.setOnClickListener {
            startActivity(Intent(this, TokenFarmActivity::class.java))
        }
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
        return try {
            val conn = URL("http://127.0.0.1:3001/health").openConnection() as HttpURLConnection
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            conn.requestMethod = "GET"
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            renderHealth(body)
        } catch (e: Exception) {
            "Bridge: starting… (no response yet)\n${e.javaClass.simpleName}"
        }
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
