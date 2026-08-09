package me.rerere.rikkahub.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Durable short-delay retry for [DirectCronAlarmReceiver] / [ExactCronAlarmReceiver] when
 * their WorkManager enqueue fails — either synchronously (exception from the enqueue call)
 * or asynchronously (the Operation completes with failure). A fire that is only logged and
 * dropped would permanently consume the slot: the job's run row would never be written and
 * nothing would advance the schedule.
 *
 * The retry re-arms an AlarmManager alarm that reuses the SAME jobId + scheduled slot
 * (the extras carry them verbatim) under ONE stable PendingIntent identity (same
 * component, action, `rikkahub://cron-retry/` data URI and request code), so retries never
 * proliferate distinct alarm identities. The identity is distinct from the scheduler's own
 * mode-specific alarms, so re-arming the schedule cannot accidentally clobber a pending
 * retry. Attempts are bounded ([MAX_ATTEMPTS]) so a WorkManager outage cannot spin alarms
 * forever; the exact-backend WorkManager safety backup (see
 * [CronJobScheduler.EXACT_BACKUP_GRACE_MS]) remains the final safety net.
 *
 * [arm] never throws: every non-fatal failure is caught and logged so receivers can always
 * finish their pending broadcast. For a pure fallback job (no exact backup), a scheduler
 * enqueue failure surfaces to the caller as a throw from the awaited Operation instead.
 */
internal object CronAlarmRetry {
    const val KEY_RETRY_ATTEMPT = "cron_job_retry_attempt"
    private const val TAG = "CronAlarmRetry"
    private const val MAX_ATTEMPTS = 5
    private const val RETRY_DELAY_MS = 60_000L

    /** Returns true when a retry alarm was armed, false when attempts were exhausted or arming failed. */
    fun arm(
        context: Context,
        receiverClass: Class<*>,
        action: String,
        jobId: String,
        scheduledAtMs: Long,
        attempt: Int,
    ): Boolean {
        if (attempt >= MAX_ATTEMPTS) {
            Log.e(TAG, "giving up after $attempt retries for job $jobId (slot $scheduledAtMs)")
            return false
        }
        return try {
            val pi = PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, receiverClass)
                    .setAction(action)
                    .setData(Uri.parse("rikkahub://cron-retry/$jobId"))
                    .putExtra(CronJobWorker.KEY_JOB_ID, jobId)
                    .putExtra(CronJobWorker.KEY_SCHEDULED_AT_MS, scheduledAtMs)
                    .putExtra(KEY_RETRY_ATTEMPT, attempt + 1),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val at = System.currentTimeMillis() + RETRY_DELAY_MS
            val am = context.getSystemService(AlarmManager::class.java)
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } catch (_: SecurityException) {
                // No (or revoked) exact-alarm permission — an inexact retry is still
                // durable and far better than losing the fire.
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
            true
        } catch (t: Throwable) {
            // Never propagate into the receiver's pendingResult.finish() path — the fire
            // is already in trouble; the exact backup (if any) or the next schedule heals it.
            Log.e(TAG, "failed to arm retry alarm for job $jobId (slot $scheduledAtMs)", t)
            false
        }
    }
}
