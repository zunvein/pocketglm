package dev.zunvein.pocketglm

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Lets the user sign in to chat.z.ai in a WebView.
 *
 * chat.z.ai gives every visitor an anonymous guest token the moment the page
 * opens, so the app must not grab a token on its own. It waits until the user
 * taps "I am signed in", reads the page's token once, refuses it if it is
 * still a guest token, and otherwise saves it encrypted through
 * [AccountStore]. The WebView's cookies and storage are then wiped so the
 * token farm keeps visiting as an anonymous browser.
 *
 * Google sign-in is usually refused inside a WebView; email login works.
 */
class AccountLoginActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var confirm: Button

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val hint = TextView(this).apply {
            setPadding(32, 24, 32, 12)
            text = "Sign in with your email below. When the chat page shows your account, tap the button."
        }
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
        }
        confirm = Button(this).apply {
            text = "I am signed in"
            setOnClickListener { readToken() }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(hint)
            addView(webView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(confirm)
        })
        webView.loadUrl("https://chat.z.ai/auth")
    }

    private fun readToken() {
        confirm.isEnabled = false
        webView.evaluateJavascript(
            "(function(){try{return localStorage.getItem('token')||''}catch(e){return ''}})()"
        ) { raw ->
            confirm.isEnabled = true
            val token = raw?.trim('"').orEmpty()
            if (!token.startsWith("eyJ")) {
                toast("No login found yet. Finish signing in first.")
                return@evaluateJavascript
            }
            when (val r = AccountStore.add(applicationContext, token)) {
                is AccountStore.AddResult.Added -> {
                    toast("Account saved (${r.account.hint})")
                    wipeAndClose()
                }
                AccountStore.AddResult.Duplicate -> {
                    toast("That account is already saved")
                    wipeAndClose()
                }
                AccountStore.AddResult.Guest ->
                    toast("This is still a guest session. Sign in with email first, then tap again.")
                is AccountStore.AddResult.Invalid -> toast(r.reason)
                is AccountStore.AddResult.StorageError ->
                    toast("Could not store the token securely: ${r.reason}")
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun wipeAndClose() {
        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()
        setResult(RESULT_OK)
        finish()
    }

    override fun onDestroy() {
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }
}
