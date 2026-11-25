package dev.zunvein.pocketglm

import android.app.AlertDialog
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * A chat box for testing the bridge end to end: it calls the same /v1 endpoint
 * a client would, and the answer streams into a bubble as it arrives. Every
 * reply spends one device token.
 */
class ChatScreen(act: MainActivity) : Screen(act) {

    private class Bubble(val head: TextView, val reasoning: TextView, val content: TextView, val error: TextView)

    private var history = JSONArray()
    private var models: List<String> = ChatClient.FALLBACK_MODELS
    private var modelsAt = 0L
    private var handle: ChatClient.Handle? = null
    private var busy = false

    private val bubbles = Ui.vertical(ctx)
    private val scroll = ScrollView(ctx).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
    }
    private val empty = Ui.muted(ctx,
        "Send a message to test the bridge end to end. Each reply spends one device token, so the pool shrinks as you chat.")
        .apply { setPadding(0, Ui.dip(ctx, 4), 0, 0) }
    private val modelBtn = Ui.small(ctx, Prefs.chatModel(ctx)) { chooseModel() }
    private val clearBtn = Ui.small(ctx, "Clear") { clear() }
    private val input = Ui.input(ctx, "Message").apply {
        maxLines = 4
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
    }
    private val sendBtn = Ui.primary(ctx, "Send") { if (busy) cancel() else send() }

    override fun build(): View {
        bubbles.addView(empty)
        scroll.addView(bubbles)

        val head = Ui.horizontal(ctx)
        head.addView(TextView(ctx).apply {
            text = "Model"
            textSize = 12.5f
            setTextColor(Ui.resColor(ctx, R.color.muted))
            setPadding(0, 0, Ui.dip(ctx, 8), 0)
        })
        head.addView(modelBtn, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        head.addView(clearBtn, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { leftMargin = Ui.dip(ctx, 8) })

        val row = Ui.horizontal(ctx, false).apply { gravity = Gravity.BOTTOM }
        row.addView(input, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        row.addView(sendBtn, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { leftMargin = Ui.dip(ctx, 8) })

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dip(ctx, 16)
            setPadding(p, Ui.dip(ctx, 12), p, p)
            addView(head)
            addView(scroll, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f).apply {
                topMargin = Ui.dip(ctx, 10)
                bottomMargin = Ui.dip(ctx, 10)
            })
            addView(row)
        }
    }

    override fun onShow() = loadModels()

    private fun loadModels() {
        val now = System.currentTimeMillis()
        if (now - modelsAt < 60_000L) return
        modelsAt = now
        bg {
            val found = ChatClient.models(key())
            post {
                if (found.isNotEmpty()) models = found else modelsAt = 0L
            }
        }
    }

    private fun chooseModel() {
        val current = Prefs.chatModel(ctx)
        val list = if (current in models) models else listOf(current) + models
        AlertDialog.Builder(ctx)
            .setTitle("Model")
            .setSingleChoiceItems(list.toTypedArray(), list.indexOf(current)) { dialog, which ->
                Prefs.setChatModel(ctx, list[which])
                modelBtn.text = list[which]
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clear() {
        if (busy) return
        history = JSONArray()
        bubbles.removeAllViews()
        bubbles.addView(empty)
    }

    private fun cancel() {
        handle?.cancel()
        toast("Stopping")
    }

    private fun send() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        sendBtn.isEnabled = false
        bg {
            val up = BridgeClient.health().up
            post {
                sendBtn.isEnabled = true
                if (!up) toast("Start the bridge on Home first.") else deliver(text)
            }
        }
    }

    private fun deliver(text: String) {
        input.setText("")
        if (empty.parent != null) bubbles.removeView(empty)
        addUser(text)
        history.put(JSONObject().put("role", "user").put("content", text))
        val bubble = addAssistant()
        bubble.content.setTextColor(Ui.resColor(ctx, R.color.muted))
        bubble.content.text = "Waiting for the bridge. The captcha step takes a few seconds."
        pinToEnd()
        setBusy(true)

        val model = Prefs.chatModel(ctx)
        val k = key()
        val request = JSONArray(history.toString())
        val h = ChatClient.Handle()
        handle = h
        bg {
            val reply = StringBuilder()
            val thinking = StringBuilder()
            var error: String? = null
            try {
                ChatClient.stream(k, model, request, h) { ev ->
                    when (ev) {
                        is ChatClient.Event.Content -> {
                            reply.append(ev.text)
                            val s = reply.toString()
                            post { showContent(bubble, s) }
                        }
                        is ChatClient.Event.Reasoning -> {
                            thinking.append(ev.text)
                            val s = thinking.toString()
                            post { showReasoning(bubble, s) }
                        }
                        is ChatClient.Event.Error -> error = ev.text
                    }
                }
            } catch (e: Exception) {
                error = "${e.javaClass.simpleName}: ${e.message}"
            }
            val answer = reply.toString()
            val err = error
            post {
                if (answer.isNotEmpty()) {
                    history.put(JSONObject().put("role", "assistant").put("content", answer))
                } else {
                    // Drop the unanswered question so the next try starts clean.
                    if (history.length() > 0) history.remove(history.length() - 1)
                }
                when {
                    err != null -> showError(bubble, err)
                    answer.isEmpty() && h.cancelled -> showContent(bubble, "Stopped.")
                    answer.isEmpty() -> showError(bubble, "The model returned an empty answer.")
                }
                setBusy(false)
            }
        }
    }

    private fun setBusy(on: Boolean) {
        busy = on
        sendBtn.text = if (on) "Stop" else "Send"
        clearBtn.isEnabled = !on
    }

    private fun addUser(text: String) {
        val tv = TextView(ctx).apply {
            this.text = text
            textSize = 15f
            setTextColor(Ui.resColor(ctx, R.color.fg))
            setTextIsSelectable(true)
            val p = Ui.dip(ctx, 12)
            setPadding(p, p, p, p)
            background = Ui.bubbleBg(ctx, user = true)
        }
        bubbles.addView(tv, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply {
            gravity = Gravity.END
            topMargin = Ui.dip(ctx, 8)
            marginStart = Ui.dip(ctx, 40)
        })
    }

    private fun addAssistant(): Bubble {
        val head = TextView(ctx).apply {
            text = "\u25B8 Thinking (tap to show)"
            textSize = 12f
            setTextColor(Ui.resColor(ctx, R.color.muted))
            visibility = View.GONE
        }
        val reasoning = TextView(ctx).apply {
            textSize = 12f
            setTextIsSelectable(true)
            setTextColor(Ui.resColor(ctx, R.color.muted))
            background = Ui.thinkBg(ctx)
            val p = Ui.dip(ctx, 10)
            setPadding(p, p, p, p)
            visibility = View.GONE
        }
        head.setOnClickListener {
            val open = reasoning.visibility != View.VISIBLE
            reasoning.visibility = if (open) View.VISIBLE else View.GONE
            head.text = if (open) "\u25BE Thinking" else "\u25B8 Thinking (tap to show)"
        }
        val content = TextView(ctx).apply {
            textSize = 15f
            setTextIsSelectable(true)
            setTextColor(Ui.resColor(ctx, R.color.fg))
            setLineSpacing(Ui.dip(ctx, 2).toFloat(), 1f)
        }
        val error = TextView(ctx).apply {
            textSize = 13f
            setTextColor(Ui.resColor(ctx, R.color.err))
            visibility = View.GONE
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dip(ctx, 12)
            setPadding(p, p, p, p)
            background = Ui.bubbleBg(ctx, user = false)
            addView(head)
            addView(reasoning, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { bottomMargin = Ui.dip(ctx, 8) })
            addView(content)
            addView(error)
        }
        bubbles.addView(box, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply {
            topMargin = Ui.dip(ctx, 8)
            marginEnd = Ui.dip(ctx, 24)
        })
        return Bubble(head, reasoning, content, error)
    }

    private fun showContent(b: Bubble, text: String) {
        val atEnd = !scroll.canScrollVertically(1)
        b.content.setTextColor(Ui.resColor(ctx, R.color.fg))
        b.content.text = text
        if (atEnd) pinToEnd()
    }

    private fun showReasoning(b: Bubble, text: String) {
        val atEnd = !scroll.canScrollVertically(1)
        b.head.visibility = View.VISIBLE
        b.reasoning.text = text
        if (b.content.text.toString().startsWith("Waiting for the bridge")) b.content.text = ""
        if (atEnd) pinToEnd()
    }

    private fun showError(b: Bubble, text: String) {
        if (b.content.text.toString().startsWith("Waiting for the bridge")) b.content.text = ""
        b.error.visibility = View.VISIBLE
        b.error.text = Redact.text(ctx, text)
        pinToEnd()
    }

    private fun pinToEnd() {
        scroll.post { scroll.scrollTo(0, bubbles.height) }
    }
}
