package dev.zunvein.pocketglm

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Harvests Aliyun captcha device tokens on this phone, using the system
 * WebView as the "headful browser".
 *
 * Mirrors cmd/token-collector/harvest.go:
 *  1. load chat.z.ai so origin/referrer/TLS look like a real visit,
 *  2. capture the device service Log1 response (it carries the trusted
 *     device session) with an XHR/fetch hook,
 *  3. inject the Aliyun captcha SDK boot script and wait for z_um.getToken()
 *     to produce one genuine device token.
 *
 * One visit yields ONE genuine token, but a chat request burns one token
 * each. So the Log1 response and the genuine token are handed to the local
 * bridge (POST /admin/mint), which mints a whole batch bound to the same
 * session and inserts it into the live pool. If the bridge cannot do that,
 * the genuine token alone goes into the pool.
 */
class TokenFarmActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var logView: TextView
    private val ui = Handler(Looper.getMainLooper())

    // Only touched on the main thread.
    private var log1Body: String? = null
    private var deviceToken: String? = null
    private var finished = false
    private var auto = false
    private var mintCount = DEFAULT_MINT

    private val log1Deadline = Runnable { settle(reason = "Log1 not seen in time") }
    private val watchdog = Runnable {
        if (!finished) {
            finished = true
            log("Gave up: the captcha script did not finish in ${WATCHDOG_MS / 1000} s")
            finishLater(false)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auto = intent.getBooleanExtra(EXTRA_AUTO, false)
        mintCount = intent.getIntExtra(EXTRA_MINT, DEFAULT_MINT).coerceIn(0, 1000)
        running = true

        logView = TextView(this).apply {
            setPadding(32, 32, 32, 32)
            setTextIsSelectable(true)
            text = "Starting token farm..."
        }

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = DESKTOP_UA
        }
        webView.addJavascriptInterface(HarvestBridge(), "PocketGLM")
        webView.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                // Belt and braces next to the document-start script. The hook
                // is idempotent, so running it twice is harmless.
                if (url?.startsWith("https://chat.z.ai") == true) {
                    view?.evaluateJavascript(HOOK_JS, null)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (!bootInjected && url?.startsWith("https://chat.z.ai") == true) {
                    bootInjected = true
                    view?.evaluateJavascript(HARVEST_BOOT_JS, null)
                    log("Page loaded, harvest script injected")
                }
            }
        }
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(webView, HOOK_JS, setOf("https://chat.z.ai"))
            }
        } catch (e: Exception) {
            // onPageStarted below installs the same hook.
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(webView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 2f))
            addView(logView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)

        ui.postDelayed(watchdog, WATCHDOG_MS)
        webView.loadUrl("https://chat.z.ai")
    }

    private var bootInjected = false

    /**
     * JS-callable bridge. These callbacks run on a private WebView binder
     * thread, NOT the UI thread, so all state changes hop to the main thread.
     */
    private inner class HarvestBridge {
        @android.webkit.JavascriptInterface
        fun onLog1(body: String) {
            ui.post {
                if (finished || log1Body != null) return@post
                log1Body = body
                log("Device session captured (${body.length} bytes)")
                if (deviceToken != null) settle(reason = null)
            }
        }

        @android.webkit.JavascriptInterface
        fun onToken(token: String) {
            ui.post {
                if (finished || deviceToken != null) return@post
                deviceToken = token
                log("Genuine device token harvested (${token.length} chars)")
                if (log1Body != null) {
                    settle(reason = null)
                } else {
                    // Log1 normally arrives before the token; give it a moment.
                    ui.postDelayed(log1Deadline, LOG1_GRACE_MS)
                }
            }
        }

        @android.webkit.JavascriptInterface
        fun onError(msg: String) {
            ui.post { log("Error: $msg") }
        }
    }

    /**
     * Both halves are in (or the grace period ran out): hand the harvest to
     * the bridge on a worker thread. Runs once.
     */
    private fun settle(reason: String?) {
        if (finished) return
        val token = deviceToken ?: return
        finished = true
        ui.removeCallbacks(log1Deadline)
        ui.removeCallbacks(watchdog)
        val log1 = log1Body
        if (log1 == null) log("Device session missing (${reason ?: "unknown"}), falling back to a single token")

        val appCtx = applicationContext
        val n = mintCount
        Thread {
            val ok = deliver(appCtx, log1, token, n)
            ui.post { finishLater(ok) }
        }.start()
    }

    /** Returns true when at least one token reached the pool (or may have). */
    private fun deliver(ctx: android.content.Context, log1: String?, token: String, n: Int): Boolean {
        if (log1 != null && n > 0) {
            when (val r = MintClient.mint(ApiKeyStore.get(ctx), log1, token, n)) {
                is MintClient.Outcome.Ok -> {
                    log("Minted a batch: ${r.inserted} tokens added, pool now ${r.poolSize}")
                    return true
                }
                is MintClient.Outcome.NoAnswer -> {
                    // The bridge may have inserted already; inserting the
                    // genuine token again could duplicate it.
                    log("Bridge did not answer (${r.reason}). Check the pool count on the main screen.")
                    return false
                }
                is MintClient.Outcome.Unreachable ->
                    log("Bridge is not reachable (${r.reason}). Start the bridge first to get a full batch.")
                is MintClient.Outcome.Rejected ->
                    log("Bridge refused the batch (HTTP ${r.code}: ${r.reason}).")
            }
        }
        return try {
            TokenStore.insert(ctx, token)
            log("Single token saved to the pool (pool: ${TokenStore.count(ctx)})")
            true
        } catch (e: Exception) {
            log("Could not save the token: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun finishLater(ok: Boolean) {
        lastResultOk = ok
        lastFinishedAt = System.currentTimeMillis()
        setResult(if (ok) RESULT_OK else RESULT_CANCELED)
        if (auto) ui.postDelayed({ if (!isFinishing) finish() }, if (ok) 2500L else 6000L)
    }

    private fun log(msg: String) {
        runOnUiThread { logView.text = "${logView.text}\n$msg" }
    }

    override fun onDestroy() {
        running = false
        ui.removeCallbacksAndMessages(null)
        webView.stopLoading()
        webView.removeJavascriptInterface("PocketGLM")
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_AUTO = "auto"
        const val EXTRA_MINT = "mint"

        /** Tokens minted per visit; the genuine one comes on top. */
        const val DEFAULT_MINT = 60

        private const val LOG1_GRACE_MS = 15_000L
        private const val WATCHDOG_MS = 120_000L

        /** True while a farm screen is open (guards against double launches). */
        @Volatile var running = false
        @Volatile var lastResultOk = false
        @Volatile var lastFinishedAt = 0L

        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

        /**
         * Reports the device service's Log1 response (the one carrying
         * DeviceConfig) to window.PocketGLM.onLog1. WebView response bodies
         * are not exposed to Java, so the page's own XHR/fetch is wrapped.
         */
        private const val HOOK_JS = """
        (function () {
            if (window.__pgl_hooked) return;
            window.__pgl_hooked = true;
            var MATCH = 'cloudauth-device-dualstack';
            function send(t) {
                try {
                    if (t && String(t).indexOf('DeviceConfig') >= 0) window.PocketGLM.onLog1(String(t));
                } catch (e) {}
            }
            var X = XMLHttpRequest.prototype, open = X.open, snd = X.send;
            X.open = function (m, u) { this.__pgl_u = String(u); return open.apply(this, arguments); };
            X.send = function () {
                var self = this;
                if (self.__pgl_u && self.__pgl_u.indexOf(MATCH) >= 0) {
                    self.addEventListener('load', function () {
                        try { send(self.responseText); } catch (e) {}
                    });
                }
                return snd.apply(this, arguments);
            };
            var f = window.fetch;
            if (f) {
                window.fetch = function (input, init) {
                    var u = (typeof input === 'string') ? input : ((input && input.url) || '');
                    var p = f.apply(this, arguments);
                    if (String(u).indexOf(MATCH) >= 0) {
                        p.then(function (r) { return r.clone().text(); }).then(send).catch(function () {});
                    }
                    return p;
                };
            }
        })();
        """

        /**
         * Port of bootCaptchaJS (harvest.go). Differences from the
         * Playwright original: results are reported to the app via
         * window.PocketGLM instead of a page Evaluate return value, and the
         * Log1 fetch is re-issued through fetch() so we can capture its body
         * (WebView response bodies are not exposed to Java).
         */
        private const val HARVEST_BOOT_JS = """
        (async () => {
            const sleep = (ms) => new Promise(r => setTimeout(r, ms));
            const report = (m) => { try { window.PocketGLM.onError(String(m)); } catch (e) {} };
            try {
                window.AliyunCaptchaConfig = { region: 'sgp', prefix: 'no8xfe' };
                if (!window.initAliyunCaptcha) {
                    await new Promise((res, rej) => {
                        const s = document.createElement('script');
                        s.src = 'https://o.alicdn.com/captcha-frontend/aliyunCaptcha/AliyunCaptcha.js';
                        s.onload = res;
                        s.onerror = () => rej(new Error('captcha sdk load failed'));
                        document.head.appendChild(s);
                    });
                }
                const el = document.createElement('div');
                el.id = '__zai_cap_el';
                el.style.cssText = 'position:fixed;left:10px;top:200px;width:320px;height:200px;z-index:2147483647;';
                document.body.appendChild(el);
                const btn = document.createElement('button');
                btn.id = '__zai_cap_btn';
                btn.type = 'button';
                btn.textContent = 'go';
                btn.style.cssText = 'position:fixed;left:10px;top:420px;width:120px;height:40px;z-index:2147483647;';
                document.body.appendChild(btn);

                let instance = null;
                window.initAliyunCaptcha({
                    SceneId: 'didk33e0',
                    mode: 'embed',
                    element: '#__zai_cap_el',
                    button: '#__zai_cap_btn',
                    captchaLogoImg: 'https://z-cdn.chatglm.cn/z-ai/static/logo.svg',
                    upLang: true,
                    language: 'en',
                    timeout: 10000,
                    delayBeforeSuccess: false,
                    success: () => {}, fail: () => {}, onError: () => {}, onClose: () => {},
                    getInstance: (i) => { instance = i; window.__zai_cap_inst = i; },
                });

                const t0 = Date.now();
                while (Date.now() - t0 < 60000) {
                    if (instance && instance.captcha && window.z_um &&
                        typeof window.z_um.getToken === 'function') break;
                    await sleep(200);
                }
                if (!window.z_um || typeof window.z_um.getToken !== 'function') {
                    report('captcha SDK did not expose z_um.getToken');
                    return;
                }
                await sleep(3000);
                const tok = window.z_um.getToken();
                const token = (tok && typeof tok.then === 'function') ? await tok : tok;
                if (!token) { report('empty device token'); return; }
                window.PocketGLM.onToken(String(token));
            } catch (e) {
                report((e && e.stack) || e);
            }
        })();
        """
    }
}
