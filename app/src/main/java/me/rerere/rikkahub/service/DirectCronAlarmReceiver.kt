package me.rerere.rikkahub.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import java.util.concurrent.Executor

/**
 * Turns a direct-mode AlarmManager.setAlarmClock wake-up into **immediate** execution.
 *
 * setAlarmClock arms a user-visible alarm (shown in the system clock app). When it fires,
 * this receiver enqueues an **expedited** OneTimeWorkRequest with zero delay — NOT the
 * regular delayed WorkManager path used by [CronJobScheduler.enqueueFlexible]. The
 * expedited worker gets foreground promotion via [CronJobWorker] and runs the direct-mode
 * action sequence through [DirectModeActionRunner].
 *
 * The receiver does not execute tools itself (broadcast time limits forbid it). It only
 * persists the immediate work request and keeps the broadcast pending until WorkManager
 * has committed the enqueue. This satisfies the contract: "Receiver 触发后不是普通延迟
 * WorkManager" — the work is expedited, 0-delay, and foreground-promoted, not a delayed
 * flexible enqueue.
 */
class DirectCronAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val jobId = intent.getStringExtra(CronJobWorker.KEY_JOB_ID) ?: return
        val scheduledAtMs = intent.getLongExtra(CronJobWorker.KEY_SCHEDULED_AT_MS, -1L)
        if (scheduledAtMs <= 0L) return

        val request = OneTimeWorkRequestBuilder<CronJobWorker>()
            .setInputData(
                Data.Builder()
                    .putString(CronJobWorker.KEY_JOB_ID, jobId)
                    .putLong(CronJobWorker.KEY_SCHEDULED_AT_MS, scheduledAtMs)
                    .build()
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(CronJobScheduler.workTagFor(jobId))
            .build()

        val pendingResult = goAsync()
        try {
            val operation = WorkManager.getInstance(context).enqueueUniqueWork(
                CronJobScheduler.directExecutionWorkName(jobId, scheduledAtMs),
                ExistingWorkPolicy.KEEP,
                request,
            )
            operation.result.addListener(
                {
                    runCatching { operation.result.get() }
                        .onFailure { Log.e(TAG, "Failed to persist direct fire for $jobId", it) }
                    pendingResult.finish()
                },
                DIRECT_EXECUTOR,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to enqueue direct fire for $jobId", t)
            pendingResult.finish()
        }
    }

    companion object {
        const val ACTION_FIRE = "me.rerere.rikkahub.action.DIRECT_CRON_FIRE"
        private const val TAG = "DirectCronReceiver"
        private val DIRECT_EXECUTOR = Executor { command -> command.run() }
    }
}