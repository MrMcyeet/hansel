package dev.hansel.offline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Runs [OfflinePipeline] as a foreground service in its own `:build` process (see the module manifest), so the
 * multi-hundred-megabyte GraphHopper import gets its own large heap and cannot take the UI process down with it.
 * Progress is written to `progress.json`; the main process watches that file.
 */
class PackageBuildService : LifecycleService() {
    private var job: Job? = null
    private lateinit var layout: OfflineLayout

    override fun onCreate() {
        super.onCreate()
        layout = OfflineLayout(this)
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_CANCEL) {
            job?.cancel(CancellationException("cancelled by user"))
            return START_NOT_STICKY
        }
        val request = JsonFiles.read<OfflineJobRequest>(layout.jobFile)
        if (request == null) { stopSelf(); return START_NOT_STICKY }
        startForegroundCompat(notification("Preparing offline data", "Starting…", null))
        if (job?.isActive == true) return START_STICKY
        job = lifecycleScope.launch(Dispatchers.IO) {
            val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
            var lastNotify = 0L
            try {
                OfflinePipeline(applicationContext, layout, http).run(request) { p ->
                    JsonFiles.write(layout.progressFile, p)
                    val now = System.currentTimeMillis()
                    if (now - lastNotify > 1500 || p.isTerminal) {
                        lastNotify = now
                        notify(notification(title(p), p.detail, p.fraction))
                    }
                }
            } catch (_: CancellationException) {
            } catch (t: Throwable) {
                JsonFiles.write(layout.progressFile, JobProgress(JobProgress.Phase.FAILED, null, (t.message ?: "failed").take(300), startedAtMillis = System.currentTimeMillis()))
            } finally {
                layout.jobFile.delete()
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun title(p: JobProgress) = when (p.phase) {
        JobProgress.Phase.DOWNLOADING -> "Downloading map data"
        JobProgress.Phase.MERGING -> "Merging regions"
        JobProgress.Phase.INDEXING_SEARCH -> "Building search index"
        JobProgress.Phase.IMPORTING_GRAPH, JobProgress.Phase.PREPARING_CH -> "Building routing graph"
        JobProgress.Phase.DOWNLOADING_TILES -> "Downloading map tiles"
        JobProgress.Phase.STYLING -> "Fetching fonts and styles"
        JobProgress.Phase.DONE -> "Offline data ready"
        JobProgress.Phase.FAILED -> "Offline build failed"
        JobProgress.Phase.CANCELLED -> "Offline build cancelled"
        else -> "Preparing offline data"
    }

    private fun notification(title: String, text: String, fraction: Double?): Notification {
        val cancel = PendingIntent.getService(this, 1, Intent(this, PackageBuildService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .setProgress(1000, ((fraction ?: 0.0) * 1000).toInt(), fraction == null)
            .addAction(0, "Cancel", cancel)
            .build()
    }

    private fun notify(n: Notification) = (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, n)

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIFICATION_ID, n)
    }

    companion object {
        const val CHANNEL_ID = "offline-build"
        const val NOTIFICATION_ID = 0x4F46
        const val ACTION_CANCEL = "dev.hansel.offline.action.CANCEL_BUILD"

        fun start(context: Context, layout: OfflineLayout, request: OfflineJobRequest) {
            JsonFiles.write(layout.jobFile, request)
            JsonFiles.write(layout.progressFile, JobProgress(JobProgress.Phase.QUEUED, null, "Queued", startedAtMillis = System.currentTimeMillis()))
            context.startForegroundService(Intent(context, PackageBuildService::class.java))
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, PackageBuildService::class.java).setAction(ACTION_CANCEL))
        }

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Offline data builds", NotificationManager.IMPORTANCE_LOW))
            }
        }
    }
}
