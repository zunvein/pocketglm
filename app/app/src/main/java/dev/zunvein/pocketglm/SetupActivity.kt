package dev.zunvein.pocketglm

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * First-run choice: use a Z.AI account (full model list) or stay a guest
 * (flash models only). Shown once; Accounts can change it later.
 */
class SetupActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)

        val col = Ui.vertical(this, 16)
        col.addView(TextView(this).apply {
            text = "Welcome to PocketGLM"
            textSize = 24f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Ui.resColor(this@SetupActivity, R.color.fg))
        })
        col.addView(Ui.muted(this, "PocketGLM runs a local bridge to Z.AI on this phone. Choose who the bridge talks to Z.AI as.").apply {
            setPadding(0, Ui.dip(this@SetupActivity, 4), 0, 0)
        })

        fun option(title: String, body: String, action: View) {
            val card = Ui.card(this, title)
            card.addView(Ui.muted(this, body))
            card.addView(action, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply {
                topMargin = Ui.dip(this@SetupActivity, 12)
            })
            col.addView(card, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply {
                topMargin = Ui.dip(this@SetupActivity, 12)
            })
        }

        option("Use my account", "Unlocks the full model list. Sign in with email or GitHub.",
            Ui.primary(this, "Sign in with Z.AI") {
                startActivityForResult(Intent(this, AccountLoginActivity::class.java), REQ_LOGIN)
            })
        option("Reinstalling?", "Paste the token you copied from Accounts before.",
            Ui.button(this, "Paste a saved token") { AccountDialogs.promptPaste(this) { finishIfAccount() } })
        option("Use a guest", "No account needed, but guests only reach the flash models. You can add an account later in Accounts.",
            Ui.button(this, "Continue as guest") { done() })

        setContentView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(col, android.view.ViewGroup.LayoutParams(Ui.MATCH, Ui.WRAP))
        })
    }

    private fun finishIfAccount() {
        if (AccountStore.list(this).isNotEmpty()) done()
    }

    private fun done() {
        Prefs.setSetupDone(this, true)
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_LOGIN) finishIfAccount()
    }

    companion object {
        private const val REQ_LOGIN = 1
    }
}
