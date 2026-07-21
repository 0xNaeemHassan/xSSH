/*
 * xSSH — Foreground service that keeps live SSH sessions and running tunnels
 * alive when the app is backgrounded.
 *
 * Uses the special-use foreground-service type on Android 14+ because an
 * interactive SSH terminal is open-ended user-controlled work rather than a
 * time-limited data-sync job. Older supported versions use dataSync. The
 * notification is low-importance (silent, no badge) and
 * shows the count of active sessions + tunnels so the user is always aware
 * of background work.
 *
 * Callers pass EXTRA_SESSIONS / EXTRA_TUNNELS on each start intent so the
 * text updates as things toggle. In practice all traffic goes through
 * [BackgroundActivityController], which owns the atomic counters and
 * rebuilds the intent on every bump.
 *
 * A zero-count start intent is a valid "please tear down now" signal — this
 * happens when the last session/tunnel stops and the controller wants to
 * release the foreground promotion in a single round-trip.
 *
 * Icon note: status-bar icons on Android 5+ are re-tinted white by the
 * system, so we use a stock monochrome asset rather than the colour launcher
 * icon (which would render as a white square).
 */
package com.xssh.feature.session

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

class SessionForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val sessions = intent?.getIntExtra(EXTRA_SESSIONS, 0) ?: 0
        val tunnels = intent?.getIntExtra(EXTRA_TUNNELS, 0) ?: 0

        // Idle → this intent is really a "shut down now" signal from the
        // controller. Nothing to promote; drop the foreground state cleanly.
        if (sessions + tunnels == 0) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        ensureChannel()
        val notification = buildNotification(sessions, tunnels)

        // The type must match the manifest declaration. Android 14+ uses the
        // open-ended special-use category; Android 12/13 use dataSync.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        }
        // The live sockets exist only in this process and cannot be restored
        // from a null restart intent after process death.
        return START_NOT_STICKY
    }

    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        stopForegroundCompat()
        stopSelf(startId)
    }

    private fun buildNotification(
        sessions: Int,
        tunnels: Int,
    ): Notification {
        val text =
            buildString {
                if (sessions > 0) {
                    append(sessions).append(" active session")
                    if (sessions != 1) append('s')
                }
                if (tunnels > 0) {
                    if (isNotEmpty()) append(" • ")
                    append(tunnels).append(" tunnel")
                    if (tunnels != 1) append('s')
                }
                if (isEmpty()) append("Background activity") // defensive
            }
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent =
            launchIntent?.let {
                PendingIntent.getActivity(
                    this,
                    0,
                    it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("xSSH")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_xssh)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun ensureChannel() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "Sessions", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Ongoing SSH sessions and active port forwards"
                    setShowBadge(false)
                },
            )
        }
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    companion object {
        const val EXTRA_SESSIONS = "xssh.extra.sessions"
        const val EXTRA_TUNNELS = "xssh.extra.tunnels"
        private const val CHANNEL = "xssh.sessions"
        private const val NOTIFICATION_ID = 42
    }
}
