package dev.hansel.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.hansel.navigation.NavState
import dev.hansel.navigation.NavigationHostRegistry
import dev.hansel.navigation.RouteProgress
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps navigation alive with the screen off (DESIGN §8, §13: no location collection without a visible notification).
 * It projects [NavigationHostRegistry.session]'s state into the ongoing notification and keeps the process in the
 * foreground so the app's fix pump (see `AppGraph`) keeps running. It deliberately does *not* feed fixes into the
 * session itself: doing so as well as the app would double-feed, and the service is not guaranteed to start.
 *
 * Start with [start] once a route is active and `ACCESS_FINE_LOCATION` is granted; it stops itself when the session
 * goes Idle or Arrived.
 */
class NavigationForegroundService : LifecycleService() {
    private var stateJob: Job? = null
    private var stopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopNavigationAndSelf()
            return START_NOT_STICKY
        }
        val session = NavigationHostRegistry.session
        if (session == null) {
            // Nothing to project; still must call startForeground after a startForegroundService, then leave.
            startForeground(NOTIFICATION_ID, buildNotification("Navigation", "No active route"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification("Navigation", "Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        observe()
        return START_STICKY
    }

    private fun observe() {
        stateJob?.cancel()
        val session = NavigationHostRegistry.session ?: return

        stateJob = lifecycleScope.launch {
            session.state
                .map { it.toNotificationModel() }
                .distinctUntilChanged()
                .collectLatest { model ->
                    when (model) {
                        is NotificationModel.Guidance -> {
                            stopJob?.cancel(); stopJob = null
                            notify(buildNotification(model.title, model.text))
                        }
                        NotificationModel.Arrived -> {
                            notify(buildNotification("Arrived", "You have reached your destination"))
                            scheduleStop(ARRIVED_LINGER_MILLIS)
                        }
                        NotificationModel.Idle -> scheduleStop(0)
                    }
                }
        }
    }

    private fun scheduleStop(delayMillis: Long) {
        if (stopJob?.isActive == true) return
        stopJob = lifecycleScope.launch {
            if (delayMillis > 0) delay(delayMillis)
            stopForegroundAndSelf()
        }
    }

    private fun stopNavigationAndSelf() {
        NavigationHostRegistry.session?.stop()
        stopForegroundAndSelf()
    }

    private fun stopForegroundAndSelf() {
        stateJob?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stateJob?.cancel(); stopJob?.cancel()
        super.onDestroy()
    }

    private fun notify(n: Notification) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, n)
    }

    private fun buildNotification(title: String, text: String): Notification {
        val stopIntent = PendingIntent.getBroadcast(
            this, 0, Intent(this, StopReceiver::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Stop", stopIntent)
        contentIntent(this)?.let { builder.setContentIntent(it) }
        return builder.build()
    }

    /** Tapping the notification opens the app's launcher activity, if there is one. */
    private fun contentIntent(context: Context): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, 1, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private sealed class NotificationModel {
        data class Guidance(val title: String, val text: String) : NotificationModel()
        data object Arrived : NotificationModel()
        data object Idle : NotificationModel()
    }

    private fun NavState.toNotificationModel(): NotificationModel = when (this) {
        is NavState.Navigating -> {
            val units = NavigationHostRegistry.units
            val p: RouteProgress? = progress
            if (p == null) {
                NotificationModel.Guidance("Navigating", "Waiting for GPS…")
            } else {
                val title = "${units.display(p.distanceToUpcomingMeters)} · ${p.upcoming.instruction}"
                val eta = units.eta(p.durationRemainingSeconds)
                val remaining = units.display(p.distanceRemainingMeters)
                val suffix = if (rerouting) " · Rerouting" else ""
                NotificationModel.Guidance(title, "$eta · $remaining remaining$suffix")
            }
        }
        is NavState.Arrived -> NotificationModel.Arrived
        is NavState.Previewing, NavState.Idle -> NotificationModel.Idle
    }

    /** Receives the notification's Stop action. Registered in this module's manifest; not exported. */
    class StopReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_STOP) return
            // Stopping the session flips state to Idle, which the service observes; stopService is belt and braces.
            NavigationHostRegistry.session?.stop()
            context.stopService(Intent(context, NavigationForegroundService::class.java))
        }
    }

    companion object {
        const val CHANNEL_ID = "navigation"
        const val NOTIFICATION_ID = 0x4E41 // "NA"
        const val ACTION_STOP = "dev.hansel.location.action.STOP_NAVIGATION"
        private const val ARRIVED_LINGER_MILLIS = 5_000L

        /** Call after [NavigationHostRegistry.session] is Navigating and fine location is granted. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, NavigationForegroundService::class.java))
        }

        /** Stops the session (if any) and the service. Safe to call when it isn't running. */
        fun stop(context: Context) {
            NavigationHostRegistry.session?.stop()
            context.stopService(Intent(context, NavigationForegroundService::class.java))
        }

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(CHANNEL_ID, "Navigation", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Turn-by-turn guidance while navigating"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            nm.createNotificationChannel(channel)
        }
    }
}
