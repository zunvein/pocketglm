package dev.zunvein.pocketglm

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Lets the user sign in to chat.z.ai in a WebView. Once the page stores its
 * session token, it is read once, saved encrypted through [AccountStore], and
 * the WebView's cookies and storage are wiped so the token farm keeps
 * visiting as an anonymous browser.
 *
 * Google sign-in is usually refused inside a WebView; email login works.
 */
class AccountLoginActivity : Activity() {

    private lateinit var webView: WebView
    private val ui = Handler(Looper.getMainLooper())
    private var done = false

    private val poll = object : Runnable {
        override fun run() {
            if (done) return
            webView.evaluateJavascript("(function(){try{return localStorage.getItem('token')||''}catch(e){return ''}})()") { raw ->
                val token = raw?.trim('"').orEmpty()
                if (!done && token.startsWith("eyJ")) onToken(token)
            }
            ui.postDelayed(this, 1500)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val hint = TextView(this).apply {
            setPadding(32, 24, 32, 24)
            text = "Sign in with email. The app saves the session token once you are in."
        }
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(hint)
            addView(webView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        webView.loadUrl("https://chat.z.ai/auth")
        ui.postDelayed(poll, 3000)
    }

    private fun onToken(token: String) {
        done = true
        val msg = when (val r = AccountStore.add(applicationContext, token)) {
            is AccountStore.AddResult.Added -> "Account saved (${r.account.hint})"
            AccountStore.AddResult.Duplicate -> "That account is already saved"
            is AccountStore.AddResult.Invalid -> r.reason
            is AccountStore.AddResult.StorageError -> "Could not store the token securely: ${r.reason}"
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()
        setResult(RESULT_OK)
        finish()
    }

    override fun onDestroy() {
        done = true
        ui.removeCallbacksAndMessages(null)
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }
}
