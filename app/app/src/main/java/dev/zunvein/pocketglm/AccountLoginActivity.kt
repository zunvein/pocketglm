package dev.zunvein.pocketglm

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.Toast

/**
 * Sign in to chat.z.ai inside the app.
 *
 * Two things matter here:
 *  - A WebView with NO WebViewClient hands every navigation to the system,
 *    which is why the Google / GitHub buttons used to jump out to the default
 *    browser. The client below keeps http(s) pages inside this WebView.
 *  - chat.z.ai gives every visitor an anonymous guest token on arrival, so the
 *    token is only read after the user taps "I am signed in", and a guest
 *    token is refused.
 *
 * Google often refuses sign-in from embedded browsers; email and GitHub
 * work. The WebView is wiped before and after, so the farm stays anonymous.
 */
class AccountLoginActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var confirm: android.widget.Button
    private lateinit var status: android.widget.TextView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wipe()

        status = Ui.text(this, "Sign in below. When the chat page shows your account, tap the button.", 13f, R.color.textDim).apply {
            setPadding(Ui.dp(this@AccountLoginActivity, 16), Ui.dp(this@AccountLoginActivity, 12), Ui.dp(this@AccountLoginActivity, 16), Ui.dp(this@AccountLoginActivity, 8))
        }
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = MOBILE_UA
            // window.open() from the provider buttons loads in this same view.
            settings.javaScriptCanOpenWindowsAutomatically = true
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val scheme = request.url.scheme
                    // Pages stay here; app links (intent:, market:, ...) are ignored.
                    return scheme != "http" && scheme != "https"
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    status.text = url?.substringAfter("://")?.substringBefore('/') ?: ""
                }
            }
        }
        confirm = Ui.button(this, "I am signed in", Ui.Kind.PRIMARY).apply {
            setOnClickListener { readToken() }
        }
        val reloadBtn = Ui.button(this, "Reload", Ui.Kind.TONAL).apply {
            setOnClickListener { webView.loadUrl("https://chat.z.ai/auth") }
        }
        val pad = Ui.dp(this, 12)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(webView, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
            addView(LinearLayout(this@AccountLoginActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(reloadBtn, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { marginEnd = Ui.dp(this@AccountLoginActivity, 8) })
                addView(confirm, LinearLayout.LayoutParams(0, Ui.WRAP, 2f))
            }, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { setMargins(pad, pad, pad, pad) })
        })
        Bypass.prepare(this) { if (!isDestroyed) webView.loadUrl("https://chat.z.ai/auth") }
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
                is AccountStore.AddResult.Added -> { toast("Account saved (${r.account.hint})"); wipeAndClose() }
                AccountStore.AddResult.Duplicate -> { toast("That account is already saved"); wipeAndClose() }
                AccountStore.AddResult.Guest ->
                    toast("This is still a guest session. Sign in first, then tap again.")
                is AccountStore.AddResult.Invalid -> toast(r.reason)
                is AccountStore.AddResult.StorageError ->
                    toast("Could not store the token securely: ${r.reason}")
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun wipe() {
        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()
    }

    private fun wipeAndClose() {
        wipe()
        setResult(RESULT_OK)
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        // Chrome on Android, without the "wv" marker that embedded WebViews carry.
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/133.0.0.0 Mobile Safari/537.36"
    }
}
