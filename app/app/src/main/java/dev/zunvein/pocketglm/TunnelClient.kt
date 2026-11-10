package dev.zunvein.pocketglm

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

/** Talks to the local bridge over loopback: the tunnel status, start and stop endpoints. */
object TunnelClient {
    private const val BASE = "http://127.0.0.1:3001/admin/tunnel"

    data class Status(
        /** The bridge answered the status call. */
        val reachable: Boolean,
        /** The bridge was started with an authtoken. */
        val enabled: Boolean,
        val online: Boolean,
        val starting: Boolean,
        val url: String,
        val error: String,
    )

    val UNKNOWN = Status(false, false, false, false, "", "")

    private fun open(path: String, key: String, post: Boolean): HttpURLConnection =
        (URL("$BASE/$path").openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
            connectTimeout = 2000
            readTimeout = 5000
            setRequestProperty("Authorization", "Bearer $key")
            if (post) {
                requestMethod = "POST"
                doOutput = true
                outputStream.use { }
            }
        }

    private fun body(conn: HttpURLConnection, code: Int): String =
        (if (code >= 400) conn.errorStream else conn.inputStream)?.bufferedReader()?.readText().orEmpty()

    fun status(key: String): Status {
        return try {
            val conn = open("status", key, false)
            val code = conn.responseCode
            val text = body(conn, code)
            conn.disconnect()
            if (code != 200) return UNKNOWN
            val o = JSONObject(text)
            Status(
                reachable = true,
                enabled = o.optBoolean("enabled"),
                online = o.optBoolean("online"),
                starting = o.optBoolean("starting"),
                url = o.optString("url"),
                error = o.optString("error"),
            )
        } catch (e: Exception) {
            UNKNOWN
        }
    }

    /** Returns null on success, else a short reason. */
    fun start(key: String): String? = post("start", key)

    fun stop(key: String): String? = post("stop", key)

    private fun post(path: String, key: String): String? {
        return try {
            val conn = open(path, key, true)
            val code = conn.responseCode
            val text = body(conn, code)
            conn.disconnect()
            if (code == 200) return null
            val reason = try { JSONObject(text).optString("error") } catch (e: Exception) { "" }
            // Starting something that is already up or coming up is not an error.
            if (code == 409 && reason.contains("already")) return null
            if (reason.isNotEmpty()) reason else "HTTP $code"
        } catch (e: Exception) {
            "Bridge not reachable (${e.javaClass.simpleName})"
        }
    }
}
