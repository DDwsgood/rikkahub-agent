package me.rerere.rikkahub.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity
import me.rerere.rikkahub.data.db.entity.ScheduledJobRunEntity
import me.rerere.rikkahub.data.repository.ScheduledJobRepository
import me.rerere.rikkahub.data.repository.ScheduledJobRunRepository
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.uuid.Uuid

/**
 * Schedules cron jobs through battery-friendly WorkManager or opt-in exact AlarmManager
 * delivery. Each job has stable alarm/work identities so pending runs can be replaced,
 * reconciled, or cancelled deterministically.
 *
 * Recurring jobs re-schedule themselves at the end of CronJobWorker.doWork(). Boot
 * recovery happens through [CronBootReceiver] and visible app-launch reconciliation.
 */
class CronJobScheduler(
    private val context: Context,
    private val repo: ScheduledJobRepository,
    private val runRepo: ScheduledJobRunRepository,
) {
    private val wm get() = WorkManager.getInstance(context)
    private val alarmManager get() = context.getSystemService(AlarmManager::class.java)

    enum class Backend { NONE, WORK_MANAGER, EXACT_ALARM, WORK_MANAGER_FALLBACK }

    data class ScheduleResult(
        val backend: Backend,
        val nextRunAtMs: Long?,
    )

    suspend fun schedule(job: ScheduledJobEntity): ScheduleResult {
        val nowMs = System.currentTimeMillis()
        val nextRun = nextRunMs(job, nowMs)
        repo.update(job.copy(nextRunAtMs = nextRun))
        if (nextRun == null) {
            cancel(job.id)
            return ScheduleResult(Backend.NONE, null)
        }

        val exactAlarmGranted = job.schedulePrecision == PRECISION_EXACT && canScheduleExactAlarms()
        val selectedBackend = selectBackend(job.schedulePrecision, exactAlarmGranted)
        return if (selectedBackend == Backend.EXACT_ALARM) {
            try {
                scheduleExact(job.id, nextRun)
                // If this job previously used the flexible backend, remove only that pending
                // regular schedule. Exact fires use slot-scoped work names, so this never
                // cancels a currently executing exact worker.
                wm.cancelUniqueWork(workNameFor(job.id))
                ScheduleResult(Backend.EXACT_ALARM, nextRun)
            } catch (_: SecurityException) {
                cancelExact(job.id)
                enqueueFlexible(job.id, nextRun, nowMs)
                ScheduleResult(Backend.WORK_MANAGER_FALLBACK, nextRun)
            }
        } else {
            cancelExact(job.id)
            enqueueFlexible(job.id, nextRun, nowMs)
            ScheduleResult(selectedBackend, nextRun)
        }
    }

    private fun enqueueFlexible(jobId: String, scheduledAtMs: Long, nowMs: Long) {
        val delayMs = max(0L, scheduledAtMs - nowMs)
        val req = OneTimeWorkRequestBuilder<CronJobWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder()
                .putString(CronJobWorker.KEY_JOB_ID, jobId)
                .putLong(CronJobWorker.KEY_SCHEDULED_AT_MS, scheduledAtMs)
                .build())
            .addTag(workTagFor(jobId))
            .build()
        wm.enqueueUniqueWork(workNameFor(jobId), ExistingWorkPolicy.REPLACE, req)
    }

    /**
     * Trigger a manual fire (trigger_job_now). Distinct work name from the regular schedule
     * + sets KEY_MANUAL=true so the worker skips lastRunAtMs / runs_so_far bumps. Manual
     * fires are bonus — they don't disturb the regular schedule's accounting.
     */
    suspend fun triggerNow(jobId: String) {
        val req = OneTimeWorkRequestBuilder<CronJobWorker>()
            .setInitialDelay(0L, TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder()
                .putString(CronJobWorker.KEY_JOB_ID, jobId)
                .putBoolean(CronJobWorker.KEY_MANUAL, true)
                .build())
            .addTag(workTagFor(jobId))
            .build()
        wm.enqueueUniqueWork(manualWorkNameFor(jobId), ExistingWorkPolicy.REPLACE, req)
    }

    fun cancel(jobId: String) {
        wm.cancelUniqueWork(workNameFor(jobId))
        wm.cancelUniqueWork(manualWorkNameFor(jobId))
        wm.cancelUniqueWork(catchupWorkNameFor(jobId))
        wm.cancelAllWorkByTag(workTagFor(jobId))
        cancelExact(jobId)
    }

    suspend fun scheduleAllEnabled() {
        repo.getEnabled().forEach { schedule(it) }
    }

    /**
     * Heal schedules from the Room source of truth without replacing healthy future work.
     * Used after boot and after an explicit user launch (the only way to leave force-stop).
     */
    suspend fun reconcileAllEnabled() {
        val nowMs = System.currentTimeMillis()
        for (job in repo.getEnabled()) {
            val persistedNext = job.nextRunAtMs
            if (persistedNext != null && persistedNext > nowMs) {
                rearmFutureIfNeeded(job, persistedNext, nowMs)
                continue
            }

            val plan = CatchupPlanner.plan(job, lastRunMs = job.lastRunAtMs, nowMs = nowMs)
            enqueueCatchupChain(job, plan)
            recordSkippedCatchups(job, plan, nowMs)

            // A missed one-shot is represented by the catchup worker itself. Re-arming the
            // original past RTC alarm would create a duplicate immediate delivery.
            if (job.scheduleType == "once" && plan.fireSlotsMs.isNotEmpty()) {
                repo.update(job.copy(nextRunAtMs = plan.fireSlotsMs.last()))
            } else {
                schedule(job)
            }
        }
    }

    private suspend fun rearmFutureIfNeeded(
        job: ScheduledJobEntity,
        scheduledAtMs: Long,
        nowMs: Long,
    ) {
        if (job.schedulePrecision == PRECISION_EXACT && canScheduleExactAlarms()) {
            try {
                scheduleExact(job.id, scheduledAtMs)
                wm.cancelUniqueWork(workNameFor(job.id))
                return
            } catch (_: SecurityException) {
                // Fall through to the persisted WorkManager fallback.
            }
        }

        cancelExact(job.id)
        val active = wm.getWorkInfosForUniqueWorkFlow(workNameFor(job.id))
            .first()
            .any { it.state == WorkInfo.State.ENQUEUED ||
                it.state == WorkInfo.State.BLOCKED ||
                it.state == WorkInfo.State.RUNNING }
        if (!active) enqueueFlexible(job.id, scheduledAtMs, nowMs)
    }

    private fun enqueueCatchupChain(job: ScheduledJobEntity, plan: CatchupPlanner.CatchupPlan) {
        val requests = plan.fireDelaysMs.mapIndexed { index, delayMs ->
            OneTimeWorkRequestBuilder<CronJobWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setInputData(Data.Builder()
                    .putString(CronJobWorker.KEY_JOB_ID, job.id)
                    .putLong(CronJobWorker.KEY_SCHEDULED_AT_MS, plan.fireSlotsMs[index])
                    .build())
                .addTag(workTagFor(job.id))
                .build()
        }
        if (requests.isEmpty()) return

        var chain = wm.beginUniqueWork(
            catchupWorkNameFor(job.id),
            ExistingWorkPolicy.REPLACE,
            requests.first(),
        )
        requests.drop(1).forEach { chain = chain.then(it) }
        chain.enqueue()
    }

    private suspend fun recordSkippedCatchups(
        job: ScheduledJobEntity,
        plan: CatchupPlanner.CatchupPlan,
        nowMs: Long,
    ) {
        // History is capped at 100 rows per job. Avoid doing thousands of writes after a
        // long offline period only to trim them immediately.
        repeat(plan.skippedCatchupCount.coerceAtMost(100)) {
            runRepo.insert(ScheduledJobRunEntity(
                id = Uuid.random().toString(),
                jobId = job.id,
                mode = job.mode,
                scheduledAtMs = nowMs,
                startedAtMs = nowMs,
                finishedAtMs = nowMs,
                outcome = "skipped_catchup",
                conversationId = null,
                errorMessage = null,
            ))
        }
        if (plan.skippedCatchupCount > 0) runRepo.trim(job.id, keep = 100)
    }

    fun canScheduleExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private fun scheduleExact(jobId: String, scheduledAtMs: Long) {
        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                scheduledAtMs,
                exactAlarmPendingIntent(jobId, scheduledAtMs),
            )
        } catch (e: SecurityException) {
            // Permission can be revoked between canScheduleExactAlarms() and this call.
            // The caller's fallback path is selected on the next reconciliation; log the
            // race rather than crashing the scheduling tool or worker.
            Log.w(TAG, "Exact alarm permission disappeared while scheduling $jobId", e)
            throw e
        }
    }

    private fun cancelExact(jobId: String) {
        val pendingIntent = exactAlarmPendingIntent(jobId, scheduledAtMs = null)
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private fun exactAlarmPendingIntent(jobId: String, scheduledAtMs: Long?): PendingIntent {
        val intent = Intent(context, ExactCronAlarmReceiver::class.java)
            .setAction(ExactCronAlarmReceiver.ACTION_FIRE)
            .setData(Uri.parse("rikkahub://scheduled-job/$jobId"))
            .putExtra(CronJobWorker.KEY_JOB_ID, jobId)
        if (scheduledAtMs != null) {
            intent.putExtra(CronJobWorker.KEY_SCHEDULED_AT_MS, scheduledAtMs)
        }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun workNameFor(jobId: String) = "cron_job_$jobId"
    private fun manualWorkNameFor(jobId: String) = "cron_job_${jobId}_manual"
    private fun catchupWorkNameFor(jobId: String) = "cron_job_${jobId}_catchup"

    companion object {
        private const val TAG = "CronJobScheduler"
        const val PRECISION_FLEXIBLE = "flexible"
        const val PRECISION_EXACT = "exact"

        internal fun workTagFor(jobId: String) = "cron_job_tag_$jobId"
        internal fun exactExecutionWorkName(jobId: String, scheduledAtMs: Long) =
            "cron_job_${jobId}_exact_$scheduledAtMs"

        internal fun selectBackend(schedulePrecision: String, exactAlarmGranted: Boolean): Backend =
            when {
                schedulePrecision != PRECISION_EXACT -> Backend.WORK_MANAGER
                exactAlarmGranted -> Backend.EXACT_ALARM
                else -> Backend.WORK_MANAGER_FALLBACK
            }

        /**
         * Compute the next fire time given [nowMs]. Returns null if the job will never
         * fire again (disabled, max_runs reached, end_at past, once already fired).
         *
         * Pure function — no side effects, no Room access. Lives in the companion so
         * tests + the boot receiver can call it without instantiating a scheduler.
         */
        fun nextRunMs(job: ScheduledJobEntity, nowMs: Long): Long? {
            if (!job.enabled) return null
            if (job.maxRuns != null && job.runsSoFar >= job.maxRuns) return null
            if (job.endAtUnixMs != null && nowMs > job.endAtUnixMs) return null

            return when (job.scheduleType) {
                "once" -> {
                    val at = job.atUnixMs ?: return null
                    if (job.lastRunAtMs != null) null else at
                }
                "cron" -> {
                    val expr = job.cronExpression ?: return null
                    val zone = job.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
                    val cron = CronExpressionParser.parse(expr).getOrNull() ?: return null
                    val basisMs = max(nowMs, job.startAtUnixMs ?: 0L) - 1L
                    val basisZdt = Instant.ofEpochMilli(basisMs).atZone(zone)
                    val nextZdt = CronExpressionParser.nextExecution(cron, basisZdt) ?: return null
                    val next = nextZdt.toInstant().toEpochMilli()
                    if (job.endAtUnixMs != null && next > job.endAtUnixMs) null else next
                }
                else -> null
            }
        }
    }
}
