package dev.zunvein.pocketglm

import android.content.Context

/** Small settings that both the UI and the bridge service read. */
object Prefs {
    private const val FILE = "pocketglm"

    private fun p(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun autoTopUp(ctx: Context) = p(ctx).getBoolean("auto_topup", true)
    fun setAutoTopUp(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("auto_topup", on).apply()

    /** Hidden-browser farming from the service. Off by default: experimental. */
    fun bgFarm(ctx: Context) = p(ctx).getBoolean("bg_farm", false)
    fun setBgFarm(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("bg_farm", on).apply()

    /** CPU wake lock while the bridge runs, so LAN clients reach it with the screen off. */
    fun keepAwake(ctx: Context) = p(ctx).getBoolean("keep_awake", true)
    fun setKeepAwake(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("keep_awake", on).apply()

    /** True from "start" until "stop", so the system can bring the bridge back after a kill. */
    fun bridgeWanted(ctx: Context) = p(ctx).getBoolean("bridge_wanted", false)
    fun setBridgeWanted(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("bridge_wanted", on).apply()

    fun bgStatus(ctx: Context): String = p(ctx).getString("bg_status", "") ?: ""
    fun setBgStatus(ctx: Context, s: String) = p(ctx).edit().putString("bg_status", s).apply()

    /** The first-run choice (account or guest) has been made. */
    fun setupDone(ctx: Context) = p(ctx).getBoolean("setup_done", false)
    fun setSetupDone(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("setup_done", on).apply()

    /** Model used by the in-app chat test. */
    fun chatModel(ctx: Context): String = p(ctx).getString("chat_model", null) ?: "glm-5.2"
    fun setChatModel(ctx: Context, m: String) = p(ctx).edit().putString("chat_model", m).apply()
}
