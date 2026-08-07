package me.rerere.rikkahub.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.repository.ScheduledJobRepository
import me.rerere.rikkahub.data.repository.ScheduledJobRunRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Re-schedules every enabled cron job after device reboot or app upgrade. Also:
 *  - applies catchup policy for windows missed during downtime
 *  - flips stranded run rows (started_at_ms in the past with no finished_at_ms) to
 *    'process_killed_replay' so get_job_history shows the truth
 */
class CronBootReceiver : BroadcastReceiver(), KoinComponent {

    private val scheduler: CronJobScheduler by inject()
    private val repo: ScheduledJobRepository by inject()
    private val runRepo: ScheduledJobRunRepository by inject()
    private val telegramPrefs: me.rerere.rikkahub.data.telegram.TelegramBotPreferences by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val isBootLike = action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        val isClockChange = action == Intent.ACTION_TIME_CHANGED ||
            action == Intent.ACTION_TIMEZONE_CHANGED
        val isPermissionChange =
            action == "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
        if (!isBootLike && !isClockChange && !isPermissionChange) return
        val pending = goAsync()
        scope.launch {
            try {
                if (isBootLike) sweepStrandedRunRows(context)
                if (isPermissionChange) {
                    // Exact-alarm permission was granted or revoked. Re-schedule all enabled
                    // jobs so they auto-promote to exact backend (or demote to fallback).
                    // This is the auto-promotion path: after the user grants Alarms &
                    // reminders access, every job switches from flexible WorkManager to
                    // setAlarmClock (direct) or setExactAndAllowWhileIdle (llm).
                    scheduler.scheduleAllEnabled()
                } else if (isClockChange) {
                    // Wall-clock schedules must be recomputed after timezone/manual clock
                    // changes. scheduleAllEnabled re-selects the backend per mode.
                    scheduler.scheduleAllEnabled()
                } else {
                    scheduler.reconcileAllEnabled()
                }

                // Re-start Telegram bot if it was enabled (existing behavior) AND
                // re-arm the periodic health probe. Both are idempotent — the probe uses
                // ExistingPeriodicWorkPolicy.KEEP so re-arming on every boot is harmless.
                if (isBootLike) {
                    val cfg = try { telegramPrefs.current() } catch (_: Throwable) { null }
                    if (cfg != null && cfg.isUsable) {
                        runCatching { me.rerere.rikkahub.service.TelegramBotService.start(context) }
                        me.rerere.rikkahub.service.TelegramBotHealthWorker.schedule(context)
                    }

                    // Re-arm the web server health probe on boot.
                    me.rerere.rikkahub.service.WebServerHealthWorker.schedule(context)

                    // Phase 12 — fire any boot-completed workflows. The dispatcher reads the
                    // boot trigger family which has been bound to current matching workflows
                    // by TriggerRegistry.start().
                    runCatching {
                        me.rerere.rikkahub.workflow.trigger.WorkflowBootDispatcher.onBoot()
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun sweepStrandedRunRows(context: Context) {
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
        postFailureNotification(context, title, text)
    }

    private fun postFailureNotification(ctx: Context, title: String, text: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (nm.getNotificationChannel(CronJobWorker.CHANNEL_ID) == null) {
            nm.createNotificationChannel(android.app.NotificationChannel(
                CronJobWorker.CHANNEL_ID, "Scheduled jobs",
                android.app.NotificationManager.IMPORTANCE_DEFAULT))
        }
        val builder = androidx.core.app.NotificationCompat.Builder(ctx, CronJobWorker.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
        try {
            // Fixed notification ID so subsequent boots replace the prior aggregate
            // notification rather than stacking up.
            val aggregateNotifId = Int.MAX_VALUE - 100
            androidx.core.app.NotificationManagerCompat.from(ctx).notify(aggregateNotifId, builder.build())
        } catch (_: SecurityException) { /* POST_NOTIFICATIONS not granted — fine */ }
    }

}
