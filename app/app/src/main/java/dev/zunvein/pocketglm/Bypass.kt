package dev.zunvein.pocketglm

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executor

/**
 * "Bypass blocking": some networks reset the connection to chat.z.ai as soon
 * as the TLS handshake names the site. The bridge can send that first packet
 * in two pieces, and it runs a local proxy for this; this object points the
 * app's WebViews at that proxy while the switch is on.
 */
object Bypass {
    const val PROXY_HOST = "127.0.0.1"
    const val PROXY_PORT = 3002

    private val main = Handler(Looper.getMainLooper())
    private val direct = Executor { it.run() }

    /**
     * Makes the WebViews use (or stop using) the bypass proxy, then calls [then]
     * on the main thread. It only routes through the proxy when the switch is
     * on AND something is listening, so a stopped bridge never breaks sign-in.
     */
    fun prepare(ctx: Context, then: () -> Unit) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            main.post(then)
            return
        }
        if (!Prefs.bypass(ctx)) {
            clear(then)
            return
        }
        Thread {
            val up = try {
                Socket().use { it.connect(InetSocketAddress(PROXY_HOST, PROXY_PORT), 400); true }
            } catch (e: Exception) {
                false
            }
            main.post {
                if (!up) {
                    clear(then)
                } else {
                    try {
                        val cfg = ProxyConfig.Builder()
                            .addProxyRule("$PROXY_HOST:$PROXY_PORT", ProxyConfig.MATCH_HTTPS)
                            .build()
                        ProxyController.getInstance().setProxyOverride(cfg, direct) { then() }
                    } catch (e: Exception) {
                        then()
                    }
                }
            }
        }.start()
    }

    private fun clear(then: () -> Unit) {
        try {
            ProxyController.getInstance().clearProxyOverride(direct) { then() }
        } catch (e: Exception) {
            main.post(then)
        }
    }
}
