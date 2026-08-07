package me.rerere.rikkahub.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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
 * Schedules cron jobs using an automatically-selected backend based on job.mode:
 *
 * - mode='direct' → AlarmManager.setAlarmClock (user-visible alarm, precise) +
 *   DirectCronAlarmReceiver → immediate expedited worker.
 * - mode='llm' → AlarmManager.setExactAndAllowWhileIdle + ExactCronAlarmReceiver →
 *   expedited worker (durable, for long-running LLM turns).
 *
 * When exact-alarm permission is unavailable (Android 12+ without SCHEDULE_EXACT_ALARM
 * granted), both modes safely fall back to battery-friendly WorkManager with flexible
 * timing. Granting the permission (see SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
 * handling in CronBootReceiver) auto-promotes all jobs to their correct exact backend.
 *
 * Each job has a stable work/alarm identity so pending runs can be replaced, reconciled,
 * or cancelled deterministically. Mode-specific PendingIntents (direct vs llm) ensure
 * that switching modes or cancelling a job never leaves a stale alarm from the other
 * path. Recurring jobs re-schedule themselves at the end of CronJobWorker.doWork().
 * Boot recovery happens through [CronBootReceiver] and visible app-launch reconciliation.
 */
class CronJobScheduler(
    private val context: Context,
    private val repo: ScheduledJobRepository,
    private val runRepo: ScheduledJobRunRepository,
) {
    private val wm get() = WorkManager.getInstance(context)
    private val alarmManager get() = context.getSystemService(AlarmManager::class.java)

    enum class Backend {
        NONE,
        WORK_MANAGER,
        ALARM_CLOCK_DIRECT,
        EXACT_ALARM_LLM,
        WORK_MANAGER_FALLBACK,
    }

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

        val exactAlarmGranted = canScheduleExactAlarms()
        val desiredBackend = selectBackend(job.mode, exactAlarmGranted)

        // Clear any alarms armed by a previous backend/mode. This is critical when mode
        // changes (direct↔llm) or when permission state changes (exact→fallback). Both
        // mode-specific PendingIntents are cancelled so no stale alarm survives.
        cancelAllAlarms(job.id)

        val effectiveBackend = when (desiredBackend) {
            Backend.ALARM_CLOCK_DIRECT, Backend.EXACT_ALARM_LLM -> {
                // Arm the exact alarm. A SecurityException race is possible: the user can
                // revoke SCHEDULE_EXACT_ALARM between canScheduleExactAlarms() and the
                // actual set call. tryArmExactAlarm catches it and returns false; we then
                // fall back to flexible WorkManager and report WORK_MANAGER_FALLBACK.
                val armed = tryArmExactAlarm(job.id, nextRun, desiredBackend)
                val resolved = resolveBackendAfterArm(desiredBackend, armed)
                if (armed) {
                    // Cancel any existing WorkManager fallback so the exact alarm and WM
                    // don't both fire (double-trigger after permission grant / reconcile).
                    wm.cancelUniqueWork(workNameFor(job.id))
                } else {
                    // SecurityException — fall back to flexible WorkManager.
                    enqueueFlexible(job.id, nextRun, nowMs)
                }
                resolved
            }
            Backend.WORK_MANAGER_FALLBACK, Backend.WORK_MANAGER -> {
                // No exact-alarm permission (Android 12+) or unknown mode — fall back to
                // battery-friendly WorkManager with flexible timing.
                enqueueFlexible(job.id, nextRun, nowMs)
                desiredBackend
            }
            Backend.NONE -> desiredBackend // handled above, but exhaustiveness
        }

        return ScheduleResult(effectiveBackend, nextRun)
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
        cancelAllAlarms(jobId)
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
        // Sweep stale alarms from any previous backend/mode before re-arming.
        cancelAllAlarms(job.id)
        val exactAlarmGranted = canScheduleExactAlarms()
        val desiredBackend = selectBackend(job.mode, exactAlarmGranted)
        when (desiredBackend) {
            Backend.ALARM_CLOCK_DIRECT, Backend.EXACT_ALARM_LLM -> {
                // Cancel any existing WorkManager fallback to prevent double-trigger after
                // boot/reconcile: if the job was previously in fallback mode (WM armed) and
                // is now promoted to exact, the old WM work would still fire alongside the
                // new alarm. Cancelling here ensures only the exact alarm is active.
                wm.cancelUniqueWork(workNameFor(job.id))
                if (!tryArmExactAlarm(job.id, scheduledAtMs, desiredBackend)) {
                    // SecurityException — permission revoked between canScheduleExactAlarms
                    // and the actual set call. Re-enqueue flexible fallback. The WM work
                    // was just cancelled above, so enqueueing is safe (no duplicate).
                    enqueueFlexible(job.id, scheduledAtMs, nowMs)
                }
            }
            else -> {
                // Fallback path: only enqueue if WorkManager isn't already active for this
                // job (avoids replacing a healthy running work with a duplicate).
                val active = wm.getWorkInfosForUniqueWorkFlow(workNameFor(job.id))
                    .first()
                    .any { it.state == WorkInfo.State.ENQUEUED ||
                        it.state == WorkInfo.State.BLOCKED ||
                        it.state == WorkInfo.State.RUNNING }
                if (!active) enqueueFlexible(job.id, scheduledAtMs, nowMs)
            }
        }
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

    fun canScheduleExactAlarms(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return alarmManager.canScheduleExactAlarms()
    }

    /**
     * Attempts to arm an exact alarm for the given [backend]. Returns true on success,
     * false if a [SecurityException] was caught — this happens when the user revokes
     * SCHEDULE_EXACT_ALARM between [canScheduleExactAlarms] and the actual set call.
     * On failure, any partially-armed alarm is cleaned up via [cancelAllAlarms].
     *
     * Structured so the SecurityException-prone Android calls are isolated in one place;
     * the fallback decision is delegated to the pure [resolveBackendAfterArm] which is
     * JVM-testable without an AlarmManager.
     */
    private fun tryArmExactAlarm(jobId: String, scheduledAtMs: Long, backend: Backend): Boolean {
        return try {
            when (backend) {
                Backend.ALARM_CLOCK_DIRECT -> scheduleAlarmClockDirect(jobId, scheduledAtMs)
                Backend.EXACT_ALARM_LLM -> scheduleExactAlarmLlm(jobId, scheduledAtMs)
                else -> return false
            }
            true
        } catch (_: SecurityException) {
            // Permission revoked in the race window. Clean up any partially-armed alarm
            // so it doesn't fire unexpectedly later.
            cancelAllAlarms(jobId)
            false
        }
    }

    // ---- Direct mode: setAlarmClock (user-visible alarm) ----

    private fun scheduleAlarmClockDirect(jobId: String, scheduledAtMs: Long) {
        // showIntent uses a constant requestCode (0) because every job's showIntent is
        // identical — it just opens the app launcher. Using jobId.hashCode() as the
        // requestCode risked PendingIntent identity collisions (String.hashCode can collide
        // for distinct UUIDs), which would cause FLAG_UPDATE_CURRENT to replace one job's
        // showIntent with another's. Since all showIntents are the same Activity + action,
        // sharing one PendingIntent instance is correct and avoids the collision entirely.
        val showIntent = PendingIntent.getActivity(
            context,
            SHOW_INTENT_REQUEST_CODE,
            Intent(context, me.rerere.rikkahub.RouteActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val triggerPI = directAlarmPendingIntent(jobId, scheduledAtMs)
        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(scheduledAtMs, showIntent),
            triggerPI,
        )
    }

    private fun directAlarmPendingIntent(jobId: String, scheduledAtMs: Long): PendingIntent {
        val intent = Intent(context, DirectCronAlarmReceiver::class.java)
            .setAction(DirectCronAlarmReceiver.ACTION_FIRE)
            .setData(Uri.parse("rikkahub://cron-direct/$jobId"))
            .putExtra(CronJobWorker.KEY_JOB_ID, jobId)
            .putExtra(CronJobWorker.KEY_SCHEDULED_AT_MS, scheduledAtMs)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // ---- LLM mode: setExactAndAllowWhileIdle ----

    private fun scheduleExactAlarmLlm(jobId: String, scheduledAtMs: Long) {
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            scheduledAtMs,
            llmAlarmPendingIntent(jobId, scheduledAtMs),
        )
    }

    private fun llmAlarmPendingIntent(jobId: String, scheduledAtMs: Long): PendingIntent {
        val intent = Intent(context, ExactCronAlarmReceiver::class.java)
            .setAction(ExactCronAlarmReceiver.ACTION_FIRE)
            .setData(Uri.parse("rikkahub://cron-llm/$jobId"))
            .putExtra(CronJobWorker.KEY_JOB_ID, jobId)
            .putExtra(CronJobWorker.KEY_SCHEDULED_AT_MS, scheduledAtMs)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Cancel both mode-specific alarms. Called on schedule/cancel/mode-change. */
    private fun cancelAllAlarms(jobId: String) {
        val directPI = directAlarmPendingIntent(jobId, 0L)
        alarmManager.cancel(directPI)
        directPI.cancel()
        val llmPI = llmAlarmPendingIntent(jobId, 0L)
        alarmManager.cancel(llmPI)
        llmPI.cancel()
    }

    private fun workNameFor(jobId: String) = "cron_job_$jobId"
    private fun manualWorkNameFor(jobId: String) = "cron_job_${jobId}_manual"
    private fun catchupWorkNameFor(jobId: String) = "cron_job_${jobId}_catchup"

    companion object {
        // Retained for compatibility with legacy rows and external references. The
        // schedulePrecision column still exists (no Room migration), but it no longer
        // selects a backend — job.mode does.
        const val PRECISION_FLEXIBLE = "flexible"
        const val PRECISION_EXACT = "exact"

        internal fun workTagFor(jobId: String) = "cron_job_tag_$jobId"

        // Still used by the legacy ExactCronAlarmReceiver path so a stale alarm armed by a
        // previous app version degrades into durable WorkManager work instead of being lost.
        internal fun exactExecutionWorkName(jobId: String, scheduledAtMs: Long) =
            "cron_job_${jobId}_exact_$scheduledAtMs"

        internal fun directExecutionWorkName(jobId: String, scheduledAtMs: Long) =
            "cron_job_${jobId}_direct_$scheduledAtMs"

        /** Constant requestCode for all setAlarmClock showIntents (all identical — open app). */
        internal const val SHOW_INTENT_REQUEST_CODE = 0

        /**
         * Pure function: given the desired backend and whether the exact alarm arm
         * succeeded, returns the effective backend. Used by [schedule] after
         * [tryArmExactAlarm] to decide the final backend. Extracted as a companion
         * pure function so JVM unit tests can verify the fallback decision without
         * needing an Android AlarmManager instance.
         */
        internal fun resolveBackendAfterArm(
            desiredBackend: Backend,
            armSucceeded: Boolean,
        ): Backend = if (armSucceeded) desiredBackend else Backend.WORK_MANAGER_FALLBACK

        /**
         * Auto-selects the scheduling backend based on [mode] and the exact-alarm permission
         * state. User precision selection is NOT exposed — the backend is automatic:
         *
         * - "direct" + permission → [Backend.ALARM_CLOCK_DIRECT] (setAlarmClock, user-visible)
         * - "llm" + permission → [Backend.EXACT_ALARM_LLM] (setExactAndAllowWhileIdle)
         * - no permission (Android 12+) → [Backend.WORK_MANAGER_FALLBACK] (flexible)
         *
         * Legacy [schedulePrecision] values are ignored — the column remains for DB
         * compatibility but never influences backend choice.
         */
        internal fun selectBackend(
            mode: String,
            exactAlarmGranted: Boolean,
        ): Backend {
            if (!exactAlarmGranted) return Backend.WORK_MANAGER_FALLBACK
            return when (mode) {
                "direct" -> Backend.ALARM_CLOCK_DIRECT
                "llm" -> Backend.EXACT_ALARM_LLM
                else -> Backend.WORK_MANAGER_FALLBACK
            }
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
