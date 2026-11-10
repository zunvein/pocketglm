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

                if (!binary.exists() || binary.length() == 0L) {
                    extractBinary(binary)
                    android.util.Log.i("PocketGLM", "extracted bridge binary: ${binary.length()} bytes")
                }

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
            val nat = File(applicationInfo.nativeLibraryDir, "libzai.so")
            logFile.appendText("[app] nativeLibraryDir libzai.so exists: ${nat.exists()} (${nat.length()} bytes)\n")
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
     */
    private fun extractBinary(target: File) {
        val src = File(applicationInfo.nativeLibraryDir, "libzai.so")
        src.copyTo(target, overwrite = true)
        // exec permission is required to spawn the process
        target.setExecutable(true, false)
        // W^X hardening on modern Android: strip the write bit on some
        // devices exec requires the file NOT be writable
        target.setWritable(false, false)
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
