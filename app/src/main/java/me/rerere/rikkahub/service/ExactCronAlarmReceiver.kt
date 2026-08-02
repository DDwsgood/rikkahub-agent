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
 * Turns an AlarmManager wake-up into durable WorkManager work.
 *
 * The receiver deliberately does not execute tools, access Room, or start an agent. It only
 * persists a slot-scoped worker and keeps the broadcast pending until WorkManager has committed
 * the enqueue operation. Exact work is expedited because the user explicitly requested this
 * slot to start on time; quota exhaustion gracefully falls back to normal work.
 */
class ExactCronAlarmReceiver : BroadcastReceiver() {
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
                CronJobScheduler.exactExecutionWorkName(jobId, scheduledAtMs),
                ExistingWorkPolicy.KEEP,
                request,
            )
            operation.result.addListener(
                {
                    runCatching { operation.result.get() }
                        .onFailure { Log.e(TAG, "Failed to persist exact fire for $jobId", it) }
                    pendingResult.finish()
                },
                DIRECT_EXECUTOR,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to enqueue exact fire for $jobId", t)
            pendingResult.finish()
        }
    }

    companion object {
        const val ACTION_FIRE = "me.rerere.rikkahub.action.EXACT_CRON_FIRE"
        private const val TAG = "ExactCronReceiver"
        private val DIRECT_EXECUTOR = Executor { command -> command.run() }
    }
}
