package app.netwatch

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.URL

object Net {

    const val PROBE_URL = "https://connectivitycheck.gstatic.com/generate_204"

    /** Liefert die Latenz in ms oder -1, wenn der Test fehlschlägt. */
    fun probeLatencyMs(): Long {
        return try {
            val start = System.nanoTime()
            val conn = URL(PROBE_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.requestMethod = "GET"
            conn.useCaches = false
            val code = conn.responseCode
            conn.disconnect()
            if (code in 200..399) (System.nanoTime() - start) / 1_000_000 else -1
        } catch (_: Exception) {
            -1
        }
    }

    fun transportLabel(ctx: Context): String {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return "unbekannt"
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return "keine"
        return transportLabel(caps)
    }

    fun transportLabel(caps: NetworkCapabilities): String = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WLAN"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobilfunk"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
        else -> "sonstige"
    }
}
