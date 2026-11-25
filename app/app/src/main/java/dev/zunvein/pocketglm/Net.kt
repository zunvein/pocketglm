package dev.zunvein.pocketglm

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address

/** The address other devices on the same Wi-Fi use to reach this phone. */
object Net {

    fun lanIp(ctx: Context): String? = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm.getLinkProperties(cm.activeNetwork)?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }
}
