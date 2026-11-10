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

    /** [perMin] is the request limit for this key (1..1000). */
    data class Friend(val id: String, val name: String, val key: String, val perMin: Int = DEFAULT_PER_MIN)

    const val DEFAULT_PER_MIN = 60
    const val MIN_PER_MIN = 1
    const val MAX_PER_MIN = 1000

    fun list(ctx: Context): List<Friend> {
        val raw = prefs(ctx).getString(LIST, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Friend(o.getString("id"), o.getString("name"), o.getString("key"), o.optInt("perMin", DEFAULT_PER_MIN))
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
        val friend = Friend(System.currentTimeMillis().toString(), label, sb.toString(), DEFAULT_PER_MIN)
        save(ctx, list(ctx) + friend)
        return friend
    }

    fun setLimit(ctx: Context, id: String, perMin: Int): Boolean {
        if (perMin !in MIN_PER_MIN..MAX_PER_MIN) return false
        save(ctx, list(ctx).map { if (it.id == id) it.copy(perMin = perMin) else it })
        return true
    }

    fun remove(ctx: Context, id: String) {
        save(ctx, list(ctx).filter { it.id != id })
    }

    /** The owner key first, then every friend key, comma-separated (AUTH_TOKEN). */
    fun authTokenValue(ctx: Context): String =
        (listOf(ApiKeyStore.get(ctx)) + list(ctx).map { it.key }).joinToString(",")

    /** Per-key limits as FRIEND_RATE_LIMITS ("key:perMin,key:perMin"). */
    fun limitsValue(ctx: Context): String =
        list(ctx).joinToString(",") { "${it.key}:${it.perMin}" }

    private fun save(ctx: Context, friends: List<Friend>) {
        val arr = JSONArray()
        friends.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("key", it.key).put("perMin", it.perMin))
        }
        prefs(ctx).edit().putString(LIST, arr.toString()).commit()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
