package dev.zunvein.pocketglm

import android.content.Context
import java.io.File
import java.io.RandomAccessFile

/** Hides anything credential-shaped before text leaves the app (clipboard, screen). */
object Redact {
    private val pglKey = Regex("sk-pgl-[A-Za-z0-9]{8,}")
    private val jwt = Regex("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")

    fun text(ctx: Context, input: String): String {
        var out = input
        val key = ApiKeyStore.get(ctx)
        if (key.length >= 8) out = out.replace(key, AccountStore.mask(key))
        out = pglKey.replace(out) { AccountStore.mask(it.value) }
        out = jwt.replace(out) { AccountStore.mask(it.value) }
        return out
    }
}

object LogFile {
    fun file(ctx: Context) = File(ctx.filesDir, "bridge/bridge.log")

    /** The last [maxBytes] of the log, starting on a line boundary. */
    fun tail(ctx: Context, maxBytes: Int): String {
        val f = file(ctx)
        if (!f.exists()) return ""
        return try {
            RandomAccessFile(f, "r").use { raf ->
                val len = raf.length()
                val start = maxOf(0L, len - maxBytes)
                raf.seek(start)
                val buf = ByteArray((len - start).toInt())
                raf.readFully(buf)
                val s = String(buf, Charsets.UTF_8)
                if (start > 0) s.substringAfter('\n', s) else s
            }
        } catch (e: Exception) {
            ""
        }
    }

    fun append(ctx: Context, line: String) {
        try {
            File(ctx.filesDir, "bridge").mkdirs()
            file(ctx).appendText(line + "\n")
        } catch (ignored: Exception) {
        }
    }
}
