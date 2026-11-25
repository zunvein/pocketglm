package dev.zunvein.pocketglm

import android.content.Context
import java.io.File
import java.util.Date
import java.util.Locale

/**
 * Crash logger: an uncaught exception anywhere in the app process is written
 * to files/bridge/crash.log (and to crash-last.log), so a crash on a phone can
 * be read from Settings without adb. Installed from every Activity's onCreate;
 * the previous handler still runs, so system reporting is unchanged.
 */
object CrashLog {
    @Volatile private var installed = false

    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val appCtx = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val dir = File(appCtx.filesDir, "bridge").apply { mkdirs() }
                val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val lines = StringBuilder().append("[$stamp] thread=${t.name} ${e.javaClass.name}: ${e.message}\n")
                for (el in e.stackTrace.take(30)) lines.append("    at $el\n")
                val cause = e.cause
                if (cause != null) {
                    lines.append("  caused by ${cause.javaClass.name}: ${cause.message}\n")
                    for (el in cause.stackTrace.take(20)) lines.append("    at $el\n")
                }
                File(dir, "crash.log").appendText(lines.toString())
                File(dir, "crash-last.log").writeText(lines.toString())
            } catch (ignored: Exception) {
            }
            previous?.uncaughtException(t, e)
        }
    }

    /** The last crash recorded on this install, or null. */
    fun last(ctx: Context): String? {
        val f = File(File(ctx.filesDir, "bridge"), "crash-last.log")
        return if (f.exists()) f.readText().take(4000).ifEmpty { null } else null
    }

    fun clear(ctx: Context) {
        File(File(ctx.filesDir, "bridge"), "crash-last.log").delete()
        File(File(ctx.filesDir, "bridge"), "crash.log").delete()
    }
}
