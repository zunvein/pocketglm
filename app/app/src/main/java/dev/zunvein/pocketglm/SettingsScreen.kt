package dev.zunvein.pocketglm

import android.content.Intent
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Everything that is set once and then left alone: the key clients use, the
 * ngrok link, the keys friends hold, the blocked-network switch, and the two
 * logs you read when something goes wrong.
 */
class SettingsScreen(act: MainActivity) : Screen(act) {

    private lateinit var keyView: TextView
    private var keyShown = false

    private lateinit var linkPill: TextView
    private lateinit var linkText: TextView
    private lateinit var linkCopy: Button
    private lateinit var linkSwitch: Ui.SwitchRow

    private lateinit var friendBox: LinearLayout
    private lateinit var crashBody: TextView

    private var tunnel = TunnelClient.UNKNOWN
    private var bridgeUp = false
    private var busy = false

    override fun build(): View {
        val (sv, col) = page("Settings")

        col.put(keyCard(), 16)
        col.put(linkCard())
        col.put(friendCard())
        col.put(networkCard())
        col.put(diagCard())
        col.put(Ui.fine(ctx, "PocketGLM ${version()} · bridge on port 3001 · package ${ctx.packageName}"), 16)
        return sv
    }

    override fun onShow() {
        renderKey()
        renderFriends()
        loadCrash()
        refresh()
    }

    override fun onTick() = refresh()

    private fun version(): String = try {
        "v" + ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
    } catch (e: Exception) {
        "v?"
    }

    private fun restartIfRunning(what: String) {
        if (bridgeUp) {
            act.startForegroundService(Intent(ctx, BridgeService::class.java).apply { action = BridgeService.ACTION_RESTART })
            toast("$what, restarting the bridge")
        } else {
            toast(what)
        }
    }

    private fun refresh() {
        if (busy) return
        busy = true
        bg {
            try {
                val h = BridgeClient.health()
                val s = if (h.up && TunnelStore.isConfigured(ctx)) TunnelClient.status(key()) else TunnelClient.UNKNOWN
                post {
                    bridgeUp = h.up
                    tunnel = s
                    renderLink()
                }
            } finally {
                busy = false
            }
        }
    }

    // ---------- API key ----------

    private lateinit var showBtn: Button

    private fun keyCard(): View {
        val c = Ui.card(ctx, "API key", "for this phone's bridge")
        keyView = Ui.mono(ctx, "").apply { setTextIsSelectable(false) }
        c.addView(keyView)
        c.put(Ui.fine(ctx,
            "Other apps use this as the OpenAI or Anthropic key, pointing at http://127.0.0.1:3001/v1. Friend keys are separate."), 8)
        showBtn = Ui.small(ctx, "Show") { keyShown = !keyShown; renderKey() }
        val copy = Ui.small(ctx, "Copy") {
            act.copy("PocketGLM key", ApiKeyStore.get(ctx), sensitive = true, clearAfterSec = 60)
            toast("Key copied. Clears in 60 s.")
        }
        val regen = Ui.smallDanger(ctx, "New key") { confirmRegenerate() }
        c.put(Ui.buttonRow(ctx, showBtn, copy, regen), 10)
        c.put(Ui.small(ctx, "Set a custom key") { promptCustomKey() }, 8)
        return c
    }

    private fun renderKey() {
        val k = ApiKeyStore.get(ctx)
        keyView.text = if (keyShown) k else AccountStore.mask(k)
        showBtn.text = if (keyShown) "Hide" else "Show"
    }

    private fun confirmRegenerate() {
        Ui.confirm(ctx, "Generate a new key?",
            "The old key stops working at once, and every app using it needs the new one.", "Generate") {
            ApiKeyStore.regenerate(ctx)
            renderKey()
            restartIfRunning("Key changed")
        }
    }

    private fun promptCustomKey() {
        val input = Ui.input(ctx, "8 to 128 characters, no spaces or commas").apply {
            setText(ApiKeyStore.get(ctx))
        }
        Ui.dialogWith(ctx, "Set a custom key", input, "Save") {
            if (ApiKeyStore.set(ctx, input.text.toString())) {
                renderKey()
                restartIfRunning("Key changed")
            } else {
                toast("Invalid key: 8 to 128 characters, no spaces or commas")
            }
        }
    }

    // ---------- public link ----------

    private lateinit var domainInput: android.widget.EditText

    private fun linkCard(): View {
        val c = Ui.card(ctx, "Public link", "ngrok")
        val head = Ui.horizontal(ctx)
        head.addView(Ui.muted(ctx, "A URL for this bridge that works away from Wi-Fi."), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        linkPill = Ui.pill(ctx, "off")
        head.addView(linkPill, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { leftMargin = Ui.dip(ctx, 8) })
        c.addView(head)

        linkSwitch = Ui.switchRow(ctx, "Share over the internet", "Needs a free ngrok account", Prefs.publicLink(ctx)) { on ->
            onLinkSwitch(on)
        }
        c.put(linkSwitch, 12)
        linkText = Ui.mono(ctx, "").apply { setTextIsSelectable(true) }
        c.put(linkText, 10)
        linkCopy = Ui.small(ctx, "Copy link") {
            if (tunnel.url.isNotEmpty()) {
                act.copy("PocketGLM link", tunnel.url)
                toast("Link copied")
            }
        }
        c.put(Ui.buttonRow(ctx,
            linkCopy,
            Ui.small(ctx, "Set up") { promptTunnelSetup() },
            Ui.smallDanger(ctx, "Remove") { confirmRemoveLink() }), 10)
        c.put(Ui.fine(ctx,
            "Anyone with the link and your API key can use your accounts, so hand out friend keys instead of the owner key."), 10)
        return c
    }

    private fun renderLink() {
        val configured = TunnelStore.isConfigured(ctx)
        linkSwitch.set(Prefs.publicLink(ctx) && configured)
        linkCopy.visibility = if (tunnel.online && tunnel.url.isNotEmpty()) View.VISIBLE else View.GONE
        val domain = TunnelStore.domain(ctx)
        when {
            !configured -> {
                Ui.applyState(linkPill, "off"); linkPill.text = "not set up"
                linkText.text = "Paste an ngrok authtoken to open a public URL to this bridge."
            }
            !bridgeUp -> {
                Ui.applyState(linkPill, "off"); linkPill.text = "bridge stopped"
                linkText.text = "Start the bridge on Home to open the link."
            }
            !tunnel.reachable -> {
                Ui.applyState(linkPill, "cooling"); linkPill.text = "checking"
                linkText.text = "Waiting for the bridge."
            }
            tunnel.online -> {
                Ui.applyState(linkPill, "ready"); linkPill.text = "online"
                linkText.text = tunnel.url + "\n/v1 for OpenAI clients"
            }
            tunnel.starting -> {
                Ui.applyState(linkPill, "cooling"); linkPill.text = "connecting"
                linkText.text = "Connecting to ngrok."
            }
            tunnel.error.isNotEmpty() && tunnel.error != "stopped" -> {
                Ui.applyState(linkPill, "dead"); linkPill.text = "failed"
                linkText.text = Redact.text(ctx, tunnel.error)
            }
            else -> {
                Ui.applyState(linkPill, "off"); linkPill.text = "off"
                linkText.text = if (domain.isEmpty()) "Link is off. A random address is used each run."
                else "Link is off. Address: https://$domain"
            }
        }
    }

    private fun onLinkSwitch(on: Boolean) {
        if (!TunnelStore.isConfigured(ctx)) {
            linkSwitch.set(false)
            promptTunnelSetup()
            return
        }
        Prefs.setPublicLink(ctx, on)
        bg {
            val err = if (on) TunnelClient.start(key()) else TunnelClient.stop(key())
            post {
                if (err != null) toast(err)
                refresh()
            }
        }
    }

    private fun promptTunnelSetup() {
        val box = Ui.vertical(ctx)
        val token = Ui.input(ctx, if (TunnelStore.isConfigured(ctx)) "Authtoken (leave empty to keep the saved one)" else "ngrok authtoken", secret = true)
        domainInput = Ui.input(ctx, "Static domain, e.g. name.ngrok-free.app (optional)").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(TunnelStore.domain(ctx))
        }
        box.addView(Ui.fine(ctx,
            "Both come from dashboard.ngrok.com: Your Authtoken, and Domains (one free static domain). Without a domain the address changes every run."))
        box.put(token, 12)
        box.put(domainInput, 8)
        Ui.dialogWith(ctx, "Public link", box, "Save") {
            val t = token.text.toString().trim()
            when {
                t.isEmpty() && !TunnelStore.isConfigured(ctx) -> toast("Paste your ngrok authtoken first")
                t.isNotEmpty() && !TunnelStore.setAuthtoken(ctx, t) -> toast("That does not look like an ngrok authtoken")
                !TunnelStore.setDomain(ctx, domainInput.text.toString()) -> toast("Invalid domain, use something like name.ngrok-free.app")
                else -> {
                    Prefs.setPublicLink(ctx, true)
                    tunnel = TunnelClient.UNKNOWN
                    renderLink()
                    restartIfRunning("Saved")
                }
            }
        }
    }

    private fun confirmRemoveLink() {
        if (!TunnelStore.isConfigured(ctx)) return
        Ui.confirm(ctx, "Remove the public link setup?", "The ngrok authtoken and domain are deleted from this phone.", "Remove") {
            TunnelStore.clear(ctx)
            Prefs.setPublicLink(ctx, false)
            tunnel = TunnelClient.UNKNOWN
            renderLink()
            restartIfRunning("Removed")
        }
    }

    // ---------- friend keys ----------

    private fun friendCard(): View {
        val c = Ui.card(ctx, "Friend keys", "chat only")
        c.addView(Ui.muted(ctx,
            "One key per person. A friend key reaches the chat API only, with its own requests-per-minute limit. Revoking a key cuts that person off."))
        friendBox = Ui.vertical(ctx)
        c.put(friendBox, 10)
        c.put(Ui.primary(ctx, "New friend key") { promptNewFriend() }, 12)
        return c
    }

    private fun renderFriends() {
        friendBox.removeAllViews()
        val friends = FriendKeys.list(ctx)
        if (friends.isEmpty()) {
            friendBox.addView(Ui.fine(ctx, "No friend keys yet."))
            return
        }
        friends.forEachIndexed { i, f ->
            if (i > 0) friendBox.addView(Ui.divider(ctx))
            val box = Ui.vertical(ctx).apply { setPadding(0, Ui.dip(ctx, 10), 0, Ui.dip(ctx, 10)) }
            box.addView(Ui.body(ctx, f.name).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD })
            box.addView(Ui.fine(ctx, "${f.perMin} requests a minute"))
            box.put(Ui.mono(ctx, AccountStore.mask(f.key)), 4)
            box.put(Ui.buttonRow(ctx,
                Ui.small(ctx, "Copy") {
                    act.copy("PocketGLM friend key", f.key, sensitive = true, clearAfterSec = 60)
                    toast("Key copied. Clears in 60 s.")
                },
                Ui.small(ctx, "Limit") { promptFriendLimit(f) },
                Ui.smallDanger(ctx, "Revoke") { confirmRevoke(f) }), 8)
            friendBox.addView(box)
        }
    }

    private fun promptNewFriend() {
        val name = Ui.input(ctx, "Name, e.g. Budi")
        Ui.dialogWith(ctx, "New friend key", name, "Create") {
            val f = FriendKeys.add(ctx, name.text.toString())
            renderFriends()
            restartIfRunning("Key for ${f.name} created")
        }
    }

    private fun promptFriendLimit(f: FriendKeys.Friend) {
        val input = Ui.input(ctx, "${FriendKeys.MIN_PER_MIN} to ${FriendKeys.MAX_PER_MIN} a minute").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(f.perMin.toString())
        }
        val box = Ui.vertical(ctx)
        box.addView(Ui.fine(ctx, "Above this the bridge answers 429 for ${f.name}."))
        box.put(input, 10)
        Ui.dialogWith(ctx, "Limit for ${f.name}", box, "Save") {
            val n = input.text.toString().trim().toIntOrNull()
            if (n == null || !FriendKeys.setLimit(ctx, f.id, n)) {
                toast("Use a number from ${FriendKeys.MIN_PER_MIN} to ${FriendKeys.MAX_PER_MIN}")
            } else {
                renderFriends()
                restartIfRunning("Limit saved")
            }
        }
    }

    private fun confirmRevoke(f: FriendKeys.Friend) {
        Ui.confirm(ctx, "Revoke ${f.name}'s key?", "It stops working at once.", "Revoke") {
            FriendKeys.remove(ctx, f.id)
            renderFriends()
            restartIfRunning("Key revoked")
        }
    }

    // ---------- network ----------

    private fun networkCard(): View {
        val c = Ui.card(ctx, "Blocked network")
        c.addView(Ui.switchRow(ctx, "Bypass blocking",
            "For networks that cut the connection to chat.z.ai. Restarts the bridge.", Prefs.bypass(ctx)) { on ->
            Prefs.setBypass(ctx, on)
            restartIfRunning(if (on) "Bypass on" else "Bypass off")
        }, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        c.put(Ui.fine(ctx,
            "Splits the first packet of the secure connection in two, which only helps when the block reads the site name from it. A VPN always works."), 10)
        return c
    }

    // ---------- diagnostics ----------

    private fun diagCard(): View {
        val c = Ui.card(ctx, "Diagnostics")
        c.addView(Ui.title(ctx, "Crash log").apply { textSize = 15f })
        crashBody = Ui.mono(ctx, "").apply { setTextIsSelectable(true) }
        c.put(crashBody, 6)
        c.put(Ui.buttonRow(ctx,
            Ui.small(ctx, "Reload") { loadCrash() },
            Ui.small(ctx, "Copy") {
                val last = CrashLog.last(ctx)
                if (last == null) toast("No crash recorded") else {
                    act.copy("PocketGLM crash log", last)
                    toast("Copied")
                }
            },
            Ui.smallDanger(ctx, "Clear") { CrashLog.clear(ctx); loadCrash() }), 10)
        return c
    }

    private fun loadCrash() {
        val last = CrashLog.last(ctx)
        crashBody.text = last ?: "No crash recorded on this install."
    }
}
