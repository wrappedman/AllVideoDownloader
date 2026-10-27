package com.allvideodownloader.avd

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.allvideodownloader.avd.core.DownloadManager

/**
 * Keeps the download manager alive while the app is in the background.
 *
 * The desktop app is a window that never gets killed; Android stops background
 * work unless it is attached to a foreground service. This service carries the
 * same jobs (the manager singleton) and shows their aggregate progress, so
 * "downloads in flight are saved up to the last chunk" still holds on a phone.
 */
class DownloadService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var idleTicks = 0

    private val tick = object : Runnable {
        override fun run() {
            val active = DownloadManager.instance.activeCount()
            if (active == 0) {
                idleTicks++
                if (idleTicks >= 3) {
                    stopSelf()
                    return
                }
            } else {
                idleTicks = 0
            }
            notify(active)
            handler.postDelayed(this, 2000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(DownloadManager.instance.activeCount()))
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun notify(active: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(active))
    }

    private fun buildNotification(active: Int): Notification {
        val open = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, AvdApp.CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)

        val text = when (active) {
            0 -> "Ready — paste a link to grab a video"
            1 -> "1 download in progress"
            else -> "$active downloads in progress"
        }
        return builder
            .setContentTitle("All Video Downloader")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pending)
            .setOngoing(active > 0)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 4041

        fun ensureRunning(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            } catch (_: Exception) {
                // Android 12+ can refuse a background start; the app foreground
                // path always succeeds, and transfers then run in-process.
            }
        }
    }
}
