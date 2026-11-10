package dev.zunvein.pocketglm

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity

/**
 * First-run choice: use a Z.AI account (full model list) or stay a guest
 * (flash models only). Shown once; the Accounts tab can change it later.
 */
class SetupActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = Ui.dp(this, 16)

        fun option(title: String, body: String, button: Button): View = Ui.card(this).apply {
            addView(Ui.text(this@SetupActivity, title, 18f, R.color.text, true))
            addView(Ui.gap(this@SetupActivity, 4))
            addView(Ui.text(this@SetupActivity, body, 14f, R.color.textDim))
            addView(Ui.gap(this@SetupActivity, 12))
            addView(button)
        }

        val signIn = Ui.button(this, "Sign in with Z.AI", Ui.Kind.PRIMARY).apply {
            setOnClickListener {
                startActivityForResult(Intent(this@SetupActivity, AccountLoginActivity::class.java), REQ_LOGIN)
            }
        }
        val paste = Ui.button(this, "Paste a saved token", Ui.Kind.TONAL).apply {
            setOnClickListener { AccountDialogs.promptPaste(this@SetupActivity) { finishIfAccount() } }
        }
        val guest = Ui.button(this, "Continue as guest", Ui.Kind.TONAL).apply {
            setOnClickListener { done() }
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(Ui.text(this@SetupActivity, "Welcome to PocketGLM", 24f, R.color.text, true))
            addView(Ui.gap(this@SetupActivity, 4))
            addView(Ui.text(
                this@SetupActivity,
                "PocketGLM runs a local bridge to Z.AI on this phone. Choose who the bridge talks to Z.AI as.",
                14f, R.color.textDim,
            ))
            addView(Ui.gap(this@SetupActivity, 16))
            addView(option(
                "Use my account",
                "Unlocks the full model list. Sign in with Google, email or GitHub.",
                signIn,
            ))
            addView(Ui.gap(this@SetupActivity, 12))
            addView(option(
                "Reinstalling?",
                "Paste the token you copied from the Accounts tab before.",
                paste,
            ))
            addView(Ui.gap(this@SetupActivity, 12))
            addView(option(
                "Use a guest",
                "No account needed, but guests only reach the flash models. You can add an account later in the Accounts tab.",
                guest,
            ))
        }
        setContentView(ScrollView(this).apply { addView(col) })
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
