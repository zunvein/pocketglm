package dev.zunvein.pocketglm

import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * A small chat box for testing the bridge end to end: pick a model, send a
 * message, watch the answer stream in. Every reply costs one device token.
 */
class ChatPage(
    private val act: AppCompatActivity,
    private val scope: CoroutineScope,
    private val bridgeState: () -> BridgeClient.Health,
) {
    private class Bubble(val reasoning: TextView, val content: TextView, val error: TextView)

    private var history = JSONArray()
    private var models: List<String> = ChatClient.FALLBACK_MODELS
    private var modelsAt = 0L
    private var job: Job? = null
    private var handle: ChatClient.Handle? = null

    private val bubbles = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(act)
    private val emptyState = Ui.text(
        act,
        "Send a message to test the bridge from end to end. Each reply uses one device token.",
        13f, R.color.textDim,
    )
    private val modelButton = Ui.button(act, Prefs.chatModel(act), Ui.Kind.TONAL)
    private val clearButton = Ui.button(act, "Clear", Ui.Kind.TEXT)
    private val input = Ui.input(act, "Type a message")
    private val sendButton = Ui.button(act, "Send", Ui.Kind.PRIMARY)

    val view: View = build()

    private fun busy() = job?.isActive == true

    private fun build(): View {
        val pad = Ui.dp(act, 16)
        val gap = Ui.dp(act, 8)
        bubbles.addView(emptyState)
        scroll.addView(bubbles)
        showModel()
        modelButton.setOnClickListener { chooseModel() }
        clearButton.setOnClickListener { clear() }
        sendButton.setOnClickListener { if (busy()) handle?.cancel() else send() }
        input.maxLines = 4

        val header = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(modelButton, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            addView(clearButton, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { marginStart = gap })
        }
        val inputRow = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(input, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            addView(sendButton, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { marginStart = gap })
        }
        return LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(header)
            addView(scroll, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f).apply {
                topMargin = Ui.dp(act, 12)
                bottomMargin = Ui.dp(act, 12)
            })
            addView(inputRow)
        }
    }

    private fun showModel() {
        modelButton.text = Prefs.chatModel(act)
        modelButton.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_arrow_drop_down, 0)
        modelButton.compoundDrawableTintList = ColorStateList.valueOf(Ui.color(act, R.color.text))
    }

    /** Called whenever the tab becomes visible: refresh the model list now and then. */
    fun onShown() {
        val h = bridgeState()
        val now = System.currentTimeMillis()
        if (!h.up || h.warming || now - modelsAt < 60_000L) return
        modelsAt = now
        val key = ApiKeyStore.get(act)
        scope.launch {
            val found = withContext(Dispatchers.IO) { ChatClient.models(key) }
            if (found.isNotEmpty()) models = found else modelsAt = 0L
        }
    }

    fun shutdown() {
        handle?.cancel()
    }

    private fun chooseModel() {
        val current = Prefs.chatModel(act)
        val list = if (current in models) models else listOf(current) + models
        AlertDialog.Builder(act)
            .setTitle("Model")
            .setSingleChoiceItems(list.toTypedArray(), list.indexOf(current)) { dialog, which ->
                Prefs.setChatModel(act, list[which])
                showModel()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clear() {
        if (busy()) return
        history = JSONArray()
        bubbles.removeAllViews()
        bubbles.addView(emptyState)
    }

    private fun send() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        val h = bridgeState()
        if (!h.up) {
            Toast.makeText(act, "Start the bridge on the Home tab first.", Toast.LENGTH_SHORT).show()
            return
        }

        input.setText("")
        if (emptyState.parent != null) bubbles.removeView(emptyState)
        addUser(text)
        history.put(JSONObject().put("role", "user").put("content", text))
        val bubble = addAssistant()
        bubble.content.setTextColor(Ui.color(act, R.color.textDim))
        bubble.content.text = "Waiting for the bridge. The captcha step takes a few seconds."
        pinToEnd()
        setBusy(true)

        val model = Prefs.chatModel(act)
        val key = ApiKeyStore.get(act)
        val request = JSONArray(history.toString())
        val h2 = ChatClient.Handle()
        handle = h2

        job = scope.launch {
            val reply = StringBuilder()
            val thinking = StringBuilder()
            var error: String? = null
            withContext(Dispatchers.IO) {
                ChatClient.stream(key, model, request, h2) { ev ->
                    when (ev) {
                        is ChatClient.Event.Content -> {
                            reply.append(ev.text)
                            val s = reply.toString()
                            act.runOnUiThread { showContent(bubble, s) }
                        }
                        is ChatClient.Event.Reasoning -> {
                            thinking.append(ev.text)
                            val s = thinking.toString()
                            act.runOnUiThread { showReasoning(bubble, s) }
                        }
                        is ChatClient.Event.Error -> error = ev.text
                    }
                }
            }
            val answer = reply.toString()
            val err = error
            if (answer.isNotEmpty()) {
                history.put(JSONObject().put("role", "assistant").put("content", answer))
            } else {
                // Drop the unanswered question so the next try starts clean.
                history.remove(history.length() - 1)
            }
            when {
                err != null -> showError(bubble, err)
                answer.isEmpty() && h2.cancelled -> showContent(bubble, "Stopped.")
                answer.isEmpty() -> showError(bubble, "The model returned an empty answer.")
            }
            setBusy(false)
        }
    }

    private fun setBusy(on: Boolean) {
        sendButton.text = if (on) "Stop" else "Send"
        sendButton.style(if (on) Ui.Kind.DANGER else Ui.Kind.PRIMARY)
        clearButton.isEnabled = !on
    }

    private fun addUser(text: String) {
        val tv = Ui.text(act, text, 15f).apply {
            setTextIsSelectable(true)
            val p = Ui.dp(act, 12)
            setPadding(p, p, p, p)
            background = Ui.shape(
                ColorUtils.setAlphaComponent(Ui.color(act, R.color.accent), 0x26),
                Ui.dp(act, 14).toFloat(),
            )
        }
        bubbles.addView(tv, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply {
            gravity = Gravity.END
            topMargin = Ui.dp(act, 8)
            marginStart = Ui.dp(act, 40)
        })
    }

    private fun addAssistant(): Bubble {
        val reasoning = Ui.text(act, "", 12f, R.color.textDim).apply {
            setTextIsSelectable(true)
            visibility = View.GONE
        }
        val content = Ui.text(act, "", 15f).apply { setTextIsSelectable(true) }
        val error = Ui.text(act, "", 13f, R.color.bad).apply { visibility = View.GONE }
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(act, 12)
            setPadding(p, p, p, p)
            background = Ui.shape(
                Ui.color(act, R.color.surface), Ui.dp(act, 14).toFloat(),
                Ui.color(act, R.color.stroke), Ui.dp(act, 1),
            )
            addView(reasoning)
            addView(content)
            addView(error)
        }
        bubbles.addView(box, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply {
            topMargin = Ui.dp(act, 8)
            marginEnd = Ui.dp(act, 24)
        })
        return Bubble(reasoning, content, error)
    }

    private fun showContent(b: Bubble, text: String) {
        val atEnd = !scroll.canScrollVertically(1)
        b.content.setTextColor(Ui.color(act, R.color.text))
        b.content.text = text
        if (atEnd) pinToEnd()
    }

    private fun showReasoning(b: Bubble, text: String) {
        val atEnd = !scroll.canScrollVertically(1)
        b.reasoning.visibility = View.VISIBLE
        b.reasoning.text = text
        if (b.content.text.toString().startsWith("Waiting for the bridge")) b.content.text = ""
        if (atEnd) pinToEnd()
    }

    private fun showError(b: Bubble, text: String) {
        if (b.content.text.toString().startsWith("Waiting for the bridge")) b.content.text = ""
        b.error.visibility = View.VISIBLE
        b.error.text = Redact.text(act, text)
        pinToEnd()
    }

    private fun pinToEnd() {
        scroll.post { scroll.scrollTo(0, bubbles.height) }
    }
}
