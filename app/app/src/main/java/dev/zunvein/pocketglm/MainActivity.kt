package dev.zunvein.pocketglm

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
    private var resumed = false
    // Written on the IO thread by renderHealth, read on the main thread.
    @Volatile private var lastTokenCount = -1
    private var lastTopUpAt = 0L
    private lateinit var autoSwitch: android.widget.Switch

    companion object {
        private const val PREF_AUTO_TOPUP = "auto_topup"
        private const val LOW_POOL = 3
    }

    private lateinit var toggleButton: Button
    private lateinit var farmButton: Button
    private lateinit var copyButton: Button
    private lateinit var statusText: TextView
    private lateinit var keyText: TextView
    private lateinit var endpointText: TextView
    private lateinit var curlText: TextView
    private lateinit var testResult: TextView

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

        val dp = resources.displayMetrics.density
        fun heading(t: String) = TextView(this).apply {
            text = t
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, (16 * dp).toInt(), 0, (4 * dp).toInt())
        }
        fun mono(selectable: Boolean = true) = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(selectable)
        }

        endpointText = mono()
        keyText = mono()
        curlText = mono()
        testResult = mono().apply { text = "" }

        autoSwitch = android.widget.Switch(this).apply {
            text = "Auto top-up when tokens run low"
            isChecked = prefs().getBoolean(PREF_AUTO_TOPUP, true)
            setOnCheckedChangeListener { _, on ->
                prefs().edit().putBoolean(PREF_AUTO_TOPUP, on).apply()
            }
        }

        val copyKeyButton = Button(this).apply { text = "Copy key" }
        val newKeyButton = Button(this).apply { text = "Generate new key" }
        val customKeyButton = Button(this).apply { text = "Set custom key" }
        val copyCurlButton = Button(this).apply { text = "Copy curl" }
        val testButton = Button(this).apply { text = "Test endpoint" }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * dp).toInt()
            setPadding(pad, pad, pad, pad)
            addView(toggleButton)
            addView(farmButton)
            addView(autoSwitch)
            addView(copyButton)
            addView(statusText)

            addView(heading("API access"))
            addView(endpointText)
            addView(heading("API key"))
            addView(keyText)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(copyKeyButton, lp)
                addView(newKeyButton, lp)
                addView(customKeyButton, lp)
            })
            addView(heading("Example request"))
            addView(curlText)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(copyCurlButton, lp)
                addView(testButton, lp)
            })
            addView(testResult)
        }
        setContentView(ScrollView(this).apply { addView(content) })

        refreshApiCard()
        copyKeyButton.setOnClickListener { copyText("pocketglm-key", ApiKeyStore.get(this), "Key copied") }
        copyCurlButton.setOnClickListener { copyText("pocketglm-curl", curlText.text.toString(), "curl copied") }
        newKeyButton.setOnClickListener { confirmRegenerate() }
        customKeyButton.setOnClickListener { promptCustomKey() }
        testButton.setOnClickListener { testEndpoint() }

        toggleButton.setOnClickListener { toggleBridge() }
        farmButton.setOnClickListener {
            startActivity(Intent(this, TokenFarmActivity::class.java))
        }
        copyButton.setOnClickListener { copyAllLogs() }
    }

    // ── API access card ──────────────────────────────────────────────────

    /** First non-loopback IPv4 address of the active network (Wi-Fi LAN IP). */
    private fun lanIp(): String? {
        return try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val link = cm.getLinkProperties(cm.activeNetwork)
            link?.linkAddresses
                ?.map { it.address }
                ?.firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }
                ?.hostAddress
        } catch (e: Exception) {
            null
        }
    }

    private fun refreshApiCard() {
        val key = ApiKeyStore.get(this)
        val ip = lanIp()
        val lan = ip ?: "<phone-ip>"
        endpointText.text = buildString {
            append("This phone (apps on the same device):\n")
            append("  OpenAI    http://127.0.0.1:3001/v1\n")
            append("  Anthropic http://127.0.0.1:3001\n\n")
            append("Other devices on the same Wi-Fi:\n")
            append("  OpenAI    http://$lan:3001/v1\n")
            append("  Anthropic http://$lan:3001\n")
            if (ip == null) append("  (no Wi-Fi address found — connect to Wi-Fi first)\n")
            append("\nPaths: /v1/chat/completions, /v1/messages, /v1/models")
        }
        keyText.text = key
        curlText.text = "curl http://$lan:3001/v1/chat/completions \\\n" +
            "  -H \"Authorization: Bearer $key\" \\\n" +
            "  -H \"Content-Type: application/json\" \\\n" +
            "  -d '{\"model\":\"glm-5.2\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}'"
    }

    private fun copyText(label: String, text: String, toast: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show()
    }

    /** A running bridge only reads AUTH_TOKEN at start, so it must restart. */
    private fun applyKeyChange() {
        refreshApiCard()
        if (bridgeAlive) {
            startForegroundService(Intent(this, BridgeService::class.java).apply {
                action = BridgeService.ACTION_RESTART
            })
            setBridgeState(true, delay = true)
            Toast.makeText(this, "Key changed — restarting bridge", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Key changed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmRegenerate() {
        AlertDialog.Builder(this)
            .setTitle("Generate new key?")
            .setMessage("The old key stops working immediately" +
                if (bridgeAlive) " and the bridge restarts." else ".")
            .setPositiveButton("Generate") { _, _ ->
                ApiKeyStore.regenerate(this)
                applyKeyChange()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptCustomKey() {
        val input = EditText(this).apply {
            hint = "8–128 chars, no spaces or commas"
            setText(ApiKeyStore.get(this@MainActivity))
        }
        AlertDialog.Builder(this)
            .setTitle("Set custom key")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                if (ApiKeyStore.set(this, input.text.toString())) {
                    applyKeyChange()
                } else {
                    Toast.makeText(this, "Invalid key (8–128 chars, no spaces or commas)", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** GET /v1/models with the current key — proves auth + routing work. */
    private fun testEndpoint() {
        testResult.text = "Testing…"
        val key = ApiKeyStore.get(this)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val conn = URL("http://127.0.0.1:3001/v1/models")
                        .openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
                    conn.connectTimeout = 2000
                    conn.readTimeout = 20000
                    conn.setRequestProperty("Authorization", "Bearer $key")
                    val code = conn.responseCode
                    val stream = if (code >= 400) conn.errorStream else conn.inputStream
                    val body = stream?.bufferedReader()?.readText().orEmpty()
                    conn.disconnect()
                    "HTTP $code\n" + body.take(400)
                } catch (e: Exception) {
                    "Failed: ${e.javaClass.simpleName}: ${e.message}\n(is the bridge started?)"
                }
            }
            testResult.text = result
        }
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
                maybeTopUp()
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
            // Read the body even for non-2xx: the bridge answers /health with
            // 503 while its Z.AI session is still initializing (DNS/warmup),
            // and that JSON is exactly what the status card should render
            // instead of a misleading "FileNotFoundException".
            val body = try {
                conn.inputStream.bufferedReader().readText()
            } catch (e: Exception) {
                conn.errorStream?.bufferedReader()?.readText()
                    ?: throw e
            }
            val code = conn.responseCode
            conn.disconnect()
            renderHealth(body, code)
        } catch (e: Exception) {
            lastTokenCount = -1
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

    private fun renderHealth(body: String, code: Int = 200): String {
        return try {
            val o = JSONObject(body)
            val captcha = o.optJSONObject("captcha")
            val sb = StringBuilder(if (code == 200) "Bridge: UP\n" else "Bridge: UP (warming up, HTTP $code)\n")
            sb.append("healthy: ").append(o.optBoolean("healthy")).append('\n')
            lastTokenCount = o.optInt("tokenCount", -1)
            sb.append("tokens: ").append(lastTokenCount).append('\n')
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

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    /**
     * While the app is open and the bridge is up, open the farm on its own
     * when the pool is nearly empty. A cool-down keeps a failing farm from
     * reopening in a loop: 2 min after a success, 10 min after a failure.
     */
    private fun maybeTopUp() {
        if (!resumed || !bridgeAlive || !autoSwitch.isChecked) return
        if (lastTokenCount < 0 || lastTokenCount > LOW_POOL) return
        if (TokenFarmActivity.running) return
        val now = System.currentTimeMillis()
        val cooldown = if (TokenFarmActivity.lastResultOk) 2 * 60_000L else 10 * 60_000L
        val last = maxOf(lastTopUpAt, TokenFarmActivity.lastFinishedAt)
        if (last != 0L && now - last < cooldown) return
        lastTopUpAt = now
        startActivity(Intent(this, TokenFarmActivity::class.java).apply {
            putExtra(TokenFarmActivity.EXTRA_AUTO, true)
        })
    }

    private fun prefs() = getSharedPreferences("pocketglm", Context.MODE_PRIVATE)

    override fun onResume() {
        super.onResume()
        resumed = true
        // Wi-Fi address may have changed while the app was in the background.
        refreshApiCard()
    }

    override fun onDestroy() {
        pollJob?.cancel()
        scope.cancel()
        // The bridge keeps running while the app is open in background —
        // stopping it is an explicit action, matching the on-demand model.
        super.onDestroy()
    }
}
