package dev.zunvein.pocketglm

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Gravity
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Sign in to chat.z.ai inside the app.
 *
 * Two things matter here:
 *  - a WebView with NO WebViewClient hands every navigation to the system,
 *    which is why the Google and GitHub buttons used to jump to the browser;
 *    the client below keeps http(s) pages inside this view;
 *  - chat.z.ai hands every visitor an anonymous guest token on arrival, so the
 *    token is only read after the user taps "I am signed in", and a guest token
 *    is refused.
 *
 * The WebView is wiped before and after, so the farm stays anonymous.
 */
class AccountLoginActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var confirm: android.widget.Button
    private lateinit var status: TextView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        wipe()

        status = Ui.fine(this, "Sign in below. When the chat page shows your account, tap the button.").apply {
            setPadding(Ui.dip(this@AccountLoginActivity, 16), Ui.dip(this@AccountLoginActivity, 12), Ui.dip(this@AccountLoginActivity, 16), Ui.dip(this@AccountLoginActivity, 8))
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
        confirm = Ui.primary(this, "I am signed in") { readToken() }
        val reload = Ui.small(this, "Reload") { webView.loadUrl("https://chat.z.ai/auth") }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dip(this@AccountLoginActivity, 12), 0, Ui.dip(this@AccountLoginActivity, 12), 0)
            addView(status, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            addView(reload)
        }
        val pad = Ui.dip(this, 12)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.resColor(this@AccountLoginActivity, R.color.bg))
            addView(bar, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
            addView(webView, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
            addView(confirm, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { setMargins(pad, pad, pad, pad) })
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
                AccountStore.AddResult.Guest -> toast("This is still a guest session. Sign in first, then tap again.")
                is AccountStore.AddResult.Invalid -> toast(r.reason)
                is AccountStore.AddResult.StorageError -> toast("Could not store the token securely: ${r.reason}")
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
