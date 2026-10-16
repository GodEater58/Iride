package it.goletto.iride

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Servizio in primo piano durante l'intervallometro e i download lunghi.
 *
 * Senza, a schermo spento e telefono fermo (proprio come in astrofotografia) Android dopo un po'
 * mette l'app in pausa e spegne la rete: la sequenza si fermerebbe a metà. Il servizio tiene
 * sveglia la CPU e il Wi-Fi e mostra una notifica con l'avanzamento.
 */
class SessionService : Service() {

    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Collegata alla fotocamera"
        val n = notification(this, text)
        running = true
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(ID, n)
        }
        if (wake == null) {
            wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "iride:sessione")
                .apply { setReferenceCounted(false); acquire(12 * 60 * 60 * 1000L) }
        }
        if (wifi == null) {
            @Suppress("DEPRECATION")
            wifi = applicationContext.getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "iride:sessione")
                .apply { setReferenceCounted(false); acquire() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        runCatching { wake?.release() }
        runCatching { wifi?.release() }
        wake = null
        wifi = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "sessione"
        private const val ID = 7
        private const val EXTRA_TEXT = "text"

        @Volatile
        private var running = false

        private fun notification(ctx: Context, text: String): Notification {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Sessione di scatto", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Iride")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        }

        /** Avvia il servizio, o se è già attivo aggiorna solo la notifica (anche con l'app in secondo piano). */
        fun update(context: Context, text: String) {
            runCatching {
                if (running) {
                    context.getSystemService(NotificationManager::class.java).notify(ID, notification(context, text))
                } else {
                    context.startForegroundService(Intent(context, SessionService::class.java).putExtra(EXTRA_TEXT, text))
                }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, SessionService::class.java)) }
        }
    }
}
