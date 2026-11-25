package dev.zunvein.pocketglm

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/**
 * The one screen that answers "is it working": bridge state, pool size, the
 * two counters, the addresses, and the farm controls that refill the pool.
 */
class HomeScreen(act: MainActivity) : Screen(act) {

    private lateinit var pill: TextView
    private lateinit var headline: TextView
    private lateinit var caption: TextView
    private lateinit var flip: Button
    private lateinit var answered: LinearLayout
    private lateinit var failed: LinearLayout
    private lateinit var endpoints: LinearLayout
    private lateinit var healthLine: TextView
    private lateinit var bgStatus: TextView
    private lateinit var batteryLine: TextView
    private lateinit var batteryBtn: Button
    private var busy = false
    private var startingUntil = 0L
    private var lastUp = false

    override fun build(): View {
        val (sv, col) = page("PocketGLM", "Z.AI models as an OpenAI-style API, served from this phone")

        val bridge = Ui.card(ctx)
        val top = Ui.horizontal(ctx)
        top.addView(Ui.title(ctx, "Bridge"), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        pill = Ui.pill(ctx, "unknown")
        top.addView(pill)
        bridge.addView(top)
        headline = TextView(ctx).apply {
            text = "…"
            textSize = 28f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Ui.resColor(ctx, R.color.fg))
            setPadding(0, Ui.dip(ctx, 10), 0, 0)
        }
        bridge.addView(headline)
        caption = Ui.muted(ctx, "Checking the bridge")
        bridge.addView(caption)
        flip = Ui.primary(ctx, "Start bridge") { flipBridge() }
        bridge.put(flip, 16)
        col.put(bridge, 16)

        answered = Ui.stat(ctx, "Chats answered", "–", "since start")
        failed = Ui.stat(ctx, "Chats failed", "–", "since start")
        col.put(tiles(answered, failed))

        val where = Ui.card(ctx, "Endpoint", "tap to copy")
        endpoints = Ui.vertical(ctx)
        where.addView(endpoints)
        healthLine = Ui.fine(ctx, "")
        where.put(healthLine, 10)
        col.put(where)

        val supply = Ui.card(ctx, "Token supply", "one token per chat")
        supply.addView(Ui.muted(ctx,
            "Every chat spends one device token from the pool. One farm run adds about ${Farmer.DEFAULT_MINT + 1}."))
        supply.put(Ui.primary(ctx, "Farm tokens now") {
            ctx.startActivity(Intent(ctx, TokenFarmActivity::class.java))
        }, 14)
        supply.put(rule(), 14)
        supply.addView(Ui.switchRow(ctx, "Auto top-up", "Opens the farm when the pool runs low, while this app is open", Prefs.autoTopUp(ctx)) {
            Prefs.setAutoTopUp(ctx, it)
        }, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        supply.put(rule())
        supply.addView(Ui.switchRow(ctx, "Farm in the background",
            "Uses a hidden browser while the bridge runs, even with the app closed. Experimental.", Prefs.bgFarm(ctx)) { on ->
            Prefs.setBgFarm(ctx, on)
            FarmState.bgFailStreak = 0
            paint()
        }, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        bgStatus = Ui.fine(ctx, "")
        supply.put(bgStatus, 8)
        supply.put(Ui.smallDanger(ctx, "Reset pool") { confirmReset() }, 10)
        col.put(supply)

        val keep = Ui.card(ctx, "Keep running")
        keep.addView(Ui.switchRow(ctx, "Keep CPU and Wi-Fi awake",
            "Holds a wake lock while the bridge runs, so other devices reach it with the screen off", Prefs.keepAwake(ctx)) { on ->
            Prefs.setKeepAwake(ctx, on)
            if (isUp()) toast("Applies the next time the bridge starts")
        }, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        batteryLine = Ui.fine(ctx, "")
        keep.put(batteryLine, 12)
        batteryBtn = Ui.button(ctx, "Allow unrestricted battery") { askBattery() }
        keep.put(batteryBtn, 12)
        keep.put(Ui.fine(ctx,
            "Xiaomi, Oppo, Vivo, Huawei and Samsung ship their own autostart list. If the bridge still dies with the screen off, allow PocketGLM there too."), 14)
        col.put(keep)
        return sv
    }

    override fun onShow() {
        paint()
        refresh()
    }

    override fun onResume() {
        paint()
        refresh()
    }

    override fun onTick() = refresh()

    private fun isUp() = lastUp

    private fun flipBridge() {
        if (lastUp || System.currentTimeMillis() < startingUntil) {
            act.startService(Intent(ctx, BridgeService::class.java).apply { action = BridgeService.ACTION_STOP })
            startingUntil = 0L
            lastUp = false
        } else {
            act.startForegroundService(Intent(ctx, BridgeService::class.java).apply { action = BridgeService.ACTION_START })
            startingUntil = System.currentTimeMillis() + 20_000L
            act.askNotifications()
        }
        paint()
    }

    private fun restartBridge() {
        act.startForegroundService(Intent(ctx, BridgeService::class.java).apply { action = BridgeService.ACTION_RESTART })
        startingUntil = System.currentTimeMillis() + 20_000L
        lastUp = false
        paint()
    }

    private fun refresh() {
        if (busy) return
        busy = true
        bg {
            try {
                val h = BridgeClient.health()
                val tun = if (h.up && TunnelStore.isConfigured(ctx)) TunnelClient.status(key()) else TunnelClient.UNKNOWN
                val lan = Net.lanIp(ctx)
                post { apply(h, tun, lan) }
            } finally {
                busy = false
            }
        }
    }

    private fun apply(h: BridgeClient.Health, tun: TunnelClient.Status, lan: String?) {
        lastUp = h.up
        val starting = !h.up && System.currentTimeMillis() < startingUntil
        when {
            h.up && !h.warming -> { Ui.applyState(pill, "ready"); pill.text = "running" }
            h.up -> { Ui.applyState(pill, "cooling"); pill.text = "warming up" }
            starting -> { Ui.applyState(pill, "cooling"); pill.text = "starting" }
            else -> Ui.applyState(pill, "off")
        }
        flip.text = if (h.up || starting) "Stop bridge" else "Start bridge"

        headline.text = if (h.up && h.tokens >= 0) h.tokens.toString() else "–"
        headline.setTextColor(Ui.resColor(ctx, if (h.up && h.tokens in 0..3) R.color.cool else R.color.fg))
        caption.text = when {
            !h.up && starting -> "Waiting for the bridge to answer on port 3001."
            !h.up -> "The bridge is not running, so nothing answers on port 3001."
            h.warming -> "The bridge is up and still setting up its Z.AI session."
            h.tokens == 0 -> "The pool is empty. Chats fail until you farm."
            h.tokens in 1..3 -> "Running low. Farm soon or the next chats fail."
            else -> "device tokens ready"
        }

        Ui.setStat(answered, if (h.up) Fmt.num(h.computeOk) else "–", "since start")
        Ui.setStat(failed, if (h.up) Fmt.num(h.computeFail) else "–", "since start")

        healthLine.text = when {
            !h.up -> "Health: no answer." + (h.error?.let { " ($it)" } ?: "")
            h.warming -> "Health: warming up."
            else -> buildString {
                append("Health: ok")
                if (h.breaker.isNotEmpty()) append(" · breaker ${h.breaker}")
                if (h.poolAgeSec >= 0) append(" · pool ${Fmt.age(h.poolAgeSec)} old")
            }
        }
        healthLine.setTextColor(Ui.resColor(ctx, if (h.up && !h.warming) R.color.muted else R.color.cool))

        val publicUrl = if (tun.online) tun.url.trimEnd('/') else null
        val sig = "$lan|$publicUrl"
        if (endpoints.tag != sig) {
            endpoints.tag = sig
            endpoints.removeAllViews()
            endpoints.addView(endpointRow("This phone", "http://127.0.0.1:3001/v1"))
            if (lan != null) {
                endpoints.addView(rule())
                endpoints.addView(endpointRow("Same Wi-Fi", "http://$lan:3001/v1"))
            } else {
                endpoints.addView(rule())
                endpoints.addView(Ui.fine(ctx, "No Wi-Fi address. Connect to Wi-Fi to reach the bridge from another device.")
                    .apply { setPadding(0, Ui.dip(ctx, 8), 0, 0) })
            }
            if (publicUrl != null) {
                endpoints.addView(rule())
                endpoints.addView(endpointRow("Public link", "$publicUrl/v1"))
            }
        }
        paint()
    }

    private fun paint() {
        val status = Prefs.bgStatus(ctx)
        val text = if (Prefs.bgFarm(ctx)) "Background farm: " + (status.ifEmpty { "waiting for the pool to run low" }) else ""
        bgStatus.text = text
        bgStatus.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE

        val exempt = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
        batteryLine.text = if (exempt) "Battery: unrestricted for PocketGLM." else "Battery: Android may stop the bridge when the screen is off."
        batteryLine.setTextColor(Ui.resColor(ctx, if (exempt) R.color.muted else R.color.cool))
        batteryBtn.visibility = if (exempt) View.GONE else View.VISIBLE
    }

    /** A tappable line: label above, address in mono, 56dp tall for the thumb. */
    private fun endpointRow(label: String, url: String): View {
        val row = Ui.vertical(ctx).apply {
            minimumHeight = Ui.dip(ctx, 56)
            setPadding(0, Ui.dip(ctx, 8), 0, Ui.dip(ctx, 8))
            isClickable = true
            isFocusable = true
            contentDescription = "$label $url, tap to copy"
            setOnClickListener {
                act.copy("PocketGLM endpoint", url)
                toast("Copied $url")
            }
        }
        row.addView(Ui.fine(ctx, label))
        row.addView(Ui.mono(ctx, url).apply { setTextColor(Ui.resColor(ctx, R.color.fg)); setPadding(0, Ui.dip(ctx, 2), 0, 0) })
        return row
    }

    private fun askBattery() {
        try {
            ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName)))
        } catch (e: Exception) {
            try {
                ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (ignored: Exception) {
                toast("Open Android Settings, Apps, PocketGLM, Battery, and choose Unrestricted.")
            }
        }
    }

    private fun confirmReset() {
        Ui.confirm(ctx, "Reset the token pool?",
            "Deletes every device token on this phone and restarts the bridge with an empty pool. Accounts and the API key stay.",
            "Reset") { resetPool() }
    }

    private fun resetPool() {
        act.startService(Intent(ctx, BridgeService::class.java).apply { action = BridgeService.ACTION_STOP })
        startingUntil = 0L
        lastUp = false
        paint()
        bg {
            var tries = 0
            while (tries++ < 20 && BridgeClient.health().up) Thread.sleep(500)
            val dir = File(ctx.filesDir, "bridge")
            for (n in listOf("tokens.sqlite", "tokens.sqlite-wal", "tokens.sqlite-shm")) File(dir, n).delete()
            BridgeDb.ensure(dir)
            post {
                toast("Pool cleared, starting the bridge")
                restartBridge()
            }
        }
    }
}
