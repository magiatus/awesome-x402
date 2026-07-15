package app.netwatch

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var toggleButton: Button

    private val uiHandler = Handler(Looper.getMainLooper())
    private val refreshTask = object : Runnable {
        override fun run() {
            updateStatus()
            loadLog()
            uiHandler.postDelayed(this, 5000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        logText = findViewById(R.id.log_text)
        toggleButton = findViewById(R.id.btn_toggle)

        toggleButton.setOnClickListener { toggleMonitor() }
        findViewById<Button>(R.id.btn_test).setOnClickListener { testNow() }
        findViewById<Button>(R.id.btn_share).setOnClickListener { shareLog() }
        findViewById<Button>(R.id.btn_clear).setOnClickListener {
            EventLog.clear(this)
            loadLog()
            Toast.makeText(this, "Protokoll gelöscht", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        uiHandler.post(refreshTask)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(refreshTask)
    }

    // ---------------- Statusanzeige ----------------

    private fun updateStatus() {
        val sb = StringBuilder()
        val cm = getSystemService(ConnectivityManager::class.java)
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }

        if (caps == null) {
            sb.appendLine("❌ KEINE VERBINDUNG")
        } else {
            val transport = Net.transportLabel(caps)
            val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            sb.appendLine("Verbindung : $transport ${if (validated) "✅" else "⚠ (kein Internet)"}")

            val down = caps.linkDownstreamBandwidthKbps / 1000
            val up = caps.linkUpstreamBandwidthKbps / 1000
            if (down > 0) sb.appendLine("Bandbreite : ↓ $down / ↑ $up Mbit/s (geschätzt)")

            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                appendWifiDetails(sb)
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                val tm = getSystemService(TelephonyManager::class.java)
                val operator = tm?.networkOperatorName.orEmpty()
                if (operator.isNotBlank()) sb.appendLine("Netzbetreiber: $operator")
            }

            val lp = cm.getLinkProperties(network)
            val ips = lp?.linkAddresses?.joinToString(", ") { it.address.hostAddress ?: "" }
            if (!ips.isNullOrBlank()) sb.appendLine("IP-Adressen: $ips")
            val dns = lp?.dnsServers?.joinToString(", ") { it.hostAddress ?: "" }
            if (!dns.isNullOrBlank()) sb.appendLine("DNS        : $dns")
        }

        sb.appendLine()
        if (MonitorService.running) {
            val latency = MonitorService.lastLatencyMs
            val latencyText = if (latency >= 0) "$latency ms" else "–"
            sb.appendLine("🟢 Überwachung AKTIV")
            sb.appendLine("   Abbrüche: ${MonitorService.dropCount} · Latenz: $latencyText")
            toggleButton.text = "Überwachung stoppen"
        } else {
            sb.appendLine("⚪ Überwachung inaktiv")
            toggleButton.text = "Überwachung starten"
        }

        statusText.text = sb.toString().trimEnd()
    }

    private fun appendWifiDetails(sb: StringBuilder) {
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val info = wm.connectionInfo ?: return
            val ssid = info.ssid?.trim('"').orEmpty()
            if (ssid.isNotBlank() && !ssid.contains("unknown", ignoreCase = true)) {
                sb.appendLine("WLAN-Name  : $ssid")
            } else if (!hasLocationPermission()) {
                sb.appendLine("WLAN-Name  : (Standort-Berechtigung nötig)")
            }
            if (info.rssi != -127 && info.rssi < 0) {
                sb.appendLine("Signal     : ${info.rssi} dBm (${signalQuality(info.rssi)})")
            }
            if (info.linkSpeed > 0) {
                sb.appendLine("Link-Speed : ${info.linkSpeed} Mbit/s")
            }
        } catch (_: Exception) {
        }
    }

    private fun signalQuality(rssi: Int): String = when {
        rssi >= -55 -> "sehr gut"
        rssi >= -67 -> "gut"
        rssi >= -75 -> "mittel"
        rssi >= -85 -> "schwach"
        else -> "sehr schwach"
    }

    // ---------------- Aktionen ----------------

    private fun toggleMonitor() {
        if (MonitorService.running) {
            stopService(Intent(this, MonitorService::class.java))
            uiHandler.postDelayed({ updateStatus(); loadLog() }, 300)
        } else {
            requestNeededPermissions()
            startForegroundService(Intent(this, MonitorService::class.java))
            uiHandler.postDelayed({ updateStatus(); loadLog() }, 300)
        }
    }

    private fun requestNeededPermissions() {
        val wanted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (!hasLocationPermission()) {
            wanted.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (wanted.isNotEmpty()) {
            requestPermissions(wanted.toTypedArray(), 42)
        }
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun testNow() {
        Toast.makeText(this, "Teste Verbindung ...", Toast.LENGTH_SHORT).show()
        Thread {
            val latency = Net.probeLatencyMs()
            val message = if (latency >= 0) {
                EventLog.append(this, "🧪 Manueller Test: $latency ms (${Net.transportLabel(this)})")
                "✅ Internet erreichbar – $latency ms"
            } else {
                EventLog.append(this, "🧪 Manueller Test FEHLGESCHLAGEN")
                "❌ Internet NICHT erreichbar"
            }
            runOnUiThread {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                updateStatus()
                loadLog()
            }
        }.start()
    }

    private fun loadLog() {
        val tail = EventLog.readTailNewestFirst(this)
        logText.text = if (tail.isBlank()) "(noch keine Einträge)" else tail
    }

    private fun shareLog() {
        val content = EventLog.readTailNewestFirst(this, 500)
        if (content.isBlank()) {
            Toast.makeText(this, "Protokoll ist leer", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "NetWatch Verbindungs-Protokoll")
            putExtra(Intent.EXTRA_TEXT, content)
        }
        startActivity(Intent.createChooser(intent, "Protokoll teilen"))
    }
}
