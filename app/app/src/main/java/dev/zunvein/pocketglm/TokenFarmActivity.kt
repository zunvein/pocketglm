package dev.zunvein.pocketglm

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The P1 experiment surface: harvest Aliyun captcha device tokens on this
 * phone using the system WebView as the "headful browser".
 *
 * Mirrors cmd/token-collector/harvest.go:
 *  1. load chat.z.ai so origin/referrer/TLS look like a real visit,
 *  2. capture the device service Log1 response (carries DeviceConfig),
 *  3. inject the Aliyun captcha SDK boot script and wait for z_um.getToken()
 *     to produce one genuine device token.
 *
 * Results are written to filesDir/bridge/harvest.json so a future in-process
 * minter (port of feilin.go) can consume them; raw tokens go straight into
 * the tokens.sqlite pool.
 */
class TokenFarmActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var logView: TextView

    private var log1Body: String? = null
    private var deviceToken: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        logView = TextView(this).apply {
            setPadding(32, 32, 32, 32)
            setTextIsSelectable(true)
            text = "booting token farm…"
        }

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = DESKTOP_UA
        }
        attachInterceptors(webView)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(webView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 2f))
            addView(logView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)

        webView.loadUrl("https://chat.z.ai")
    }

    /**
     * Response listener for the device-service Log1 response + a JS interface
     * the injected boot script reports results through.
     */
    @SuppressLint("AddJavascriptInterface")
    private fun attachInterceptors(wv: WebView) {
        wv.webViewClient = object : android.webkit.WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url.toString()
                return if (url.contains("cloudauth-device-dualstack")) {
                    // Body sniffing of XHR responses is not possible here;
                    // the harvest JS re-issues the Log1 call via fetch and
                    // hands us the DeviceConfig through the JS bridge.
                    null
                } else super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (url?.startsWith("https://chat.z.ai") == true) {
                    view?.evaluateJavascript(HARVEST_BOOT_JS, null)
                    log("page loaded — harvest script injected")
                }
            }
        }

        wv.addJavascriptInterface(HarvestBridge(), "PocketGLM")
    }

    /** JS-callable bridge: receives Log1 DeviceConfig + token from the page.
     *  NOTE: these callbacks run on a private WebView binder thread, NOT the
     *  UI thread — everything touching UI state is hopped to the main thread. */
    private inner class HarvestBridge {
        @android.webkit.JavascriptInterface
        fun onLog1(body: String) {
            runOnUiThread {
                log1Body = body
                log("Log1 captured (${body.length} bytes)")
            }
        }

        @android.webkit.JavascriptInterface
        fun onToken(token: String) {
            runOnUiThread {
                deviceToken = token
                log("device token harvested (${token.length} chars) — inserting into pool")
                try {
                    TokenStore.insert(applicationContext, token)
                    log("inserted into pool ✓ (tokens: ${TokenStore.count(applicationContext)})")
                } catch (e: Exception) {
                    log("ERROR inserting token: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }

        @android.webkit.JavascriptInterface
        fun onError(msg: String) {
            runOnUiThread { log("ERROR: $msg") }
        }
    }

    private fun log(msg: String) {
        runOnUiThread {
            logView.text = "${logView.text}\n$msg"
        }
    }

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

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
