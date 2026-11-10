package dev.zunvein.pocketglm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.io.File
import java.io.FileOutputStream

/**
 * Foreground service that owns the Go bridge process.
 *
 * Lifecycle:
 *  - ACTION_START: extracts the bundled android/arm64 bridge binary from APK
 *    assets (once), creates the working dir (filesDir/bridge), makes sure the
 *    token pool database exists (boot guard), then spawns the process and
 *    promotes itself to a foreground service so Android keeps it alive while
 *    the user is using the bridge.
 *  - ACTION_STOP: destroys the bridge process and stops itself.
 *
 * The service is intentionally dumb: all logic (breaker, standby pool, refills)
 * lives inside the Go binary. Nothing here runs while the app is closed.
 */
class BridgeService : Service() {

    companion object {
        const val ACTION_START = "dev.zunvein.pocketglm.START"
        const val ACTION_STOP = "dev.zunvein.pocketglm.STOP"
        /** Same as START, but any running bridge is replaced (new API key). */
        const val ACTION_RESTART = "dev.zunvein.pocketglm.RESTART"
        const val CHANNEL_ID = "pocketglm_bridge"
        const val BINARY_NAME = "zai-api-android-arm64"
        const val NOTIFICATION_ID = 1
        const val ATTENTION_CHANNEL_ID = "pocketglm_attention"
        const val ATTENTION_NOTIFICATION_ID = 2

        /** How often the service looks at the pool (only while the bridge runs). */
        private const val TICK_MS = 60_000L
        /** The background farm tops up at or below this many tokens. */
        private const val BG_LOW = 10
        private const val WAKE_LOCK_MS = 6L * 60 * 60 * 1000
    }

    private var bridgeProcess: Process? = null
    private val main = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var bgFarmer: Farmer? = null
    private var ticking = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, ACTION_RESTART -> startBridge()
            ACTION_STOP -> stopBridge()
            // null intent = the system restarted us after killing the process.
            null -> if (Prefs.bridgeWanted(this)) {
                LogFile.append(this, "[app] service restarted by the system, starting the bridge again")
                startBridge()
            } else {
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startBridge() {
        Prefs.setBridgeWanted(this, true)
        try {
            promoteToForeground()
        } catch (e: Exception) {
            // Android refuses to start a foreground service from the background
            // in some restart cases. Nothing to run without it.
            LogFile.append(this, "[app] cannot enter foreground: ${e.javaClass.simpleName}: ${e.message}")
            stopSelf()
            return
        }
        acquireLocks()

        Thread {
            val workDir = File(filesDir, "bridge").apply { mkdirs() }
            val logFile = File(workDir, "bridge.log")

            // Replace a bridge that is already running (restart / double
            // START): wait for it to exit so the port is free again.
            bridgeProcess?.let { old ->
                old.destroy()
                try {
                    old.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                } catch (ignored: InterruptedException) {
                }
                bridgeProcess = null
            }

            // Boot guard: the bridge refuses to start without a token DB.
            try {
                ensureTokenDb(workDir)
            } catch (e: Exception) {
                captureFailure(e)
                return@Thread
            }

            // Remove copies made by older builds: on Android 10+ a file in
            // app data that has EVER been opened for writing can never be
            // executed afterwards, no matter how the permission bits are
            // shuffled. Copying an executable into filesDir simply cannot
            // work on this targetSdk — the only reliably executable location
            // is nativeLibraryDir, where the INSTALLER (not the app) put
            // libzai.so with the exec bit already set.
            File(workDir, BINARY_NAME).delete()

            // Strategy 1 (canonical): exec straight from nativeLibraryDir.
            val nativeBin = File(applicationInfo.nativeLibraryDir, "libzai.so")
            if (trySpawn(nativeBin, workDir, logFile)) return@Thread

            // Strategy 2 (fallback, expected dead on Android 10+): fresh copy
            // in app data with permissions in the "correct" order. Kept only
            // so the log can prove exactly WHY it fails if it does.
            try {
                val copy = File(workDir, BINARY_NAME)
                extractBinary(copy)
                if (trySpawn(copy, workDir, logFile)) return@Thread
            } catch (e: Exception) {
                appendLog(workDir, "[app] copy fallback failed: ${e.javaClass.simpleName}: ${e.message}")
            }

            captureFailure(java.io.IOException("all exec strategies failed"))
        }.start()
    }

    /** Try to spawn the bridge from [bin]; true when the process started. */
    private fun trySpawn(bin: File, workDir: File, logFile: File): Boolean {
        return try {
            appendLog(workDir, "[app] exec try: ${bin.absolutePath} (exists=${bin.exists()}, exec=${bin.canExecute()}, ${bin.length()} bytes)")
            bridgeProcess = ProcessBuilder(bin.absolutePath)
                .directory(workDir)
                .redirectErrorStream(true)
                .redirectOutput(logFile)
                .apply {
                    environment()["ZAI_DNS"] = deviceDnsServers()
                    // Clients must present this key; generated on first use.
                    environment()["AUTH_TOKEN"] = FriendKeys.authTokenValue(this@BridgeService)
                    // Signed-in Z.AI accounts (decrypted only here, in memory).
                    // Without any, the bridge keeps using its guest identity.
                    val accounts = AccountStore.tokensForBridge(this@BridgeService)
                    if (accounts.isNotEmpty()) environment()["ZAI_TOKENS"] = accounts
                    // Blocked-network workaround (see Bypass.kt): off unless the user turned it on.
                    if (Prefs.bypass(this@BridgeService)) {
                        environment()["ZAI_FRAGMENT"] = "1"
                        environment()["ZAI_FRAG_PROXY_ADDR"] = "${Bypass.PROXY_HOST}:${Bypass.PROXY_PORT}"
                    }
                    // Public link (ngrok). The authtoken stays in memory here and is
                    // never logged; without it the bridge keeps the tunnel inert.
                    TunnelStore.authtoken(this@BridgeService)?.let { tok ->
                        environment()["NGROK_AUTHTOKEN"] = tok
                        environment()["NGROK_URL"] = TunnelStore.bridgeUrl(this@BridgeService)
                        environment()["NGROK_AUTOSTART"] = if (Prefs.publicLink(this@BridgeService)) "1" else "0"
                    }
                }
                .start()
            appendLog(workDir, "[app] spawned OK from ${bin.parent} (accounts: ${AccountStore.list(this).count { !it.isExpired() }})")
            main.post { startTicks() }
            true
        } catch (e: Exception) {
            appendLog(workDir, "[app] exec failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun appendLog(workDir: File, line: String) {
        try {
            File(workDir, "bridge.log").appendText(line + "\n")
        } catch (ignored: Exception) {
        }
    }

    /**
     * The Go bridge is a pure-Go (netgo) binary: it does NOT use Android's
     * system resolver and there is no /etc/resolv.conf in an app process,
     * so every hostname lookup would die on [::1]:53. We read the device's
     * current DNS servers from ConnectivityManager (requires
     * ACCESS_NETWORK_STATE) and pass them to the bridge via ZAI_DNS; the
     * bridge installs a resolver that uses them. If the device list is
     * unavailable for any reason we fall back to public resolvers so the
     * bridge is never left with a dead resolver.
     */
    private fun deviceDnsServers(): String {
        val fallback = "1.1.1.1,8.8.8.8"
        return try {
            val cm = getSystemService(android.net.ConnectivityManager::class.java)
            val link = cm.getLinkProperties(cm.activeNetwork)
                ?: return fallback.also { appendLog(File(filesDir, "bridge"), "[app] ZAI_DNS=$it (no active network, public fallback)") }
            val servers = link.dnsServers.joinToString(",") { it.hostAddress ?: "" }.trim(',')
            if (servers.isEmpty()) {
                appendLog(File(filesDir, "bridge"), "[app] ZAI_DNS=$fallback (empty device DNS list, public fallback)")
                fallback
            } else {
                appendLog(File(filesDir, "bridge"), "[app] ZAI_DNS=$servers")
                servers
            }
        } catch (e: Exception) {
            appendLog(File(filesDir, "bridge"), "[app] ZAI_DNS=$fallback (${e.javaClass.simpleName}, public fallback)")
            fallback
        }
    }

    /** Write the failure into filesDir/bridge/bridge.log so the UI can show it. */
    private fun captureFailure(e: Exception) {
        try {
            val logFile = File(File(filesDir, "bridge"), "bridge.log")
            logFile.appendText("\n[app] ${e.javaClass.simpleName}: ${e.message}\n")
            val natDir = File(applicationInfo.nativeLibraryDir)
            logFile.appendText("[app] nativeLibraryDir=${natDir.absolutePath} (exists=${natDir.exists()})\n")
            val files = natDir.listFiles()
            if (files.isNullOrEmpty()) {
                logFile.appendText("[app]   (empty — libs not extracted from APK)\n")
            } else {
                files.forEach { logFile.appendText("[app]   ${it.name} (${it.length()} bytes)\n") }
            }
        } catch (ignored: Exception) {
        }
    }

    private fun stopBridge() {
        Prefs.setBridgeWanted(this, false)
        shutDown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun shutDown() {
        main.post {
            ticking = false
            main.removeCallbacksAndMessages(null)
            bgFarmer?.destroy()
            bgFarmer = null
        }
        bridgeProcess?.destroy()
        bridgeProcess = null
        releaseLocks()
    }

    // ── keep running with the screen off ─────────────────────────────────

    /**
     * A partial wake lock keeps the CPU running, and a Wi-Fi lock keeps the
     * radio from sleeping, so other devices on the LAN can still reach the
     * bridge when the screen is off. Both are released on stop.
     */
    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (!Prefs.keepAwake(this)) return
        try {
            if (wakeLock?.isHeld != true) {
                val pm = getSystemService(PowerManager::class.java)
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pocketglm:bridge").apply {
                    setReferenceCounted(false)
                    // Safety net: never hold the CPU forever if the service is leaked.
                    acquire(WAKE_LOCK_MS)
                }
            }
            if (wifiLock?.isHeld != true) {
                val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifiLock = wm.createWifiLock(mode, "pocketglm:bridge").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (e: Exception) {
            LogFile.append(this, "[app] could not take wake/wifi lock: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (ignored: Exception) {}
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (ignored: Exception) {}
        wakeLock = null
        wifiLock = null
    }

    // ── background farm (experimental) ───────────────────────────────────

    private val tick = object : Runnable {
        override fun run() {
            if (!ticking) return
            // Renew the wake lock before its safety timeout runs out.
            acquireLocks()
            if (Prefs.bgFarm(this@BridgeService)) {
                Thread {
                    val h = BridgeClient.health()
                    main.post { onHealth(h) }
                }.start()
            }
            main.postDelayed(this, TICK_MS)
        }
    }

    private fun startTicks() {
        if (ticking) return
        ticking = true
        main.postDelayed(tick, 20_000L)
    }

    private fun onHealth(h: BridgeClient.Health) {
        if (!ticking || !Prefs.bgFarm(this)) return
        if (!h.up || h.warming || h.tokens < 0 || h.tokens > BG_LOW) return
        if (FarmState.running || bgFarmer != null || !FarmState.cooldownOk()) return
        runBackgroundFarm()
    }

    private fun runBackgroundFarm() {
        LogFile.append(this, "[farm-bg] pool is low, starting a hidden farm run")
        val f = try {
            Farmer(
                this, Farmer.DEFAULT_MINT,
                log = { m -> LogFile.append(this, "[farm-bg] $m") },
                onDone = { ok, summary -> onBackgroundDone(ok, summary) },
            )
        } catch (e: Exception) {
            // No WebView available in this context.
            LogFile.append(this, "[farm-bg] cannot create a WebView here: ${e.javaClass.simpleName}: ${e.message}")
            FarmState.lastOk = false
            FarmState.lastFinishedAt = System.currentTimeMillis()
            Prefs.setBgStatus(this, "Could not start: no WebView in the service")
            return
        }
        bgFarmer = f
        try {
            f.layoutOffscreen()
            f.start()
        } catch (e: Exception) {
            LogFile.append(this, "[farm-bg] start failed: ${e.javaClass.simpleName}: ${e.message}")
            onBackgroundDone(false, "start failed")
        }
    }

    private fun onBackgroundDone(ok: Boolean, summary: String) {
        bgFarmer?.let { f -> main.post { f.destroy() } }
        bgFarmer = null
        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        Prefs.setBgStatus(this, (if (ok) "OK " else "Failed ") + time + ": " + summary)
        if (ok) {
            FarmState.bgFailStreak = 0
        } else {
            FarmState.bgFailStreak++
            if (FarmState.bgFailStreak == 2) notifyAttention()
        }
        refreshNotification()
    }

    /** Two hidden runs failed in a row: the visible farm is the proven path. */
    private fun notifyAttention() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(ATTENTION_CHANNEL_ID, "PocketGLM alerts", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(
            ATTENTION_NOTIFICATION_ID,
            Notification.Builder(this, ATTENTION_CHANNEL_ID)
                .setContentTitle("Background farm is not working")
                .setContentText("Open the app and run the token farm by hand.")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
        )
    }

    private fun refreshNotification() {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        } catch (ignored: Exception) {
        }
    }

    /**
     * Copies the ARM64 bridge binary into filesDir and marks it executable.
     * WARNING: on Android 10+ (targetSdk >= 29) this is expected to FAIL at
     * exec time even when setExecutable() reports success — the kernel
     * denies execve() on any app-data file that has ever been writable.
     * Kept only as the second diagnostic strategy; the real exec path is
     * nativeLibraryDir (see startBridge).
     */
    private fun extractBinary(target: File) {
        val src = File(applicationInfo.nativeLibraryDir, "libzai.so")
        src.copyTo(target, overwrite = true)
        // drop the write bit while the file is still a plain data file…
        target.setWritable(false, false)
        target.setReadable(true, false)
        // …only now may the exec bit stick
        val ok = target.setExecutable(true, false) && target.canExecute()
        if (!ok) {
            throw IllegalStateException(
                "cannot mark ${target.name} executable (W^X restriction); src=${src.absolutePath} exists=${src.exists()}"
            )
        }
    }

    /**
     * The Go bridge exits with status 1 when its SQLite token DB is missing.
     * On first launch (or after the user cleared data) we create the file
     * with the expected schema; the WebView token farm fills it later.
     */
    private fun ensureTokenDb(workDir: File) {
        BridgeDb.ensure(workDir)
    }

    private fun promoteToForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "PocketGLM bridge",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows while the local bridge is running"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)

        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val status = Prefs.bgStatus(this)
        val text = if (Prefs.bgFarm(this) && status.isNotEmpty()) "Bridge on :3001 | farm: $status" else "Bridge running on :3001"
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("PocketGLM")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()
    }

    override fun onDestroy() {
        // Not through stopBridge(): "wanted" must survive so a system restart can bring the bridge back.
        shutDown()
        super.onDestroy()
    }
}
