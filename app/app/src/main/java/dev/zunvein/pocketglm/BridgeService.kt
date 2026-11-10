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
            try {
                val workDir = File(filesDir, "bridge").apply { mkdirs() }
                val binary = File(workDir, BINARY_NAME)

                // Always (re)extract: a copy left over from a previous
                // failed start carries poisoned permission bits and can
                // never be repaired in place (Android's write-implies-noexec
                // rule) — a fresh copy, permissions applied in the right
                // order, is the only fix.
                extractBinary(binary)
                android.util.Log.i("PocketGLM", "bridge binary ready: ${binary.length()} bytes, exec=${binary.canExecute()}")

                // Boot guard: the bridge refuses to start without a token DB.
                ensureTokenDb(workDir)

                val logFile = File(workDir, "bridge.log")

                bridgeProcess = ProcessBuilder(binary.absolutePath)
                    .directory(workDir)
                    .redirectErrorStream(true)
                    .redirectOutput(logFile)
                    .start()
            } catch (e: Exception) {
                android.util.Log.e("PocketGLM", "FAILED to start bridge", e)
                captureFailure(e)
            }
        }.start()
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
     * The binary ships as jniLibs/arm64-v8a/libzai.so (nativeLibs dir is
     * extracted by the installer already, but app data dir is the only place
     * we can reliably chmod +x and NULL-deref exec on every device).
     *
     * Android 10+ W^X rule: a file in app data that is EVER marked writable
     * can never be made executable afterwards — even after clearing the
     * write bit. Permissions must be applied in this exact order:
     * read-only FIRST, exec bit LAST.
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
