package me.rerere.rikkahub.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.repository.ScheduledJobRepository
import me.rerere.rikkahub.data.repository.ScheduledJobRunRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private const val TAG = "CronReconcileWorker"

/**
 * Durable WorkManager worker that performs cron reconciliation after boot / time change /
 * exact-alarm permission change. Replaces the old pattern of doing unbounded Room work
 * inside [CronBootReceiver].goAsync(): a broadcast's ~10s budget (cold process, Koin init)
 * is far too tight for sweeping stranded rows + catchup planning + re-arming every job.
 *
 * Semantics preserved from the original receiver:
 *  - boot-like  → sweep stranded run rows + [CronJobScheduler.reconcileAllEnabled] +
 *                 Telegram/Web health + [me.rerere.rikkahub.workflow.trigger.WorkflowBootDispatcher.onBoot]
 *  - time       → [CronJobScheduler.scheduleAllEnabled] (recompute wall-clock schedules)
 *  - permission → [CronJobScheduler.scheduleAllEnabled] (auto-promote/demote exact backend)
 *
 * The worker promotes itself to foreground (specialUse — the manifest already declares the
 * WorkManager SystemForegroundService with that type) because reconciliation over many jobs
 * can exceed WorkManager's ~10-minute execution limit. Per-job failures are isolated by the
 * scheduler and reported back so this worker can [Result.retry] idempotently instead of the
 * failure being silently swallowed.
 */
class CronReconcileWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    private val scheduler: CronJobScheduler by inject()
    private val repo: ScheduledJobRepository by inject()
    private val runRepo: ScheduledJobRunRepository by inject()
    private val telegramPrefs: me.rerere.rikkahub.data.telegram.TelegramBotPreferences by inject()
    private val settingsStore: me.rerere.rikkahub.data.datastore.SettingsStore by inject()

    override suspend fun getForegroundInfo(): ForegroundInfo = createReconcileForegroundInfo()

    override suspend fun doWork(): Result {
        val kind = inputData.getString(KEY_KIND) ?: KIND_BOOT
        // Every reconcile pass re-asserts the daily keep-alive alarm (no-op if armed).
        CronDailyKeepAliveReceiver.armIfAbsent(applicationContext)
        try {
            setForeground(createReconcileForegroundInfo())
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            // Reconcile can still finish under normal WorkManager limits when an OEM
            // rejects foreground promotion.
            Log.w(TAG, "Unable to promote cron reconciliation to foreground", t)
        }

        var retry = false

        if (kind == KIND_BOOT) {
            try {
                sweepStrandedRunRows()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.e(TAG, "stranded-row sweep failed", t)
                retry = true
            }
        }

        val failures = try {
            when (kind) {
                KIND_TIME, KIND_PERMISSION -> scheduler.scheduleAllEnabled()
                else -> scheduler.reconcileAllEnabled()
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "scheduler reconciliation threw", t)
            listOf(t.message?.take(200) ?: "scheduler reconciliation threw")
        }
        if (failures.isNotEmpty()) {
            Log.w(TAG, "cron reconciliation failed for ${failures.size} job(s): $failures")
            retry = true
        }

        if (kind == KIND_BOOT) {
            // Telegram/Web health + boot workflows are idempotent and re-run on the next
            // boot; failures here are logged, not retried (avoid retry storms). Cancellation
            // must propagate — a cancelled worker has to be replayable, never marked done.
            try {
                // telegramPrefs.current() is suspend; a worker cancellation while awaiting
                // it must not be eaten by the surrounding runCatching-style error handling.
                val cfg = try {
                    telegramPrefs.current()
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    null
                }
                if (cfg != null && cfg.isUsable) {
                    runCatching { TelegramBotService.start(applicationContext) }
                    TelegramBotHealthWorker.schedule(applicationContext)
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.e(TAG, "telegram boot restart failed", t)
            }
            runCatching { WebServerHealthWorker.schedule(applicationContext) }
                .onFailure { Log.e(TAG, "web health schedule failed", it) }
            // Keep-alive FGS: if the user opted in, bring it back on boot / package
            // replace (both map to KIND_BOOT). START_STICKY cannot cross a reboot, so
            // this is its only boot-time revive path.
            runCatching {
                if (settingsStore.settingsFlowRaw.first().keepaliveEnabled) {
                    AgentKeepaliveService.start(applicationContext)
                }
            }.onFailure { Log.e(TAG, "keepalive boot restart failed", it) }
            runCatching { me.rerere.rikkahub.workflow.trigger.WorkflowBootDispatcher.onBoot() }
                .onFailure { Log.e(TAG, "workflow boot dispatch failed", it) }
        }

        return if (retry) Result.retry() else Result.success()
    }

    private suspend fun sweepStrandedRunRows() {
        val cutoff = System.currentTimeMillis() - 30L * 60_000L
        val stranded = runRepo.getStranded(stalenessMs = cutoff)
        if (stranded.isEmpty()) return

        for (row in stranded) {
            runRepo.update(row.copy(
                finishedAtMs = System.currentTimeMillis(),
                outcome = "process_killed_replay",
                errorMessage = "worker terminated mid-execute",
            ))
        }

        // Single aggregate notification per boot rather than one per stranded row.
        val jobNames = stranded
            .mapNotNull { row -> repo.getById(row.jobId)?.name }
            .distinct()
        val title = if (stranded.size == 1) "Scheduled job interrupted"
                    else "${stranded.size} scheduled jobs interrupted"
        val text = if (jobNames.size <= 3) jobNames.joinToString(", ")
                   else jobNames.take(3).joinToString(", ") + ", and ${jobNames.size - 3} others"
        postFailureNotification(title, text)
    }

    private fun postFailureNotification(title: String, text: String) {
        val ctx = applicationContext
        ensureScheduledJobsHighChannel(ctx)
        val builder = NotificationCompat.Builder(ctx, SCHEDULED_JOBS_HIGH_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVibrate(SCHEDULED_JOB_VIBRATE_PATTERN)
            .setAutoCancel(true)
        try {
            // Fixed notification ID so subsequent boots replace the prior aggregate
            // notification rather than stacking up.
            val aggregateNotifId = Int.MAX_VALUE - 100
            NotificationManagerCompat.from(ctx).notify(aggregateNotifId, builder.build())
        } catch (_: SecurityException) { /* POST_NOTIFICATIONS not granted — fine */ }
    }

    private fun createReconcileForegroundInfo(): ForegroundInfo {
        val ctx = applicationContext
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(RECONCILE_CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(
                RECONCILE_CHANNEL_ID,
                "Cron reconciliation",
                NotificationManager.IMPORTANCE_LOW,
            ))
        }
        val notification = NotificationCompat.Builder(ctx, RECONCILE_CHANNEL_ID)
            .setContentTitle(ctx.getString(R.string.scheduled_job_reconcile_notification_title))
            .setContentText(ctx.getString(R.string.scheduled_job_reconcile_notification_body))
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(
                RECONCILE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            ForegroundInfo(RECONCILE_NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val KEY_KIND = "cron_reconcile_kind"
        const val KIND_BOOT = "boot"
        const val KIND_TIME = "time"
        const val KIND_PERMISSION = "permission"

        /**
         * Periodic self-healing reconcile (Chrono pattern): WorkManager's correct role is
         * the fallback that periodically re-arms every job's exact alarm — not just a DB
         * check. 60 minutes satisfies the ≤60min target and stays well inside
         * WorkManager's 15-minute minimum period.
         */
        const val KIND_PERIODIC = "periodic"
        private const val PERIODIC_WORK_NAME = "cron_reconcile_periodic"
        private const val PERIODIC_INTERVAL_HOURS = 1L

        /** Unique name for the widget/goAsync-timeout fallback reconcile. */
        private const val ONE_TIME_FALLBACK_WORK_NAME = "cron_reconcile_one_time_fallback"

        private const val RECONCILE_CHANNEL_ID = "rikkahub_cron_reconcile"
        // Distinct from CronJobWorker's 0x50000000-prefixed execution notification IDs.
        private const val RECONCILE_NOTIFICATION_ID = 0x005EC0DE

        /**
         * Ensures the hourly self-healing reconcile is enqueued. Called from
         * [CronBootReceiver] on every boot-like event; UPDATE keeps the request fresh
         * without stacking duplicates (unique-work identity).
         */
        fun schedulePeriodic(context: Context) {
            runCatching {
                val req = PeriodicWorkRequestBuilder<CronReconcileWorker>(
                    PERIODIC_INTERVAL_HOURS, TimeUnit.HOURS,
                )
                    .setInputData(Data.Builder().putString(KEY_KIND, KIND_PERIODIC).build())
                    .build()
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    PERIODIC_WORK_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    req,
                )
            }
        }

        /**
         * Enqueue a durable one-shot reconcile of [kind] — the escape hatch for callers
         * whose execution window may expire mid-sweep (e.g. the widget's goAsync budget in
         * [me.rerere.rikkahub.widget.AgentWidgetProvider]). APPEND_OR_REPLACE keeps an
         * already-queued fallback while still guaranteeing the pass eventually runs; the
         * work is expedited with a graceful degrade on quota exhaustion. Fire-and-forget:
         * never throws, matching [schedulePeriodic].
         */
        fun enqueueOneTime(context: Context, kind: String) {
            runCatching {
                val req = OneTimeWorkRequestBuilder<CronReconcileWorker>()
                    .setInputData(Data.Builder().putString(KEY_KIND, kind).build())
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    ONE_TIME_FALLBACK_WORK_NAME,
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    req,
                )
            }
        }
    }
}
