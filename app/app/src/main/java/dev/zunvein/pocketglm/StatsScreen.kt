package dev.zunvein.pocketglm

import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File

/**
 * The numbers the bridge reports on /health plus the tail of bridge.log, which
 * is also where every farm line lands.
 */
class StatsScreen(act: MainActivity) : Screen(act) {

    private lateinit var tokens: LinearLayout
    private lateinit var answered: LinearLayout
    private lateinit var failed: LinearLayout
    private lateinit var age: LinearLayout
    private lateinit var offline: TextView
    private lateinit var breakerLine: TextView
    private lateinit var logBody: TextView
    private var health = BridgeClient.DOWN
    private var busy = false

    override fun build(): View {
        val (sv, col) = page("Stats", "reported by the bridge, reset when it restarts")

        offline = Ui.muted(ctx, "").apply { visibility = View.GONE }
        col.put(offline, 12)

        tokens = Ui.stat(ctx, "Tokens in pool", "–", "one per chat")
        answered = Ui.stat(ctx, "Chats answered", "–", "since start")
        age = Ui.stat(ctx, "Pool age", "–", "since first token")
        failed = Ui.stat(ctx, "Chats failed", "–", "since start")
        col.put(tiles(tokens, answered), 16)
        col.put(tiles(age, failed), 8)

        val breaker = Ui.card(ctx, "Breaker", "captcha supply")
        breaker.addView(Ui.muted(ctx,
            "The breaker opens when the pool is empty or the captcha keeps failing, and every chat answers 503 until it closes again."))
        breakerLine = Ui.mono(ctx, "–")
        breaker.put(breakerLine, 10)
        col.put(breaker)

        val log = Ui.card(ctx, "Bridge log", "newest last")
        logBody = TextView(ctx).apply {
            textSize = 11.5f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Ui.resColor(ctx, R.color.fg))
            setTextIsSelectable(true)
            background = Ui.thinkBg(ctx)
            val p = Ui.dip(ctx, 10)
            setPadding(p, p, p, p)
            text = "Loading…"
        }
        log.addView(logBody, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        log.put(Ui.buttonRow(ctx,
            Ui.small(ctx, "Reload") { loadLog() },
            Ui.small(ctx, "Copy") { copyLog() },
            Ui.small(ctx, "Share") { shareLog() }), 12)
        log.put(Ui.fine(ctx, "Secrets are hidden before the log leaves the app."), 8)
        col.put(log)
        return sv
    }

    override fun onShow() {
        refresh()
        loadLog()
    }

    override fun onTick() = refresh()

    private fun refresh() {
        if (busy) return
        busy = true
        bg {
            try {
                val h = BridgeClient.health()
                post { render(h) }
            } finally {
                busy = false
            }
        }
    }

    private fun render(h: BridgeClient.Health) {
        health = h
        offline.visibility = if (h.up) View.GONE else View.VISIBLE
        offline.text = "The bridge is not running, so there is nothing to count yet. Start it on Home."
        Ui.setStat(tokens, if (h.up && h.tokens >= 0) h.tokens.toString() else "–", "one per chat")
        Ui.setStat(answered, if (h.up) Fmt.num(h.computeOk) else "–", "since start")
        Ui.setStat(failed, if (h.up) Fmt.num(h.computeFail) else "–", "since start")
        Ui.setStat(age, if (h.up) Fmt.age(h.poolAgeSec) else "–", "since first token")
        breakerLine.text = when {
            !h.up -> "bridge not answering"
            h.breaker.isEmpty() -> "closed"
            else -> h.breaker
        }
        breakerLine.setTextColor(Ui.resColor(ctx, if (h.up && h.breaker.isEmpty()) R.color.muted else R.color.cool))
    }

    private fun loadLog() {
        bg {
            val tail = Redact.text(ctx, LogFile.tail(ctx, 6000))
            post { logBody.text = tail.ifEmpty { "(bridge.log is empty or does not exist yet)" } }
        }
    }

    private fun header(): String =
        "healthy=${health.healthy} tokens=${health.tokens} ok=${health.computeOk} fail=${health.computeFail} breaker=${health.breaker}\n\n"

    private fun copyLog() {
        bg {
            val full = LogFile.file(ctx).let { if (it.exists()) it.readText() else "(no bridge.log yet)" }
            val text = Redact.text(ctx, header() + full)
            post {
                act.copy("PocketGLM log", text)
                toast("Log copied, secrets hidden")
            }
        }
    }

    /** The whole log, secrets hidden, through the system share sheet as a file. */
    private fun shareLog() {
        bg {
            val uri = try {
                val src = LogFile.file(ctx)
                val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
                val out = File(dir, "pocketglm-bridge.log.txt")
                val body = if (src.exists()) src.readText() else "(no bridge.log yet)"
                out.writeText(Redact.text(ctx, header() + body))
                FileProvider.getUriForFile(ctx, MainActivity.LOG_AUTHORITY, out)
            } catch (e: Exception) {
                post { toast("Could not prepare the log: ${e.javaClass.simpleName}") }
                return@bg
            }
            post {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                ctx.startActivity(Intent.createChooser(send, "Share the bridge log"))
            }
        }
    }
}
