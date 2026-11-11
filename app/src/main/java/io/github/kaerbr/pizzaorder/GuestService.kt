package io.github.kaerbr.pizzaorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the guest page reachable while the tablet's screen is off or another app is in front.
 *
 * The server itself lives in [GuestHost]; the service is what Android wants for a socket that stays open
 * in the background: without it the process may be killed mid-evening and every phone loses the page.
 * `dataSync` is the fitting type — the tablet serves a local page, not a media stream.
 */
class GuestService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val host = OrderDesk.get(this).guest
        startInForeground(notification(host.runtime.value.port))

        scope.launch {
            val running = OrderDesk.get(this@GuestService).startGuest()
            if (running) {
                updateNotification(host.runtime.value)
            } else {
                // Nothing listens, so nothing needs to hold a foreground slot: the panel keeps the reason
                // ("Port belegt", "Zugriff fehlt") and the admin retries from there. shutdown() leaves a
                // Failed status alone, so the reason survives this teardown.
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        // Closes the socket; the admin's "on" stays in preferences so the next app start brings it back.
        OrderDesk.get(this).guest.shutdown()
        super.onDestroy()
    }

    /** Android 14 wants the type at start time as well as in the manifest. */
    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Re-posted through startForeground, not NotificationManager.notify: a foreground service's own
     * notification needs no POST_NOTIFICATIONS, while a plain notify() would on Android 13+.
     */
    private fun updateNotification(runtime: GuestRuntime) =
        startInForeground(notification(runtime.port, runtime.status))

    private fun notification(port: Int, status: GuestStatus = GuestStatus.Starting): Notification {
        getSystemService(NotificationManager::class.java)
            ?.takeIf { it.getNotificationChannel(CHANNEL_ID) == null }
            ?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Gäste-Bestellung", NotificationManager.IMPORTANCE_LOW),
            )

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val text = when (status) {
            is GuestStatus.Running -> "Gäste können bestellen, Port $port."
            is GuestStatus.Failed -> status.message
            else -> "Startet …"
        }

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Gäste-Bestellung läuft")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "guest-server"
        private const val NOTIFICATION_ID = 1

        /**
         * The switch in the admin panel: start the foreground service or take it down.
         *
         * Whether the local network permission is there is decided in [GuestHost.start] (reached through
         * [OrderDesk.startGuest]), so the panel and the service reach the same verdict, not two that disagree.
         */
        fun setEnabled(context: Context, enabled: Boolean) {
            if (enabled) {
                ContextCompat.startForegroundService(context, Intent(context, GuestService::class.java))
            } else {
                stop(context)
                OrderDesk.get(context).guest.stop()
            }
        }

        /**
         * Takes the service down and leaves the switch and its reason alone: a port change that failed must
         * not keep a foreground slot with nothing listening behind it.
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, GuestService::class.java))
        }
    }
}
