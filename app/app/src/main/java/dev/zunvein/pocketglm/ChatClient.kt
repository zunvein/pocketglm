package dev.zunvein.pocketglm

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

/** Chat requests from inside the app to the local bridge (streaming). */
object ChatClient {
    private const val BASE = "http://127.0.0.1:3001"

    /** Shown until the bridge answers /v1/models. */
    val FALLBACK_MODELS = listOf("glm-5.2", "GLM-5.1", "GLM-5-Turbo", "GLM-5v-Turbo", "glm-4.7")

    sealed class Event {
        class Content(val text: String) : Event()
        class Reasoning(val text: String) : Event()
        class Error(val text: String) : Event()
    }

    /** Lets the UI stop a request that is waiting on the network. */
    class Handle {
        @Volatile var conn: HttpURLConnection? = null
        @Volatile var cancelled = false

        fun cancel() {
            cancelled = true
            try { conn?.disconnect() } catch (ignored: Exception) {}
        }
    }

    /** Model ids the bridge offers right now; empty when it cannot be asked. */
    fun models(key: String): List<String> {
        return try {
            val conn = URL("$BASE/v1/models").openConnection(Proxy.NO_PROXY) as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 20000
            conn.setRequestProperty("Authorization", "Bearer $key")
            val code = conn.responseCode
            val body = if (code == 200) conn.inputStream.bufferedReader().readText() else ""
            conn.disconnect()
            val arr = JSONObject(body).optJSONArray("data") ?: return emptyList()
            val out = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val id = arr.optJSONObject(i)?.optString("id").orEmpty()
                if (id.isNotEmpty()) out.add(id)
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * POST /v1/chat/completions with stream=true. [onEvent] runs on the
     * calling (background) thread. Returns when the stream ends, fails or is
     * cancelled through [handle].
     */
    fun stream(key: String, model: String, messages: JSONArray, handle: Handle, onEvent: (Event) -> Unit) {
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("stream", true)
            .toString()
            .toByteArray(Charsets.UTF_8)
        var conn: HttpURLConnection? = null
        try {
            conn = URL("$BASE/v1/chat/completions").openConnection(Proxy.NO_PROXY) as HttpURLConnection
            handle.conn = conn
            conn.connectTimeout = 3000
            conn.readTimeout = 120_000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val text = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                onEvent(Event.Error(describe(code, text)))
                return
            }
            conn.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val o = parse(data) ?: continue
                    val err = o.optJSONObject("error")
                    if (err != null) {
                        onEvent(Event.Error(str(err, "message").ifEmpty { "The bridge reported an error." }))
                        continue
                    }
                    val delta = o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: continue
                    val reasoning = str(delta, "reasoning_content")
                    if (reasoning.isNotEmpty()) onEvent(Event.Reasoning(reasoning))
                    val content = str(delta, "content")
                    if (content.isNotEmpty()) onEvent(Event.Content(content))
                }
            }
        } catch (e: Exception) {
            if (!handle.cancelled) {
                onEvent(Event.Error("Connection problem: ${e.javaClass.simpleName}. Is the bridge running?"))
            }
        } finally {
            try { conn?.disconnect() } catch (ignored: Exception) {}
        }
    }

    private fun parse(s: String): JSONObject? = try { JSONObject(s) } catch (e: Exception) { null }

    /** optString turns JSON null into the text "null"; this does not. */
    private fun str(o: JSONObject, key: String): String =
        if (o.isNull(key)) "" else o.optString(key, "")

    private fun describe(code: Int, body: String): String {
        val msg = try {
            val o = JSONObject(body)
            str(o.optJSONObject("error") ?: o, "message")
        } catch (e: Exception) {
            ""
        }.ifEmpty { body.take(300) }
        val head = if (code == 401) "The API key was refused (HTTP 401)" else "HTTP $code"
        return if (msg.isEmpty()) head else "$head: $msg"
    }
}
