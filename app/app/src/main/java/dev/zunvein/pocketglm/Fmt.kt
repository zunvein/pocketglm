package dev.zunvein.pocketglm

import java.util.Locale

/** Small number and duration formatting shared by the screens. */
object Fmt {

    fun num(n: Long): String = String.format(Locale.US, "%,d", n)

    /** 45 s, 12 m, 3 h 20 m. Pool age reads better than a raw second count. */
    fun age(sec: Long): String = when {
        sec < 0 -> "–"
        sec < 90 -> "${sec}s"
        sec < 5400 -> "${sec / 60}m"
        else -> "${sec / 3600}h ${(sec % 3600) / 60}m"
    }
}
