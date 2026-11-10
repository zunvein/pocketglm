package dev.zunvein.pocketglm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * API keys handed to friends, one per person. The bridge lets them use the
 * chat API only and rate limits each key. The owner key stays in [ApiKeyStore].
 *
 * They travel to the bridge as extra entries of AUTH_TOKEN (comma-separated,
 * after the owner key), so every change needs a bridge restart.
 */
object FriendKeys {
    private const val PREFS = "pocketglm"
    private const val LIST = "friend_keys"
    private const val ALPHABET =
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    data class Friend(val id: String, val name: String, val key: String)

    fun list(ctx: Context): List<Friend> {
        val raw = prefs(ctx).getString(LIST, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Friend(o.getString("id"), o.getString("name"), o.getString("key"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(ctx: Context, name: String): Friend {
        val label = name.trim().ifEmpty { "Friend" }.take(40)
        val rnd = SecureRandom()
        val sb = StringBuilder("pgl-friend-")
        repeat(24) { sb.append(ALPHABET[rnd.nextInt(ALPHABET.length)]) }
        val friend = Friend(System.currentTimeMillis().toString(), label, sb.toString())
        save(ctx, list(ctx) + friend)
        return friend
    }

    fun remove(ctx: Context, id: String) {
        save(ctx, list(ctx).filter { it.id != id })
    }

    /** The owner key first, then every friend key, comma-separated (AUTH_TOKEN). */
    fun authTokenValue(ctx: Context): String =
        (listOf(ApiKeyStore.get(ctx)) + list(ctx).map { it.key }).joinToString(",")

    private fun save(ctx: Context, friends: List<Friend>) {
        val arr = JSONArray()
        friends.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("key", it.key))
        }
        prefs(ctx).edit().putString(LIST, arr.toString()).commit()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
