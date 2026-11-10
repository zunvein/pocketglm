package dev.zunvein.pocketglm

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Talks to the local bridge's POST /admin/mint.
 *
 * One WebView visit gives a single genuine device token plus the Log1
 * response that carries the trusted device session. The bridge validates
 * both and mints [n] more tokens straight into its live pool, so a single
 * visit can feed many chat requests.
 */
object MintClient {

    sealed class Outcome {
        /** The bridge inserted [inserted] tokens; the pool now holds [poolSize]. */
        data class Ok(val inserted: Int, val poolSize: Int) : Outcome()

        /** The bridge answered but refused (bad harvest, bad key, pool write failed). */
        data class Rejected(val code: Int, val reason: String) : Outcome()

        /** Nothing listened (bridge stopped) or the connection failed before sending. */
        data class Unreachable(val reason: String) : Outcome()

        /**
         * The request went out but no answer came back in time. The bridge may
         * or may not have inserted tokens, so callers must not insert the
         * genuine token again.
         */
        data class NoAnswer(val reason: String) : Outcome()
    }

    private const val ENDPOINT = "http://127.0.0.1:3001/admin/mint"

    fun mint(apiKey: String, log1: String, token: String, n: Int): Outcome {
        val body = JSONObject()
            .put("log1", log1)
            .put("token", token)
            .put("n", n)
            .toString()
            .toByteArray(Charsets.UTF_8)

        val conn = try {
            (URL(ENDPOINT).openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 60_000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
                setFixedLengthStreamingMode(body.size)
            }
        } catch (e: Exception) {
            return Outcome.Unreachable("${e.javaClass.simpleName}: ${e.message}")
        }

        try {
            try {
                conn.outputStream.use { it.write(body) }
            } catch (e: Exception) {
                return Outcome.Unreachable("${e.javaClass.simpleName}: ${e.message}")
            }

            val code = try {
                conn.responseCode
            } catch (e: SocketTimeoutException) {
                return Outcome.NoAnswer("no reply within 60 s")
            } catch (e: Exception) {
                return Outcome.NoAnswer("${e.javaClass.simpleName}: ${e.message}")
            }

            val text = try {
                (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.readText().orEmpty()
            } catch (e: Exception) {
                ""
            }
            val json = try { JSONObject(text) } catch (e: Exception) { null }

            return if (code == 200 && json != null) {
                Outcome.Ok(json.optInt("inserted", 0), json.optInt("tokenCount", -1))
            } else {
                val reason = json?.optString("error").orEmpty().ifEmpty { "HTTP $code" }
                Outcome.Rejected(code, reason)
            }
        } finally {
            conn.disconnect()
        }
    }
}
