package app.netwatch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder

class MonitorService : Service() {

    companion object {
        @Volatile var running = false
        @Volatile var dropCount = 0
        @Volatile var lastLatencyMs = -1L

        private const val CHANNEL_ID = "monitor"
        private const val NOTIFICATION_ID = 1
        private const val PING_INTERVAL_MS = 30_000L
        private const val PINGS_PER_SUMMARY = 10
    }

    private lateinit var cm: ConnectivityManager
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var pingThread: HandlerThread? = null
    private var pingHandler: Handler? = null

    @Volatile private var lostAtMs = 0L
    @Volatile private var lastTransport: String? = null
    @Volatile private var lastValidated: Boolean? = null
    @Volatile private var lastPingOk = true
    private var pingCounter = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        createChannel()
        val notification = buildNotification("Überwachung läuft ...")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        EventLog.append(this, "▶ Überwachung gestartet")
        cm = getSystemService(ConnectivityManager::class.java)
        registerNetworkCallback()
        startPingLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        pingThread?.quitSafely()
        EventLog.append(this, "⏹ Überwachung gestoppt")
        super.onDestroy()
    }

    private fun registerNetworkCallback() {
        val cb = object : ConnectivityManager.NetworkCallback() {

            override fun onAvailable(network: Network) {
                val transport = cm.getNetworkCapabilities(network)?.let { Net.transportLabel(it) } ?: "unbekannt"
                if (lostAtMs > 0) {
                    val downSeconds = (System.currentTimeMillis() - lostAtMs) / 1000
                    lostAtMs = 0
                    EventLog.append(this@MonitorService,
                        "✅ Verbindung wieder da ($transport) – war $downSeconds s weg")
                } else {
                    EventLog.append(this@MonitorService, "✅ Verbindung aktiv ($transport)")
                }
                lastTransport = transport
                updateNotification()
            }

            override fun onLost(network: Network) {
                dropCount++
                lostAtMs = System.currentTimeMillis()
                lastTransport = null
                lastValidated = null
                EventLog.append(this@MonitorService, "❌ Verbindung verloren (Abbruch Nr. $dropCount)")
                updateNotification()
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val transport = Net.transportLabel(caps)
                val previousTransport = lastTransport
                if (previousTransport != null && previousTransport != transport) {
                    EventLog.append(this@MonitorService, "🔁 Wechsel: $previousTransport → $transport")
                }
                lastTransport = transport

                val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val previousValidated = lastValidated
                if (previousValidated != null && previousValidated != validated) {
                    if (validated) {
                        EventLog.append(this@MonitorService, "✅ Internet wieder erreichbar")
                    } else {
                        EventLog.append(this@MonitorService,
                            "⚠ Verbunden, aber Internet NICHT erreichbar")
                    }
                }
                lastValidated = validated
            }
        }
        callback = cb
        cm.registerDefaultNetworkCallback(cb)
    }

    private fun startPingLoop() {
        val thread = HandlerThread("netwatch-ping").also { it.start() }
        pingThread = thread
        val handler = Handler(thread.looper)
        pingHandler = handler

        val task = object : Runnable {
            override fun run() {
                if (!running) return
                val latency = Net.probeLatencyMs()
                lastLatencyMs = latency
                pingCounter++

                if (latency < 0 && lastPingOk) {
                    lastPingOk = false
                    EventLog.append(this@MonitorService,
                        "⚠ Erreichbarkeits-Test fehlgeschlagen (kein Internet?)")
                } else if (latency >= 0 && !lastPingOk) {
                    lastPingOk = true
                    EventLog.append(this@MonitorService,
                        "✅ Erreichbarkeits-Test wieder ok ($latency ms)")
                } else if (latency >= 0 && pingCounter % PINGS_PER_SUMMARY == 0) {
                    EventLog.append(this@MonitorService, "📶 Latenz: $latency ms")
                }

                updateNotification()
                handler.postDelayed(this, PING_INTERVAL_MS)
            }
        }
        handler.post(task)
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Netzwerk-Überwachung", NotificationManager.IMPORTANCE_LOW
        )
        channel.description = "Dauerhafte Anzeige, solange NetWatch die Verbindung überwacht"
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("NetWatch")
            .setContentText(text)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val latencyText = if (lastLatencyMs >= 0) "${lastLatencyMs} ms" else "–"
        val text = "Abbrüche: $dropCount · Latenz: $latencyText"
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }
}
