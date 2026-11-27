package com.amwolfstein.sistrum.library.scan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Foreground dataSync service that keeps a library scan alive while the app is backgrounded. */
class ScanService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Scanning library", 0, 0))
        observer = scope.launch {
            ScanCoordinator.progress.collectLatest { progress ->
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(NOTIFICATION_ID, buildNotification(progress))
                if (progress.state in TERMINAL_STATES) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> ScanCoordinator.pause()
            ACTION_RESUME -> ScanCoordinator.resume()
            ACTION_CANCEL -> ScanCoordinator.cancel()
            ACTION_START -> {
                val sourceId = intent.getStringExtra(EXTRA_SOURCE_ID)
                if (sourceId != null) {
                    ScanCoordinator.begin(applicationContext, sourceId)
                } else if (!ScanCoordinator.isRunning) {
                    ScanCoordinator.resumePending(applicationContext)
                }
            }
            null -> if (!ScanCoordinator.isRunning) {
                ScanCoordinator.resumePending(applicationContext)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        observer?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(progress: ScanProgress): Notification {
        val text = when (progress.state) {
            ScanState.RUNNING -> if (progress.totalFiles > 0) {
                "${progress.scannedFiles} / ${progress.totalFiles}"
            } else {
                "Preparing library"
            }
            ScanState.PAUSED -> "Scan paused"
            ScanState.CANCELLING -> "Cancelling…"
            ScanState.COMPLETED -> "Scan complete"
            ScanState.COMPLETED_WITH_ERRORS -> "Scan complete with errors"
            ScanState.CANCELLED -> "Scan cancelled"
            ScanState.FAILED -> "Scan failed"
            ScanState.IDLE -> "Idle"
        }
        val paused = progress.state == ScanState.PAUSED
        return buildNotification(
            "Library scan",
            progress.scannedFiles,
            progress.totalFiles,
            text,
            paused = paused,
        )
    }

    private fun buildNotification(
        title: String,
        progress: Int,
        max: Int,
        text: String = "",
        paused: Boolean = false,
    ): Notification {
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    if (paused) "Resume" else "Pause",
                    serviceIntent(if (paused) ACTION_RESUME else ACTION_PAUSE),
                ).build(),
            )
            .addAction(Notification.Action.Builder(null, "Cancel", serviceIntent(ACTION_CANCEL)).build())
        if (max > 0) {
            builder.setProgress(max, progress, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun serviceIntent(action: String): android.app.PendingIntent =
        android.app.PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, ScanService::class.java).setAction(action),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Library scan", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        const val ACTION_START = "com.amwolfstein.sistrum.scan.START"
        const val ACTION_PAUSE = "com.amwolfstein.sistrum.scan.PAUSE"
        const val ACTION_RESUME = "com.amwolfstein.sistrum.scan.RESUME"
        const val ACTION_CANCEL = "com.amwolfstein.sistrum.scan.CANCEL"
        const val EXTRA_SOURCE_ID = "sourceId"
        private const val CHANNEL_ID = "library_scan"
        private const val NOTIFICATION_ID = 4101
        private val TERMINAL_STATES = setOf(
            ScanState.COMPLETED,
            ScanState.COMPLETED_WITH_ERRORS,
            ScanState.CANCELLED,
            ScanState.FAILED,
        )
    }
}
