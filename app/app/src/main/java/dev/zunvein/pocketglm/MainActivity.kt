package dev.zunvein.pocketglm

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Five tabs over plain views (no Compose, no Material): Home (bridge + token
 * supply), Chat (test box), API (endpoint + key), Accounts, Logs. Colors come
 * from the day/night resources, so dark mode follows the system.
 */
class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pollJob: Job? = null
    private var resumed = false
    private var health = BridgeClient.DOWN
    private var startingUntil = 0L
    private var lastTopUpAt = 0L
    private var tab = 0
    private var keyShown = false

    private lateinit var pages: List<View>
    private lateinit var tabLabels: List<TextView>
    private lateinit var tabIcons: List<android.widget.ImageView>
    private lateinit var chatPage: ChatPage
    private var setupShown = false
    private val emailShown = HashSet<String>()

    // Home
    private lateinit var statusPill: TextView
    private lateinit var toggleButton: android.widget.Button
    private lateinit var hintText: TextView
    private lateinit var tokensValue: TextView
    private lateinit var okValue: TextView
    private lateinit var failValue: TextView
    private lateinit var breakerValue: TextView
    private lateinit var poolAgeValue: TextView
    private lateinit var bgStatusText: TextView
    private lateinit var batteryText: TextView
    private lateinit var batteryButton: android.widget.Button

    // API
    private lateinit var endpointText: TextView
    private lateinit var keyText: TextView
    private lateinit var curlText: TextView
    private lateinit var testResult: TextView
    private lateinit var showKeyButton: android.widget.Button
    private lateinit var linkPill: TextView
    private lateinit var linkText: TextView
    private lateinit var linkSwitch: SwitchCompat
    private lateinit var linkCopy: android.widget.Button
    private var linkStatus = TunnelClient.UNKNOWN
    private var syncingLink = false

    // Accounts
    private lateinit var accountList: LinearLayout
    private lateinit var applyButton: android.widget.Button

    // Logs
    private lateinit var logText: TextView
    private lateinit var logsScroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        chatPage = ChatPage(this, scope) { health }
        pages = listOf(buildHome(), chatPage.view, buildApi(), buildAccounts(), buildLogs())
        val content = FrameLayout(this).apply { pages.forEach { addView(it) } }

        val names = listOf("Home", "Chat", "API", "Accounts", "Logs")
        val icons = listOf(R.drawable.ic_home, R.drawable.ic_chat, R.drawable.ic_code, R.drawable.ic_person, R.drawable.ic_logs)
        val labels = ArrayList<TextView>()
        val iconViews = ArrayList<android.widget.ImageView>()
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Ui.color(this@MainActivity, R.color.surface))
            names.forEachIndexed { i, n ->
                val icon = android.widget.ImageView(this@MainActivity).apply {
                    setImageResource(icons[i])
                    contentDescription = null
                }
                val label = Ui.text(this@MainActivity, n, 11f, R.color.textDim, true).apply { gravity = Gravity.CENTER }
                labels.add(label); iconViews.add(icon)
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    minimumHeight = Ui.dp(this@MainActivity, 60)
                    contentDescription = n
                    isClickable = true
                    addView(icon, LinearLayout.LayoutParams(Ui.dp(this@MainActivity, 24), Ui.dp(this@MainActivity, 24)))
                    addView(label)
                    setOnClickListener { selectTab(i) }
                }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            }
        }
        tabLabels = labels
        tabIcons = iconViews
        val divider = View(this).apply { setBackgroundColor(Ui.color(this@MainActivity, R.color.stroke)) }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(content, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
            addView(divider, LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(this@MainActivity, 1)))
            addView(bar, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        })

        selectTab(0)
        renderApi()
        renderAccounts()
        renderHome()
    }

    private fun selectTab(i: Int) {
        tab = i
        pages.forEachIndexed { idx, v -> v.visibility = if (idx == i) View.VISIBLE else View.GONE }
        tabLabels.forEachIndexed { idx, t ->
            val c = Ui.color(this, if (idx == i) R.color.accent else R.color.textDim)
            t.setTextColor(c)
            tabIcons[idx].setColorFilter(c)
        }
        if (i == 1) chatPage.onShown()
        if (i == 3) renderAccounts()
        if (i == 4) refreshLogs()
    }

    private fun page(vararg views: View): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(this@MainActivity, 16)
            setPadding(p, p, p, p)
        }
        views.forEach {
            col.addView(it, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { bottomMargin = Ui.dp(this@MainActivity, 12) })
        }
        return ScrollView(this).apply { addView(col) }
    }

    private fun row(vararg views: Pair<View, Float>): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        views.forEach { (v, w) ->
            addView(v, if (w > 0f) LinearLayout.LayoutParams(0, Ui.WRAP, w) else LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP))
        }
    }

    private fun buttonRow(vararg buttons: android.widget.Button): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        val gap = Ui.dp(this@MainActivity, 8)
        buttons.forEachIndexed { i, b ->
            addView(b, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { if (i > 0) marginStart = gap })
        }
    }

    // ── Home ─────────────────────────────────────────────────────────────

    private fun buildHome(): View {
        statusPill = Ui.pill(this, "Stopped", R.color.textDim)
        toggleButton = Ui.button(this, "Start bridge", Ui.Kind.PRIMARY).apply { setOnClickListener { toggleBridge() } }
        hintText = Ui.text(this, "", 13f, R.color.textDim)

        val (tokensRow, tv) = Ui.statRow(this, "Device tokens"); tokensValue = tv
        val (okRow, ov) = Ui.statRow(this, "Chats answered"); okValue = ov
        val (failRow, fv) = Ui.statRow(this, "Chats failed"); failValue = fv
        val (breakerRow, bv) = Ui.statRow(this, "Breaker"); breakerValue = bv
        val (ageRow, av) = Ui.statRow(this, "Pool age"); poolAgeValue = av

        val bridgeCard = Ui.card(this).apply {
            addView(row(Ui.text(this@MainActivity, "Bridge", 18f, R.color.text, true) to 1f, statusPill to 0f))
            addView(Ui.gap(this@MainActivity, 8))
            addView(tokensRow); addView(okRow); addView(failRow); addView(breakerRow); addView(ageRow)
            addView(hintText)
            addView(Ui.gap(this@MainActivity, 8))
            addView(toggleButton)
        }

        val farmNow = Ui.button(this, "Farm tokens now", Ui.Kind.TONAL).apply {
            setOnClickListener { startActivity(Intent(this@MainActivity, TokenFarmActivity::class.java)) }
        }
        val (autoRow, _) = Ui.switchRow(
            this, "Auto top-up",
            "Opens the farm by itself when tokens run low, while this app is open.",
            Prefs.autoTopUp(this)
        ) { Prefs.setAutoTopUp(this, it) }
        val (bgRow, _) = Ui.switchRow(
            this, "Background farm (experimental)",
            "Farms with a hidden browser while the bridge runs, even with the app closed. May not work on every phone.",
            Prefs.bgFarm(this)
        ) {
            Prefs.setBgFarm(this, it)
            FarmState.bgFailStreak = 0
            renderHome()
        }
        bgStatusText = Ui.text(this, "", 13f, R.color.textDim)

        val farmCard = Ui.card(this).apply {
            addView(Ui.text(this@MainActivity, "Token supply", 18f, R.color.text, true))
            addView(Ui.text(this@MainActivity, "Every chat uses one device token. One farm run adds about ${Farmer.DEFAULT_MINT + 1}.", 13f, R.color.textDim))
            addView(Ui.gap(this@MainActivity, 12))
            addView(farmNow)
            addView(autoRow)
            addView(bgRow)
            addView(bgStatusText)
        }

        val (awakeRow, _) = Ui.switchRow(
            this, "Keep CPU and Wi-Fi awake",
            "Holds a wake lock while the bridge runs, so other devices can reach it with the screen off.",
            Prefs.keepAwake(this)
        ) { on ->
            Prefs.setKeepAwake(this, on)
            if (health.up) Toast.makeText(this, "Applies the next time the bridge starts", Toast.LENGTH_SHORT).show()
        }
        batteryText = Ui.text(this, "", 13f, R.color.textDim)
        batteryButton = Ui.button(this, "Allow unrestricted battery", Ui.Kind.TONAL).apply {
            setOnClickListener { requestBatteryExemption() }
        }
        val keepCard = Ui.card(this).apply {
            addView(Ui.text(this@MainActivity, "Keep running", 18f, R.color.text, true))
            addView(Ui.gap(this@MainActivity, 4))
            addView(awakeRow)
            addView(batteryText)
            addView(Ui.gap(this@MainActivity, 8))
            addView(batteryButton)
            addView(Ui.gap(this@MainActivity, 8))
            addView(Ui.text(this@MainActivity, "Xiaomi, Oppo, Vivo, Huawei and Samsung phones also have their own autostart or battery list. If the bridge still gets killed, allow PocketGLM there.", 12f, R.color.textDim))
        }
        val (bypassRow, _) = Ui.switchRow(
            this, "Bypass blocking",
            "For networks that cut the connection to chat.z.ai. Splits the first packet of each secure connection in two. Restarts the bridge.",
            Prefs.bypass(this)
        ) { on ->
            Prefs.setBypass(this, on)
            if (isUp() || isStarting()) {
                restartBridge()
                Toast.makeText(this, "Restarting the bridge", Toast.LENGTH_SHORT).show()
            }
        }
        val netCard = Ui.card(this).apply {
            addView(Ui.text(this@MainActivity, "Blocked network", 18f, R.color.text, true))
            addView(Ui.gap(this@MainActivity, 4))
            addView(bypassRow)
            addView(Ui.gap(this@MainActivity, 4))
            addView(Ui.text(this@MainActivity, "Not guaranteed: it only helps when the block reads the site name from the first packet. A VPN always works.", 12f, R.color.textDim))
        }
        return page(bridgeCard, farmCard, netCard, keepCard)
    }

    private fun isUp() = health.up
    private fun isStarting() = !health.up && System.currentTimeMillis() < startingUntil

    private fun renderHome() {
        val h = health
        when {
            h.up && !h.warming -> statusPill.setPill("Running", R.color.good)
            h.up -> statusPill.setPill("Warming up", R.color.warn)
            isStarting() -> statusPill.setPill("Starting", R.color.warn)
            else -> statusPill.setPill("Stopped", R.color.textDim)
        }
        val running = h.up || isStarting()
        toggleButton.text = if (running) "Stop bridge" else "Start bridge"
        toggleButton.style(if (running) Ui.Kind.DANGER else Ui.Kind.PRIMARY)

        tokensValue.text = if (h.up && h.tokens >= 0) h.tokens.toString() else "-"
        tokensValue.setTextColor(Ui.color(this, if (h.up && h.tokens in 0..3) R.color.warn else R.color.text))
        okValue.text = if (h.up) h.computeOk.toString() else "-"
        failValue.text = if (h.up) h.computeFail.toString() else "-"
        breakerValue.text = if (h.up && h.breaker.isNotEmpty()) h.breaker else "-"
        poolAgeValue.text = if (h.up && h.poolAgeSec >= 0) formatAge(h.poolAgeSec) else "-"
        hintText.text = when {
            h.up && h.tokens == 0 -> "The pool is empty. Chats fail until you farm tokens."
            h.up && h.warming -> "The bridge is up and still setting up its Z.AI session."
            isStarting() -> "Starting the bridge..."
            !h.up && h.error != null && startingUntil != 0L -> "No answer yet. The Logs tab shows what the bridge says."
            else -> ""
        }
        hintText.visibility = if (hintText.text.isEmpty()) View.GONE else View.VISIBLE

        val bg = Prefs.bgStatus(this)
        bgStatusText.text = if (Prefs.bgFarm(this)) {
            "Background farm: " + (bg.ifEmpty { "waiting for the pool to run low" })
        } else ""
        bgStatusText.visibility = if (bgStatusText.text.isEmpty()) View.GONE else View.VISIBLE

        val ignoring = batteryExempt()
        batteryText.text = if (ignoring) "Battery: unrestricted for PocketGLM." else "Battery: Android may stop the bridge when the screen is off."
        batteryButton.visibility = if (ignoring) View.GONE else View.VISIBLE
        applyButton.visibility = if (isUp()) View.VISIBLE else View.GONE
    }

    private fun formatAge(sec: Long): String = when {
        sec < 90 -> "${sec}s"
        sec < 5400 -> "${sec / 60}m"
        else -> "${sec / 3600}h ${(sec % 3600) / 60}m"
    }

    private fun batteryExempt(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun requestBatteryExemption() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                Toast.makeText(this, "Open Settings > Apps > PocketGLM > Battery and choose Unrestricted", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun toggleBridge() {
        if (isUp() || isStarting()) {
            startService(Intent(this, BridgeService::class.java).apply { action = BridgeService.ACTION_STOP })
            startingUntil = 0L
            health = BridgeClient.DOWN
        } else {
            startForegroundService(Intent(this, BridgeService::class.java).apply { action = BridgeService.ACTION_START })
            startingUntil = System.currentTimeMillis() + 20_000L
        }
        renderHome()
    }

    private fun restartBridge() {
        startForegroundService(Intent(this, BridgeService::class.java).apply { action = BridgeService.ACTION_RESTART })
        startingUntil = System.currentTimeMillis() + 20_000L
        health = BridgeClient.DOWN
        renderHome()
    }

    // ── API ──────────────────────────────────────────────────────────────

    private fun buildApi(): View {
        endpointText = Ui.mono2(this)
        keyText = Ui.mono2(this)
        curlText = Ui.mono2(this)
        testResult = Ui.mono2(this).apply { visibility = View.GONE }

        showKeyButton = Ui.button(this, "Show", Ui.Kind.TONAL).apply {
            setOnClickListener { keyShown = !keyShown; renderApi() }
        }
        val copyKey = Ui.button(this, "Copy key", Ui.Kind.TONAL).apply {
            setOnClickListener { copyText("pocketglm-key", ApiKeyStore.get(this@MainActivity), "Key copied") }
        }
        val newKey = Ui.button(this, "New key", Ui.Kind.TONAL).apply { setOnClickListener { confirmRegenerate() } }
        val customKey = Ui.button(this, "Custom", Ui.Kind.TONAL).apply { setOnClickListener { promptCustomKey() } }
        val copyCurl = Ui.button(this, "Copy curl", Ui.Kind.TONAL).apply {
            setOnClickListener { copyText("pocketglm-curl", curlText(ApiKeyStore.get(this@MainActivity)), "curl copied") }
        }
        val test = Ui.button(this, "Test endpoint", Ui.Kind.PRIMARY).apply { setOnClickListener { testEndpoint() } }

        val endpoints = Ui.card(this).apply {
            addView(Ui.text(this@MainActivity, "Endpoints", 18f, R.color.text, true))
            addView(Ui.gap(this@MainActivity, 8))
            addView(endpointText)
        }
        linkPill = Ui.pill(this, "Not set up", R.color.textDim)
        linkText = Ui.mono2(this)
        linkCopy = Ui.button(this, "Copy link", Ui.Kind.TONAL).apply {
            setOnClickListener {
                val u = linkStatus.url
                if (u.isNotEmpty()) copyText("pocketglm-link", u, "Link copied")
            }
        }
        val linkSetup = Ui.button(this, "Set up", Ui.Kind.TONAL).apply { setOnClickListener { promptTunnelSetup() } }
        val linkRemove = Ui.button(this, "Remove", Ui.Kind.TEXT).apply { setOnClickListener { confirmRemoveLink() } }
        val (linkSwitchRow, sw) = Ui.switchRow(
            this, "Share over the internet",
            "Opens a public https link to this bridge",
            Prefs.publicLink(this),
        ) { on -> onLinkSwitch(on) }
        linkSwitch = sw
        val linkCard = Ui.card(this).apply {
            val head = row(Ui.text(this@MainActivity, "Public link", 18f, R.color.text, true) to 1f)
            head.addView(linkPill)
            addView(head)
            addView(Ui.gap(this@MainActivity, 8))
            addView(linkSwitchRow)
            addView(Ui.gap(this@MainActivity, 8))
            addView(linkText)
            addView(Ui.gap(this@MainActivity, 8))
            addView(buttonRow(linkCopy, linkSetup, linkRemove))
            addView(Ui.gap(this@MainActivity, 8))
            addView(Ui.text(this@MainActivity,
                "Needs a free ngrok account (authtoken). Anyone who has the link and your API key can use your accounts, so give each friend the key only on purpose.",
                12f, R.color.textDim))
        }
        val keyCard = Ui.card(this).apply {
            addView(Ui.text(this@MainActivity, "API key", 18f, R.color.text, true))
            addView(Ui.gap(this@MainActivity, 8))
            addView(keyText)
            addView(Ui.gap(this@MainActivity, 8))
            addView(buttonRow(showKeyButton, copyKey))
            addView(Ui.gap(this@MainActivity, 8))
            addView(buttonRow(newKey, customKey))
        }
        val example = Ui.card(this).apply {
            addView(Ui.text(this@MainActivity, "Example request", 18f, R.color.text, true))
            addView(Ui.gap(this@MainActivity, 8))
            addView(curlText)
            addView(Ui.gap(this@MainActivity, 8))
            addView(buttonRow(copyCurl, test))
            addView(Ui.gap(this@MainActivity, 8))
            addView(testResult)
        }
        return page(endpoints, linkCard, keyCard, example)
    }

    /** First non-loopback IPv4 address of the active network (Wi-Fi LAN IP). */
    private fun lanIp(): String? = try {
        val cm = getSystemService(ConnectivityManager::class.java)
        cm.getLinkProperties(cm.activeNetwork)?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }

    private fun curlText(key: String): String {
        val lan = lanIp() ?: "<phone-ip>"
        return "curl http://$lan:3001/v1/chat/completions \\\n" +
            "  -H \"Authorization: Bearer $key\" \\\n" +
            "  -H \"Content-Type: application/json\" \\\n" +
            "  -d '{\"model\":\"glm-5.2\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}'"
    }

    private fun renderApi() {
        val key = ApiKeyStore.get(this)
        val ip = lanIp()
        val lan = ip ?: "<phone-ip>"
        endpointText.text = buildString {
            append("This phone:\n")
            append("  OpenAI    http://127.0.0.1:3001/v1\n")
            append("  Anthropic http://127.0.0.1:3001\n\n")
            append("Same Wi-Fi:\n")
            append("  OpenAI    http://$lan:3001/v1\n")
            append("  Anthropic http://$lan:3001")
            if (ip == null) append("\n  (no Wi-Fi address found, connect to Wi-Fi first)")
        }
        keyText.text = if (keyShown) key else AccountStore.mask(key)
        showKeyButton.text = if (keyShown) "Hide" else "Show"
        curlText.text = curlText(if (keyShown) key else AccountStore.mask(key))
        renderLink()
    }

    // ── Public link (ngrok) ──────────────────────────────────────────────

    private fun renderLink() {
        val configured = TunnelStore.isConfigured(this)
        val s = linkStatus
        syncingLink = true
        linkSwitch.isChecked = Prefs.publicLink(this) && configured
        syncingLink = false
        linkCopy.visibility = if (s.online && s.url.isNotEmpty()) View.VISIBLE else View.GONE
        val domain = TunnelStore.domain(this)
        when {
            !configured -> {
                linkPill.setPill("Not set up", R.color.textDim)
                linkText.text = "Tap Set up and paste your ngrok authtoken."
            }
            !health.up -> {
                linkPill.setPill("Bridge stopped", R.color.textDim)
                linkText.text = "Start the bridge on the Home tab to open the link."
            }
            !s.reachable -> {
                linkPill.setPill("Checking", R.color.warn)
                linkText.text = "Waiting for the bridge..."
            }
            s.online -> {
                linkPill.setPill("Online", R.color.good)
                linkText.text = s.url + "\n/v1 for OpenAI clients"
            }
            s.starting -> {
                linkPill.setPill("Connecting", R.color.warn)
                linkText.text = "Connecting to ngrok..."
            }
            s.error.isNotEmpty() && s.error != "stopped" -> {
                linkPill.setPill("Failed", R.color.bad)
                linkText.text = Redact.text(this, s.error)
            }
            !s.enabled -> {
                linkPill.setPill("Restart needed", R.color.warn)
                linkText.text = "Restart the bridge so it picks up the authtoken."
            }
            else -> {
                linkPill.setPill("Off", R.color.textDim)
                linkText.text = if (domain.isEmpty()) "Link is off. A random address is used each time."
                else "Link is off. Address: https://$domain"
            }
        }
    }

    private fun onLinkSwitch(on: Boolean) {
        if (syncingLink) return
        if (!TunnelStore.isConfigured(this)) {
            syncingLink = true; linkSwitch.isChecked = false; syncingLink = false
            promptTunnelSetup()
            return
        }
        Prefs.setPublicLink(this, on)
        val key = ApiKeyStore.get(this)
        scope.launch {
            val err = withContext(Dispatchers.IO) { if (on) TunnelClient.start(key) else TunnelClient.stop(key) }
            if (err != null) Toast.makeText(this@MainActivity, err, Toast.LENGTH_LONG).show()
            linkStatus = withContext(Dispatchers.IO) { TunnelClient.status(key) }
            renderLink()
        }
    }

    private fun promptTunnelSetup() {
        val pad = Ui.dp(this, 20)
        val token = Ui.input(this, if (TunnelStore.isConfigured(this)) "Authtoken (leave empty to keep the saved one)" else "ngrok authtoken").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        val domain = Ui.input(this, "Static domain, e.g. name.ngrok-free.app (optional)").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            setText(TunnelStore.domain(this@MainActivity))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(Ui.text(this@MainActivity,
                "Get both from dashboard.ngrok.com: Your Authtoken, and Domains (one free static domain). Without a domain the address changes every time.",
                13f, R.color.textDim))
            addView(Ui.gap(this@MainActivity, 12))
            addView(token)
            addView(Ui.gap(this@MainActivity, 8))
            addView(domain)
        }
        AlertDialog.Builder(this)
            .setTitle("Public link")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val t = token.text.toString().trim()
                if (t.isEmpty() && !TunnelStore.isConfigured(this)) {
                    Toast.makeText(this, "Paste your ngrok authtoken first", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                if (t.isNotEmpty() && !TunnelStore.setAuthtoken(this, t)) {
                    Toast.makeText(this, "That does not look like an ngrok authtoken", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                if (!TunnelStore.setDomain(this, domain.text.toString())) {
                    Toast.makeText(this, "Invalid domain. Use something like name.ngrok-free.app", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                Prefs.setPublicLink(this, true)
                applyLinkChange("Saved")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmRemoveLink() {
        if (!TunnelStore.isConfigured(this)) return
        AlertDialog.Builder(this)
            .setTitle("Remove the public link setup?")
            .setMessage("Your ngrok authtoken and domain are deleted from this phone.")
            .setPositiveButton("Remove") { _, _ ->
                TunnelStore.clear(this)
                Prefs.setPublicLink(this, false)
                applyLinkChange("Removed")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** The bridge only reads the ngrok settings at start, so a running one restarts. */
    private fun applyLinkChange(done: String) {
        linkStatus = TunnelClient.UNKNOWN
        renderLink()
        if (isUp() || isStarting()) {
            restartBridge()
            Toast.makeText(this, "$done, restarting the bridge", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, done, Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyText(label: String, text: String, toast: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show()
    }

    /** A running bridge only reads AUTH_TOKEN at start, so it must restart. */
    private fun applyKeyChange() {
        renderApi()
        if (isUp() || isStarting()) {
            restartBridge()
            Toast.makeText(this, "Key changed, restarting the bridge", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Key changed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmRegenerate() {
        AlertDialog.Builder(this)
            .setTitle("Generate a new key?")
            .setMessage("The old key stops working right away" + if (isUp()) " and the bridge restarts." else ".")
            .setPositiveButton("Generate") { _, _ -> ApiKeyStore.regenerate(this); applyKeyChange() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptCustomKey() {
        val input = EditText(this).apply {
            hint = "8 to 128 characters, no spaces or commas"
            setText(ApiKeyStore.get(this@MainActivity))
        }
        AlertDialog.Builder(this)
            .setTitle("Set a custom key")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                if (ApiKeyStore.set(this, input.text.toString())) applyKeyChange()
                else Toast.makeText(this, "Invalid key: 8 to 128 characters, no spaces or commas", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun testEndpoint() {
        testResult.visibility = View.VISIBLE
        testResult.text = "Testing..."
        val key = ApiKeyStore.get(this)
        scope.launch {
            val result = withContext(Dispatchers.IO) { BridgeClient.testModels(key) }
            testResult.text = Redact.text(this@MainActivity, result)
        }
    }

    // ── Accounts ─────────────────────────────────────────────────────────

    private fun buildAccounts(): View {
        accountList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val signIn = Ui.button(this, "Sign in with Z.AI", Ui.Kind.PRIMARY).apply {
            setOnClickListener { startActivityForResult(Intent(this@MainActivity, AccountLoginActivity::class.java), REQ_LOGIN) }
        }
        val paste = Ui.button(this, "Paste token", Ui.Kind.TONAL).apply { setOnClickListener { promptPaste() } }
        applyButton = Ui.button(this, "Apply now (restart bridge)", Ui.Kind.TONAL).apply { setOnClickListener { restartBridge() } }

        val info = Ui.card(this).apply {
            addView(Ui.text(this@MainActivity, "Accounts", 18f, R.color.text, true))
            addView(Ui.gap(this@MainActivity, 4))
            addView(Ui.text(
                this@MainActivity,
                "Without an account the bridge uses a guest identity that only reaches the flash models. " +
                    "Signed-in accounts are rotated per request. Changes apply when the bridge starts.",
                13f, R.color.textDim
            ))
        }
        val list = Ui.card(this).apply { addView(accountList) }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(buttonRow(signIn, paste))
            addView(Ui.gap(this@MainActivity, 8))
            addView(applyButton)
        }
        return page(info, list, actions)
    }

    private fun renderAccounts() {
        accountList.removeAllViews()
        val accounts = AccountStore.list(this)
        if (accounts.isEmpty()) {
            accountList.addView(Ui.text(this, "No accounts saved. The bridge runs as a guest.", 14f, R.color.textDim))
            return
        }
        val fmt = SimpleDateFormat("d MMM yyyy HH:mm", Locale.getDefault())
        accounts.forEachIndexed { i, a ->
            if (i > 0) accountList.addView(Ui.gap(this, 16))
            val exp = a.expiresAtSec?.let {
                if (a.isExpired()) "expired" else "expires " + fmt.format(Date(it * 1000))
            } ?: "no expiry"
            val pill = if (a.isExpired()) Ui.pill(this, "Expired", R.color.bad) else Ui.pill(this, "Active", R.color.good)
            val shown = a.id in emailShown && a.email.isNotEmpty()
            val title = Ui.text(this, if (shown) a.email else a.hint, 15f, R.color.text, true).apply {
                setTextIsSelectable(shown)
            }
            val header = row(title to 1f)
            if (a.email.isNotEmpty()) {
                // Tap to reveal or hide the full address; it is masked by default.
                header.addView(Ui.iconButton(this, R.drawable.ic_eye, if (shown) "Hide email" else "Show email", if (shown) R.color.accent else R.color.textDim).apply {
                    setOnClickListener {
                        if (a.id in emailShown) emailShown.remove(a.id) else emailShown.add(a.id)
                        renderAccounts()
                    }
                }, LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)))
            }
            header.addView(pill, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { marginStart = Ui.dp(this@MainActivity, 8) })
            accountList.addView(header)
            accountList.addView(Ui.mono(this).apply { text = "${a.masked()}\n$exp"; setTextIsSelectable(false) })
            accountList.addView(Ui.gap(this, 12))
            accountList.addView(buttonRow(
                Ui.button(this, "Copy token", Ui.Kind.TONAL).apply { setOnClickListener { AccountDialogs.confirmCopyToken(this@MainActivity, a) } },
                Ui.button(this, "Remove", Ui.Kind.TEXT).apply { setOnClickListener { confirmRemove(a) } },
            ))
        }
    }

    private fun confirmRemove(a: AccountStore.Account) {
        AlertDialog.Builder(this)
            .setTitle("Remove this account?")
            .setMessage("${a.hint} will be deleted from this phone.")
            .setPositiveButton("Remove") { _, _ -> AccountStore.remove(this, a.id); renderAccounts() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptPaste() = AccountDialogs.promptPaste(this) { renderAccounts() }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_LOGIN) renderAccounts()
    }

    // ── Logs ─────────────────────────────────────────────────────────────

    private fun buildLogs(): View {
        logText = Ui.mono(this).apply {
            val p = Ui.dp(this@MainActivity, 12)
            setPadding(p, p, p, p)
            background = Ui.shape(Ui.color(this@MainActivity, R.color.surfaceAlt), Ui.dp(this@MainActivity, 10).toFloat())
            textSize = 11f
        }
        val copy = Ui.button(this, "Copy all logs", Ui.Kind.PRIMARY).apply {
            setOnClickListener {
                val full = LogFile.file(this@MainActivity).let { if (it.exists()) it.readText() else "(no bridge.log yet)" }
                val header = "healthy=${health.healthy} tokens=${health.tokens} ok=${health.computeOk} fail=${health.computeFail} breaker=${health.breaker}\n\n"
                copyText("pocketglm-logs", Redact.text(this@MainActivity, header + full), "Logs copied, secrets hidden")
            }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(this@MainActivity, 16)
            setPadding(p, p, p, p)
            addView(copy, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { bottomMargin = Ui.dp(this@MainActivity, 12) })
            addView(logText)
        }
        logsScroll = ScrollView(this).apply { addView(col) }
        return logsScroll
    }

    private fun refreshLogs() {
        val tail = LogFile.tail(this, 12_000)
        val shown = Redact.text(this, tail).ifEmpty { "(bridge.log is empty or does not exist yet)" }
        if (logText.text.toString() != shown) {
            val atEnd = !logsScroll.canScrollVertically(1)
            logText.text = shown
            if (atEnd) logsScroll.post { logsScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    // ── polling + auto top-up ────────────────────────────────────────────

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                val h = withContext(Dispatchers.IO) { BridgeClient.health() }
                health = h
                linkStatus = if (h.up && TunnelStore.isConfigured(this@MainActivity)) {
                    val key = ApiKeyStore.get(this@MainActivity)
                    withContext(Dispatchers.IO) { TunnelClient.status(key) }
                } else TunnelClient.UNKNOWN
                renderLink()
                renderHome()
                if (tab == 4) refreshLogs()
                maybeTopUp()
                delay(2000)
            }
        }
    }

    /**
     * While the app is open and the bridge is up, open the farm on its own
     * when the pool is nearly empty. The cooldown (2 min after a success, 10
     * after a failure) keeps a failing farm from reopening in a loop.
     */
    private fun maybeTopUp() {
        if (!resumed || !Prefs.autoTopUp(this)) return
        val h = health
        if (!h.up || h.warming || h.tokens < 0 || h.tokens > LOW_POOL) return
        if (FarmState.running || !FarmState.cooldownOk()) return
        val now = System.currentTimeMillis()
        if (now - lastTopUpAt < 30_000L) return
        lastTopUpAt = now
        startActivity(Intent(this, TokenFarmActivity::class.java).apply {
            putExtra(TokenFarmActivity.EXTRA_AUTO, true)
        })
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (!Prefs.setupDone(this)) {
            if (AccountStore.list(this).isNotEmpty()) {
                Prefs.setSetupDone(this, true)
            } else if (!setupShown) {
                setupShown = true
                startActivity(Intent(this, SetupActivity::class.java))
            }
        }
        if (tab == 3) renderAccounts()
        renderApi()
        renderHome()
        startPolling()
    }

    override fun onPause() {
        resumed = false
        pollJob?.cancel()
        super.onPause()
    }

    override fun onDestroy() {
        chatPage.shutdown()
        scope.cancel()
        // The bridge keeps running when the app closes; stopping it is an explicit action.
        super.onDestroy()
    }

    companion object {
        private const val LOW_POOL = 3
        private const val REQ_LOGIN = 1
    }
}
