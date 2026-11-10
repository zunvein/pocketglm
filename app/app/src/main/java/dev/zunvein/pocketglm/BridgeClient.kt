package dev.zunvein.pocketglm

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

/** Talks to the local bridge over loopback. */
object BridgeClient {
    private const val BASE = "http://127.0.0.1:3001"

    data class Health(
        val up: Boolean,
        /** The bridge answers but is still warming up (HTTP 503). */
        val warming: Boolean,
        val healthy: Boolean,
        val tokens: Int,
        val breaker: String,
        val computeOk: Long,
        val computeFail: Long,
        val poolAgeSec: Long,
        val error: String?,
    )

    val DOWN = Health(false, false, false, -1, "", 0, 0, -1, null)

    /** GET /health. The body is read for 503 too: that is the warm-up state. */
    fun health(): Health {
        return try {
            val conn = URL("$BASE/health").openConnection(Proxy.NO_PROXY) as HttpURLConnection
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            val code = conn.responseCode
            val body = (if (code >= 400) conn.errorStream else conn.inputStream)
                ?.bufferedReader()?.readText().orEmpty()
            conn.disconnect()
            try {
                val o = JSONObject(body)
                val c = o.optJSONObject("captcha")
                Health(
                    up = true,
                    warming = code != 200,
                    healthy = o.optBoolean("healthy"),
                    tokens = o.optInt("tokenCount", -1),
                    breaker = c?.optString("breaker").orEmpty(),
                    computeOk = c?.optLong("computeOK", 0) ?: 0,
                    computeFail = c?.optLong("computeFail", 0) ?: 0,
                    poolAgeSec = c?.optLong("poolAgeSec", -1) ?: -1,
                    error = null,
                )
            } catch (e: Exception) {
                DOWN.copy(up = true, warming = true, error = "unreadable /health answer")
            }
        } catch (e: Exception) {
            DOWN.copy(error = e.javaClass.simpleName)
        }
    }

    /** GET /v1/models with the key: proves auth and routing work. */
    fun testModels(key: String): String {
        return try {
            val conn = URL("$BASE/v1/models").openConnection(Proxy.NO_PROXY) as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 20000
            conn.setRequestProperty("Authorization", "Bearer $key")
            val code = conn.responseCode
            val body = (if (code >= 400) conn.errorStream else conn.inputStream)
                ?.bufferedReader()?.readText().orEmpty()
            conn.disconnect()
            "HTTP $code\n" + body.take(400)
        } catch (e: Exception) {
            "Failed: ${e.javaClass.simpleName}: ${e.message}\n(is the bridge started?)"
        }
    }
}
