package dev.zunvein.pocketglm

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Z.AI account tokens (JWTs) the bridge may use instead of the guest
 * identity. Signed-in accounts can reach models that guests cannot.
 *
 * Each token is encrypted with an AES-256-GCM key that lives in the Android
 * Keystore (the key material never leaves secure hardware / the system
 * process) and only the ciphertext is written to app-private preferences.
 * If the Keystore is unusable the token is NOT stored: there is no plaintext
 * fallback.
 */
object AccountStore {

    data class Account(
        val id: String,
        val token: String,
        val expiresAtSec: Long?,
        val hint: String,
    ) {
        fun isExpired(nowSec: Long = System.currentTimeMillis() / 1000): Boolean =
            expiresAtSec != null && expiresAtSec <= nowSec

        /** Never the whole token: first 6 and last 4 characters only. */
        fun masked(): String = mask(token)
    }

    sealed class AddResult {
        data class Added(val account: Account) : AddResult()
        object Duplicate : AddResult()
        data class Invalid(val reason: String) : AddResult()
        data class StorageError(val reason: String) : AddResult()
    }

    private const val PREFS = "pocketglm_accounts"
    private const val LIST = "accounts"
    private const val KEY_ALIAS = "pocketglm_accounts_v1"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private val JWT_SHAPE = Regex("^eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$")

    fun mask(token: String): String =
        if (token.length <= 14) "****" else token.take(6) + "..." + token.takeLast(4)

    @Synchronized
    fun list(ctx: Context): List<Account> {
        val raw = prefs(ctx).getString(LIST, null) ?: return emptyList()
        val arr = try { JSONArray(raw) } catch (e: Exception) { return emptyList() }
        val out = ArrayList<Account>()
        for (i in 0 until arr.length()) {
            val blob = arr.optString(i)
            val token = try { decrypt(blob) } catch (e: Exception) { null } ?: continue
            parse(token)?.let { out.add(it) }
        }
        return out
    }

    @Synchronized
    fun add(ctx: Context, input: String): AddResult {
        val token = clean(input)
        if (!JWT_SHAPE.matches(token)) {
            return AddResult.Invalid("That does not look like a Z.AI token (it should start with eyJ and have three parts).")
        }
        val account = parse(token)
            ?: return AddResult.Invalid("The token payload could not be read.")
        if (account.isExpired()) return AddResult.Invalid("This token has already expired.")
        val current = list(ctx)
        if (current.any { it.id == account.id }) return AddResult.Duplicate
        return try {
            save(ctx, current + account)
            AddResult.Added(account)
        } catch (e: Exception) {
            AddResult.StorageError("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    @Synchronized
    fun remove(ctx: Context, id: String) {
        val remaining = list(ctx).filter { it.id != id }
        try { save(ctx, remaining) } catch (e: Exception) { /* nothing sensitive to leak */ }
    }

    /** Comma-joined, unexpired tokens in the form the bridge reads (ZAI_TOKENS). */
    fun tokensForBridge(ctx: Context): String =
        list(ctx).filter { !it.isExpired() }.joinToString(",") { it.token }

    // ── parsing ──────────────────────────────────────────────────────────

    /** Accepts a pasted token even when it still carries quotes or "Bearer ". */
    private fun clean(input: String): String =
        input.trim().removePrefix("Bearer ").removePrefix("bearer ").trim().trim('"', '\'').trim()

    private fun parse(token: String): Account? {
        val parts = token.split('.')
        if (parts.size != 3) return null
        return try {
            val json = JSONObject(String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8))
            val exp = if (json.has("exp")) json.optLong("exp") else null
            val hint = json.optString("email").takeIf { it.isNotEmpty() }?.let(::maskEmail)
                ?: json.optString("id").takeIf { it.isNotEmpty() }?.let { "id ..." + it.takeLast(4) }
                ?: "account"
            Account(fingerprint(token), token, exp, hint)
        } catch (e: Exception) {
            null
        }
    }

    private fun maskEmail(e: String): String {
        val at = e.indexOf('@')
        return if (at <= 1) "***" + e.substring(maxOf(at, 0)) else e.take(2) + "***" + e.substring(at)
    }

    private fun fingerprint(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).take(6)
            .joinToString("") { "%02x".format(it) }

    // ── storage ──────────────────────────────────────────────────────────

    private fun save(ctx: Context, accounts: List<Account>) {
        val arr = JSONArray()
        accounts.forEach { arr.put(encrypt(it.token)) }
        if (!prefs(ctx).edit().putString(LIST, arr.toString()).commit()) {
            throw IllegalStateException("preferences write failed")
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    /** iv(12) || ciphertext+tag, base64. */
    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        require(raw.size > 12 + 16) { "short blob" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        return String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    }
}
