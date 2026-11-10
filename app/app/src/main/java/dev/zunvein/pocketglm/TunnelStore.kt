package dev.zunvein.pocketglm

import android.content.Context

/**
 * The ngrok credentials behind the "Public link" feature.
 *
 * The authtoken is a secret: it is encrypted with a Keystore key (see
 * [SecretBox]) and handed to the bridge process only through its environment
 * at spawn time. It is never logged and never shown back in the UI. The
 * static domain is not secret and is stored as plain text.
 */
object TunnelStore {
    private const val ALIAS = "pocketglm_tunnel_v1"
    private const val PREFS = "pocketglm_tunnel"
    private const val K_TOKEN = "authtoken"
    private const val K_DOMAIN = "domain"

    // ngrok authtokens are letters, digits and underscores.
    private val tokenShape = Regex("[A-Za-z0-9_]{20,200}")
    private val hostShape = Regex("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The decrypted authtoken, or null when none is saved (or the key is gone). */
    fun authtoken(ctx: Context): String? {
        val blob = prefs(ctx).getString(K_TOKEN, null) ?: return null
        return try {
            SecretBox.decrypt(ALIAS, blob)
        } catch (e: Exception) {
            null
        }
    }

    /** Cheap check (no decryption) for the UI: is a token saved? */
    fun isConfigured(ctx: Context): Boolean = prefs(ctx).contains(K_TOKEN)

    /** Returns false (and stores nothing) when [token] does not look like an authtoken. */
    fun setAuthtoken(ctx: Context, token: String): Boolean {
        val t = token.trim()
        if (!tokenShape.matches(t)) return false
        return prefs(ctx).edit().putString(K_TOKEN, SecretBox.encrypt(ALIAS, t)).commit()
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(K_TOKEN).remove(K_DOMAIN).apply()
    }

    /** The saved domain as the user typed it (no scheme), or "". */
    fun domain(ctx: Context): String = prefs(ctx).getString(K_DOMAIN, "") ?: ""

    /**
     * Accepts "name.ngrok-free.app", "https://name.ngrok-free.app/" or empty
     * (empty = a random link each run). Returns false for anything else.
     */
    fun setDomain(ctx: Context, raw: String): Boolean {
        var d = raw.trim().lowercase()
        d = d.removePrefix("https://").removePrefix("http://").trimEnd('/')
        if (d.isNotEmpty() && !hostShape.matches(d)) return false
        return prefs(ctx).edit().putString(K_DOMAIN, d).commit()
    }

    /** Value for the bridge's NGROK_URL variable ("" when no static domain is set). */
    fun bridgeUrl(ctx: Context): String {
        val d = domain(ctx)
        return if (d.isEmpty()) "" else "https://$d"
    }
}
