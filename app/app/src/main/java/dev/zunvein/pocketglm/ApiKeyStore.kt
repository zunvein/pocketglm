package dev.zunvein.pocketglm

import android.content.Context
import java.security.SecureRandom

/**
 * The API key clients must present to the local bridge (Authorization:
 * Bearer <key>, or x-api-key for Anthropic-style clients).
 *
 * Stored in app-private SharedPreferences and handed to the bridge process
 * as AUTH_TOKEN at every start. A random key is generated on first use, so
 * the bridge never runs with its built-in default token.
 */
object ApiKeyStore {
    private const val PREFS = "pocketglm"
    private const val KEY = "api_key"
    private const val ALPHABET =
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    fun get(ctx: Context): String {
        val current = prefs(ctx).getString(KEY, null)
        if (!current.isNullOrEmpty()) return current
        return regenerate(ctx)
    }

    fun regenerate(ctx: Context): String {
        val key = generate()
        save(ctx, key)
        return key
    }

    /** Returns false (and stores nothing) when [key] is not acceptable. */
    fun set(ctx: Context, key: String): Boolean {
        val trimmed = key.trim()
        if (!isValid(trimmed)) return false
        save(ctx, trimmed)
        return true
    }

    // The bridge splits AUTH_TOKEN on commas and trims whitespace, so neither
    // may appear inside a key.
    fun isValid(key: String): Boolean =
        key.length in 8..128 && key.none { it.isWhitespace() || it == ',' }

    private fun save(ctx: Context, key: String) {
        prefs(ctx).edit().putString(KEY, key).commit()
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun generate(): String {
        val rnd = SecureRandom()
        val sb = StringBuilder("sk-pgl-")
        repeat(32) { sb.append(ALPHABET[rnd.nextInt(ALPHABET.length)]) }
        return sb.toString()
    }
}
