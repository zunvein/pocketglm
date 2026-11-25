package dev.zunvein.pocketglm

import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Z.AI accounts. A guest identity reaches the flash models only, so a signed-in
 * account is what makes the full model list work. Tokens never leave the phone
 * except to the local bridge.
 */
class AccountsScreen(act: MainActivity) : Screen(act) {

    private lateinit var list: LinearLayout
    private lateinit var applyBtn: android.widget.Button
    private val shown = HashSet<String>()
    private var bridgeUp = false

    override fun build(): View {
        val (sv, col) = page("Accounts", "one Z.AI identity per sign-in, rotated per request")

        val head = Ui.card(ctx, "Signed in")
        head.addView(Ui.muted(ctx,
            "Without an account the bridge runs as a guest and only reaches the flash models. Changes apply the next time the bridge starts."))
        list = Ui.vertical(ctx)
        head.put(list, 12)
        col.put(head, 16)

        val paste = Ui.button(ctx, "Paste token") { AccountDialogs.promptPaste(ctx) { render() } }
        col.put(Ui.primary(ctx, "Sign in with Z.AI") {
            act.startActivity(Intent(ctx, AccountLoginActivity::class.java))
        }, 12)
        col.put(paste)
        applyBtn = Ui.button(ctx, "Restart the bridge now") {
            act.startForegroundService(Intent(ctx, BridgeService::class.java).apply { action = BridgeService.ACTION_RESTART })
            toast("Restarting the bridge")
        }
        applyBtn.visibility = View.GONE
        col.put(applyBtn)
        col.put(Ui.fine(ctx,
            "Google usually refuses to sign in from inside another app. Email and GitHub work in the sign-in window."), 14)
        return sv
    }

    override fun onShow() {
        render()
        pollBridge()
    }

    override fun onResume() {
        render()
        pollBridge()
    }

    override fun onTick() = pollBridge()

    private fun pollBridge() {
        bg {
            val up = BridgeClient.health().up
            post {
                bridgeUp = up
                applyBtn.visibility = if (up) View.VISIBLE else View.GONE
            }
        }
    }

    private fun render() {
        list.removeAllViews()
        val accounts = AccountStore.list(ctx)
        if (accounts.isEmpty()) {
            list.addView(Ui.muted(ctx, "No accounts saved. The bridge runs as a guest."))
            return
        }
        val fmt = SimpleDateFormat("d MMM yyyy HH:mm", Locale.getDefault())
        accounts.forEachIndexed { i, a ->
            if (i > 0) list.addView(Ui.divider(ctx))
            list.addView(row(a, fmt))
        }
    }

    private fun row(a: AccountStore.Account, fmt: SimpleDateFormat): View {
        val box = Ui.vertical(ctx).apply { setPadding(0, Ui.dip(ctx, 12), 0, Ui.dip(ctx, 12)) }
        val revealed = a.id in shown && a.email.isNotEmpty()

        val head = Ui.horizontal(ctx)
        head.addView(TextView(ctx).apply {
            text = if (revealed) a.email else a.hint
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Ui.resColor(ctx, R.color.fg))
            setTextIsSelectable(revealed)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        if (a.email.isNotEmpty()) {
            val eyeBtn = android.widget.FrameLayout(ctx).apply {
                minimumWidth = Ui.dip(ctx, 44)
                minimumHeight = Ui.dip(ctx, 44)
                isClickable = true
                isFocusable = true
                contentDescription = if (revealed) "Hide the address" else "Show the address"
                addView(Ui.icon(ctx, R.drawable.ic_eye, if (revealed) R.color.accent else R.color.muted, 20),
                    android.widget.FrameLayout.LayoutParams(Ui.dip(ctx, 20), Ui.dip(ctx, 20)).apply { gravity = android.view.Gravity.CENTER })
                setOnClickListener {
                    if (revealed) shown.remove(a.id) else shown.add(a.id)
                    render()
                }
            }
            head.addView(eyeBtn)
        }
        val pill = Ui.pill(ctx, if (a.isExpired()) "dead" else "ready").apply {
            text = if (a.isExpired()) "expired" else "active"
        }
        head.addView(pill, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { leftMargin = Ui.dip(ctx, 8) })
        box.addView(head)

        val exp = a.expiresAtSec?.let {
            if (a.isExpired()) "expired" else "expires " + fmt.format(Date(it * 1000))
        } ?: "no expiry"
        box.addView(Ui.mono(ctx, "${a.masked()}\n$exp").apply { setPadding(0, Ui.dip(ctx, 4), 0, 0) })
        box.put(Ui.buttonRow(ctx,
            Ui.small(ctx, "Copy token") { AccountDialogs.confirmCopyToken(ctx, a) },
            Ui.smallDanger(ctx, "Remove") { confirmRemove(a) }), 10)
        return box
    }

    private fun confirmRemove(a: AccountStore.Account) {
        Ui.confirm(ctx, "Remove this account?", "${a.hint} is deleted from this phone. A running bridge keeps using it until it restarts.", "Remove") {
            AccountStore.remove(ctx, a.id)
            render()
        }
    }
}
