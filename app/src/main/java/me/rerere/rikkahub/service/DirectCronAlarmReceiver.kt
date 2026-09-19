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
 * has committed the enqueue. If the enqueue fails (synchronously or the Operation
 * completes with failure), a durable short-delay retry alarm reuses the same jobId + slot
 * identity ([CronAlarmRetry]) instead of silently dropping the fire. Once the slot worker
 * IS durably persisted, the exact-backend safety backup for this slot is cancelled so it
 * never fires a duplicate. `pendingResult.finish()` runs on every path.
 */
class DirectCronAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val jobId = intent.getStringExtra(CronJobWorker.KEY_JOB_ID) ?: return
        val scheduledAtMs = intent.getLongExtra(CronJobWorker.KEY_SCHEDULED_AT_MS, -1L)
        if (scheduledAtMs <= 0L) return
        val attempt = intent.getIntExtra(CronAlarmRetry.KEY_RETRY_ATTEMPT, 0)
        // Display extras armed by the scheduler — carried into the worker so notification
        // building never depends on Room being ready in a cold-started process.
        val jobName = intent.getStringExtra(CronJobWorker.KEY_JOB_NAME)
        val jobMode = intent.getStringExtra(CronJobWorker.KEY_JOB_MODE)

        val request = OneTimeWorkRequestBuilder<CronJobWorker>()
            .setInputData(
                Data.Builder()
                    .putString(CronJobWorker.KEY_JOB_ID, jobId)
                    .putString(CronJobWorker.KEY_JOB_NAME, jobName)
                    .putString(CronJobWorker.KEY_JOB_MODE, jobMode)
                    .putLong(CronJobWorker.KEY_SCHEDULED_AT_MS, scheduledAtMs)
                    .build()
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(CronJobScheduler.workTagFor(jobId))
            .build()

        val pendingResult = goAsync()
        val operation = try {
            WorkManager.getInstance(context).enqueueUniqueWork(
                CronJobScheduler.directExecutionWorkName(jobId, scheduledAtMs),
                ExistingWorkPolicy.KEEP,
                request,
            )
        } catch (t: Throwable) {
            // A synchronous enqueue failure must not silently drop the fire — arm a
            // durable short-delay retry that reuses the same jobId + slot identity.
            Log.e(TAG, "Unable to enqueue direct fire for $jobId", t)
            CronAlarmRetry.arm(
                context, DirectCronAlarmReceiver::class.java, ACTION_FIRE,
                jobId, scheduledAtMs, attempt, jobName, jobMode,
            )
            pendingResult.finish()
            return
        }
        operation.result.addListener(
            {
                val ok = runCatching { operation.result.get() }.isSuccess
                if (!ok) {
                    Log.e(TAG, "Failed to persist direct fire for $jobId")
                    CronAlarmRetry.arm(
                        context, DirectCronAlarmReceiver::class.java, ACTION_FIRE,
                        jobId, scheduledAtMs, attempt, jobName, jobMode,
                    )
                    pendingResult.finish()
                } else {
                    // The slot's durable worker is persisted — the safety backup for this
                    // slot is now redundant. Wait for the backup-cancel Operation's
                    // terminal state before finishing the broadcast (listeners run on
                    // DIRECT_EXECUTOR, so no thread is ever blocked) so a backup that
                    // would double-execute the slot is durably cancelled. A failed cancel
                    // is only logged: the worker's at-most-once guard
                    // (shouldSuppressBackupFire) is the backstop.
                    val cancelOp = try {
                        WorkManager.getInstance(context)
                            .cancelUniqueWork(CronJobScheduler.backupWorkNameFor(jobId, scheduledAtMs))
                    } catch (t: Throwable) {
                        Log.w(TAG, "Unable to cancel slot backup for $jobId", t)
                        null
                    }
                    if (cancelOp == null) {
                        pendingResult.finish()
                    } else {
                        cancelOp.result.addListener(
                            {
                                if (runCatching { cancelOp.result.get() }.isFailure) {
                                    Log.w(TAG, "Unable to cancel slot backup for $jobId")
                                }
                                pendingResult.finish()
                            },
                            DIRECT_EXECUTOR,
                        )
                    }
                }
            },
            DIRECT_EXECUTOR,
        )
    }

    companion object {
        const val ACTION_FIRE = "me.rerere.rikkahub.action.DIRECT_CRON_FIRE"
        private const val TAG = "DirectCronReceiver"
        private val DIRECT_EXECUTOR = Executor { command -> command.run() }
    }
}
