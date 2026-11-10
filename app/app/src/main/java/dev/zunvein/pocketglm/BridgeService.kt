package dev.zunvein.pocketglm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
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
        const val CHANNEL_ID = "pocketglm_bridge"
        const val BINARY_NAME = "zai-api-android-arm64"
        const val NOTIFICATION_ID = 1
    }

    private var bridgeProcess: Process? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startBridge()
            ACTION_STOP -> stopBridge()
        }
        return START_NOT_STICKY
    }

    private fun startBridge() {
        promoteToForeground()

        Thread {
            val workDir = File(filesDir, "bridge").apply { mkdirs() }
            val logFile = File(workDir, "bridge.log")

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
                .start()
            appendLog(workDir, "[app] spawned OK from ${bin.parent}")
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
        bridgeProcess?.destroy()
        bridgeProcess = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification =
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("PocketGLM")
                .setContentText("Bridge running on :3001")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setOngoing(true)
                .setContentIntent(openIntent)
                .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        bridgeProcess?.destroy()
        bridgeProcess = null
        super.onDestroy()
    }
}
