package dev.zunvein.pocketglm

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/** Shared by the visible farm screen, the background farm and the auto top-up. */
object FarmState {
    @Volatile var running = false
    @Volatile var lastOk = false
    @Volatile var lastFinishedAt = 0L
    @Volatile var lastStartedAt = 0L
    @Volatile var lastSummary = ""
    /** Consecutive failed background runs; two in a row means "ask the user". */
    @Volatile var bgFailStreak = 0

    /** 2 min after a success, 10 min after a failure, so a broken farm cannot loop. */
    fun cooldownOk(now: Long = System.currentTimeMillis()): Boolean {
        val last = maxOf(lastStartedAt, lastFinishedAt)
        if (last == 0L) return true
        return now - last >= if (lastOk) 120_000L else 600_000L
    }
}

/**
 * One token-farming run: open chat.z.ai in a WebView, capture the device
 * session (Log1 response) and the one genuine device token the Aliyun
 * captcha SDK produces, then hand both to the local bridge (POST
 * /admin/mint), which verifies them against Aliyun and mints a batch.
 * If the bridge cannot do that, the single genuine token goes into the pool.
 *
 * The same engine runs in the visible farm screen and in the hidden
 * background farm. All public methods and [onDone] run on the main thread;
 * [log] may be called from any thread.
 */
class Farmer(
    ctx: Context,
    private val mintCount: Int,
    private val log: (String) -> Unit,
    private val onDone: (ok: Boolean, summary: String) -> Unit,
) {
    private val appCtx = ctx.applicationContext
    private val ui = Handler(Looper.getMainLooper())

    val webView: WebView = WebView(ctx)

    private var log1Body: String? = null
    private var deviceToken: String? = null
    private var settled = false
    private var destroyed = false
    private var bootInjected = false
    private var pageFailed = false
    private var owns = false

    private val log1Deadline = Runnable { settle("Log1 not seen in time") }
    private val watchdog = Runnable {
        if (!settled) {
            settled = true
            val msg = "Gave up: the captcha script did not finish in ${WATCHDOG_MS / 1000} s"
            log(msg)
            finish(false, msg)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun start() {
        owns = true
        FarmState.running = true
        FarmState.lastStartedAt = System.currentTimeMillis()
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.userAgentString = DESKTOP_UA
        webView.addJavascriptInterface(HarvestBridge(), "PocketGLM")
        webView.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                // Belt and braces next to the document-start script; the hook is idempotent.
                if (url?.startsWith("https://chat.z.ai") == true) {
                    view?.evaluateJavascript(FarmJs.HOOK_JS, null)
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                if (request?.isForMainFrame != true || settled || destroyed) return
                pageFailed = true
                settled = true
                ui.removeCallbacks(watchdog)
                val why = error?.description?.toString().orEmpty().ifEmpty { "unknown error" }
                val hint = if (Prefs.bypass(appCtx)) "" else " Your network may block chat.z.ai: try the \"Bypass blocking\" switch on the Home tab."
                val msg = "chat.z.ai could not be loaded ($why).$hint"
                log(msg)
                finish(false, "chat.z.ai not reachable")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (pageFailed) return
                if (!bootInjected && url?.startsWith("https://chat.z.ai") == true) {
                    bootInjected = true
                    view?.evaluateJavascript(FarmJs.HARVEST_BOOT_JS, null)
                    log("Page loaded, harvest script injected")
                }
            }
        }
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(webView, FarmJs.HOOK_JS, setOf("https://chat.z.ai"))
            }
        } catch (e: Exception) {
            // onPageStarted installs the same hook.
        }
        ui.postDelayed(watchdog, WATCHDOG_MS)
        Bypass.prepare(appCtx) {
            if (!destroyed && !settled) webView.loadUrl("https://chat.z.ai")
        }
    }

    /** Gives a WebView that is not on screen a real size, so the page lays out. */
    fun layoutOffscreen(widthPx: Int = 1080, heightPx: Int = 1920) {
        webView.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(widthPx, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(heightPx, android.view.View.MeasureSpec.EXACTLY),
        )
        webView.layout(0, 0, widthPx, heightPx)
        webView.onResume()
        webView.resumeTimers()
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        if (owns) {
            owns = false
            FarmState.running = false
        }
        ui.removeCallbacksAndMessages(null)
        try {
            webView.stopLoading()
            webView.removeJavascriptInterface("PocketGLM")
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            webView.destroy()
        } catch (ignored: Exception) {
        }
    }

    /** JS callbacks arrive on a private binder thread, so everything hops to main. */
    private inner class HarvestBridge {
        @android.webkit.JavascriptInterface
        fun onLog1(body: String) {
            ui.post {
                if (settled || destroyed || log1Body != null) return@post
                log1Body = body
                log("Device session captured (${body.length} bytes)")
                if (deviceToken != null) settle(null)
            }
        }

        @android.webkit.JavascriptInterface
        fun onToken(token: String) {
            ui.post {
                if (settled || destroyed || deviceToken != null) return@post
                deviceToken = token
                log("Genuine device token harvested (${token.length} chars)")
                if (log1Body != null) settle(null) else ui.postDelayed(log1Deadline, LOG1_GRACE_MS)
            }
        }

        @android.webkit.JavascriptInterface
        fun onError(msg: String) {
            ui.post { log("Error: $msg") }
        }
    }

    private fun settle(reason: String?) {
        if (settled || destroyed) return
        val token = deviceToken ?: return
        settled = true
        ui.removeCallbacks(log1Deadline)
        ui.removeCallbacks(watchdog)
        val log1 = log1Body
        if (log1 == null) log("Device session missing (${reason ?: "unknown"}), falling back to a single token")
        val n = mintCount
        Thread {
            val (ok, summary) = deliver(log1, token, n)
            ui.post { finish(ok, summary) }
        }.start()
    }

    private fun finish(ok: Boolean, summary: String) {
        if (destroyed) return
        owns = false
        FarmState.lastOk = ok
        FarmState.lastSummary = summary
        FarmState.lastFinishedAt = System.currentTimeMillis()
        FarmState.running = false
        onDone(ok, summary)
    }

    private fun deliver(log1: String?, token: String, n: Int): Pair<Boolean, String> {
        if (log1 != null && n > 0) {
            when (val r = MintClient.mint(ApiKeyStore.get(appCtx), log1, token, n)) {
                is MintClient.Outcome.Ok -> {
                    return when (r.minted) {
                        "rejected" -> {
                            val why = when (r.genuine) {
                                "ok" -> "Aliyun accepts this phone's own token but refuses the minted ones, so the minting does not match this phone's browser."
                                "rejected" -> "Aliyun refuses even this phone's own token, so the phone's browser is not trusted."
                                else -> "Aliyun refused the minted tokens and the check on the phone's own token was inconclusive."
                            }
                            log("Batch NOT added. $why Pool: ${r.poolSize}")
                            false to "batch rejected by Aliyun"
                        }
                        "ok" -> {
                            log("Minted batch verified by Aliyun: ${r.inserted} tokens added, pool now ${r.poolSize}")
                            true to "+${r.inserted} tokens (pool ${r.poolSize})"
                        }
                        else -> {
                            log("Minted batch added but not fully verified (${r.minted}): ${r.inserted} tokens, pool now ${r.poolSize}")
                            true to "+${r.inserted} tokens, unverified (pool ${r.poolSize})"
                        }
                    }
                }
                is MintClient.Outcome.NoAnswer -> {
                    // The bridge may have inserted already; adding the genuine token again could duplicate it.
                    log("Bridge did not answer (${r.reason}). Check the pool count on the main screen.")
                    return false to "bridge did not answer"
                }
                is MintClient.Outcome.Unreachable ->
                    log("Bridge is not reachable (${r.reason}). Start the bridge first to get a full batch.")
                is MintClient.Outcome.Rejected ->
                    log("Bridge refused the batch (HTTP ${r.code}: ${r.reason}).")
            }
        }
        return try {
            TokenStore.insert(appCtx, token)
            val count = TokenStore.count(appCtx)
            log("Single token saved to the pool (pool: $count)")
            true to "+1 token (pool $count)"
        } catch (e: Exception) {
            log("Could not save the token: ${e.javaClass.simpleName}: ${e.message}")
            false to "could not save the token"
        }
    }

    companion object {
        const val DEFAULT_MINT = 60
        private const val LOG1_GRACE_MS = 15_000L
        private const val WATCHDOG_MS = 120_000L
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
    }
}

/** The page scripts. Kept apart so the engine above stays readable. */
object FarmJs {
    const val HOOK_JS = """
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
    const val HARVEST_BOOT_JS = """
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
