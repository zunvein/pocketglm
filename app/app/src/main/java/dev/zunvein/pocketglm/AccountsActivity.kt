package dev.zunvein.pocketglm

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.app.AlertDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manage the Z.AI accounts the bridge rotates through. Tokens are shown
 * masked only; changes reach the bridge the next time it starts.
 */
class AccountsActivity : Activity() {

    private lateinit var listBox: LinearLayout
    private lateinit var note: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()

        note = TextView(this).apply {
            text = "Without an account the bridge uses a guest identity, which only reaches the flash models. " +
                "Changes apply the next time the bridge starts."
            setPadding(0, 0, 0, pad)
        }
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val signIn = Button(this).apply {
            text = "Sign in with Z.AI"
            setOnClickListener { startActivityForResult(Intent(this@AccountsActivity, AccountLoginActivity::class.java), 1) }
        }
        val paste = Button(this).apply {
            text = "Paste token"
            setOnClickListener { promptPaste() }
        }
        val restart = Button(this).apply {
            text = "Restart bridge now"
            setOnClickListener {
                startForegroundService(Intent(this@AccountsActivity, BridgeService::class.java).apply {
                    action = BridgeService.ACTION_RESTART
                })
                Toast.makeText(this@AccountsActivity, "Restarting the bridge", Toast.LENGTH_SHORT).show()
            }
        }

        setContentView(ScrollView(this).apply {
            addView(LinearLayout(this@AccountsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                addView(TextView(this@AccountsActivity).apply {
                    text = "Accounts"
                    textSize = 20f
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(note)
                addView(listBox)
                addView(signIn)
                addView(paste)
                addView(restart)
            })
        })
        render()
    }

    private fun render() {
        listBox.removeAllViews()
        val accounts = AccountStore.list(this)
        if (accounts.isEmpty()) {
            listBox.addView(TextView(this).apply { text = "No accounts saved. The bridge runs as guest." })
            return
        }
        val fmt = SimpleDateFormat("d MMM yyyy HH:mm", Locale.getDefault())
        accounts.forEach { a ->
            val exp = a.expiresAtSec?.let { if (a.isExpired()) "expired" else "expires " + fmt.format(Date(it * 1000)) } ?: "no expiry"
            listBox.addView(TextView(this).apply {
                typeface = Typeface.MONOSPACE
                textSize = 12f
                text = "${a.hint}\n${a.masked()}\n$exp"
                setPadding(0, 12, 0, 4)
            })
            listBox.addView(Button(this).apply {
                text = "Remove"
                setOnClickListener { confirmRemove(a) }
            })
        }
    }

    private fun confirmRemove(a: AccountStore.Account) {
        AlertDialog.Builder(this)
            .setTitle("Remove account?")
            .setMessage("${a.hint} will be deleted from this phone.")
            .setPositiveButton("Remove") { _, _ -> AccountStore.remove(this, a.id); render() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptPaste() {
        val input = EditText(this).apply {
            hint = "eyJ..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
        }
        AlertDialog.Builder(this)
            .setTitle("Paste a Z.AI token")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val msg = when (val r = AccountStore.add(this, input.text.toString())) {
                    is AccountStore.AddResult.Added -> "Account saved (${r.account.hint})"
                    AccountStore.AddResult.Duplicate -> "That account is already saved"
                    AccountStore.AddResult.Guest -> "That is a guest token, not a signed-in account."
                    is AccountStore.AddResult.Invalid -> r.reason
                    is AccountStore.AddResult.StorageError -> "Could not store the token securely: ${r.reason}"
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        render()
    }
}
