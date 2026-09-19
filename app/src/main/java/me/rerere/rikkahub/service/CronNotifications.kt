package me.rerere.rikkahub.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * Shared notification-channel plumbing for scheduled-job alerts.
 *
 * Channel importance is immutable once created — the legacy `rikkahub_cron_jobs` channel
 * is stuck at IMPORTANCE_DEFAULT on existing installs, so job alerts moved to
 * [SCHEDULED_JOBS_HIGH_CHANNEL_ID] (IMPORTANCE_HIGH heads-up, aligned with open-source
 * alarm/calendar apps) instead of mutating the old channel in place.
 */
internal const val SCHEDULED_JOBS_HIGH_CHANNEL_ID = "rikkahub_cron_jobs_high"

/** Alarm-style long vibration (WakeUp pattern): buzz 5s, pause 0.5s, buzz 5s. */
internal val SCHEDULED_JOB_VIBRATE_PATTERN: LongArray = longArrayOf(0, 5000, 500, 5000)

/**
 * Creates the IMPORTANCE_HIGH scheduled-jobs channel when missing and returns its id.
 * Never throws on a missing NotificationManager — callers post best-effort anyway.
 */
internal fun ensureScheduledJobsHighChannel(context: Context): String {
    val nm = context.getSystemService(NotificationManager::class.java)
    if (nm != null && nm.getNotificationChannel(SCHEDULED_JOBS_HIGH_CHANNEL_ID) == null) {
        nm.createNotificationChannel(
            NotificationChannel(
                SCHEDULED_JOBS_HIGH_CHANNEL_ID,
                "Scheduled jobs",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                enableVibration(true)
                vibrationPattern = SCHEDULED_JOB_VIBRATE_PATTERN
            }
        )
    }
    return SCHEDULED_JOBS_HIGH_CHANNEL_ID
}
