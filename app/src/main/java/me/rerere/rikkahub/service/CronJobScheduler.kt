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
import androidx.work.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity
import me.rerere.rikkahub.data.db.entity.ScheduledJobRunEntity
import me.rerere.rikkahub.data.repository.ScheduledJobRepository
import me.rerere.rikkahub.data.repository.ScheduledJobRunRepository
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.uuid.Uuid

private const val TAG = "CronJobScheduler"

/**
 * Ordered steps of a schedule transition. PINS the "durable before teardown" contract: the
 * durable WorkManager path is always established before any alarm is cancelled, so a
 * failure or process death between steps can never leave a zero-scheduling window.
 * [CronJobScheduler.scheduleLocked] / [CronJobScheduler.rearmFutureIfNeeded] follow this
 * order and the decision is pinned by [CronJobSchedulerTest].
 */
internal enum class ScheduleStep { PERSIST_DURABLE_WM, ARM_EXACT_ALARM, CANCEL_STALE_ALARMS }

/**
 * Which alarms to cancel AFTER the new path is durable. For a successful exact arm only
 * the OTHER mode's alarm is stale (this mode's old alarm entry was replaced by the
 * same-PendingIntent set call); for the fallback path and for a failed exact arm
 * (SecurityException), no alarm should remain.
 */
internal enum class AlarmCleanup { OTHER_MODE_ONLY, BOTH }

internal fun scheduleTransitionSteps(
    desiredBackend: CronJobScheduler.Backend,
    exactArmSucceeded: Boolean,
): List<ScheduleStep> {
    val isExact = desiredBackend == CronJobScheduler.Backend.ALARM_CLOCK_DIRECT ||
        desiredBackend == CronJobScheduler.Backend.EXACT_ALARM_LLM
    return if (isExact) {
        listOf(
            ScheduleStep.PERSIST_DURABLE_WM,
            ScheduleStep.ARM_EXACT_ALARM,
            ScheduleStep.CANCEL_STALE_ALARMS,
        )
    } else {
        listOf(
            ScheduleStep.PERSIST_DURABLE_WM,
            ScheduleStep.CANCEL_STALE_ALARMS,
        )
    }
}

internal fun decideAlarmCleanup(
    desiredBackend: CronJobScheduler.Backend,
    exactArmSucceeded: Boolean,
): AlarmCleanup {
    val isExact = desiredBackend == CronJobScheduler.Backend.ALARM_CLOCK_DIRECT ||
        desiredBackend == CronJobScheduler.Backend.EXACT_ALARM_LLM
    return if (isExact && exactArmSucceeded) AlarmCleanup.OTHER_MODE_ONLY
           else AlarmCleanup.BOTH
}

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
 *
 * Reliability & linearization guarantees:
 *
 * 1. Every enqueue/cancel WorkManager [Operation] is awaited ([androidx.work.await]) so a
 *    re-schedule or cancel is only reported done after WorkManager has persisted it — a
 *    fire-and-forget enqueue can silently lose the next fire after a process kill.
 * 2. All transitions for a single job (schedule/cancel/setEnabled/complete/reconcile) run
 *    under a per-job [Mutex] (see [withJobLock]) so concurrent callers can't interleave —
 *    e.g. a cancel racing an arm leaving both alarms armed, or a stale snapshot
 *    re-enabling a paused job. Internal methods are the `*Locked` variants and never
 *    re-acquire the lock (Mutex is not reentrant).
 * 3. The Room row is the control-plane truth: [scheduleLocked] re-reads the latest row and
 *    only persists `nextRunAtMs`, never overwriting enabled/mode/config with a caller's
 *    stale snapshot.
 * 4. Transition ordering (see [scheduleTransitionSteps]): the durable WorkManager path is
 *    established FIRST, then (for exact backends) the new exact alarm is armed, and only
 *    AFTER that are stale/other-mode alarms cancelled. There is no window where the new
 *    path has not been made durable and the old path has already been torn down. A
 *    same-mode AlarmManager set replaces the old alarm entry for the same PendingIntent,
 *    so only the other mode's alarm needs explicit cancellation.
 * 5. While an exact backend is armed, a durable WorkManager safety backup (with a grace
 *    window) is kept under its OWN unique, SLOT-SCOPED name
 *    ([backupWorkNameFor(jobId, slot)]) and carries an explicit `KEY_IS_BACKUP` marker.
 *    A SCHEDULE_EXACT_ALARM revoke deletes AlarmManager alarms and kills the process
 *    without a broadcast; the backup still fires and continues the chain under the current
 *    permission state. The backup is at-most-once per natural slot: the worker suppresses
 *    it whenever the slot was really executed (any-age suppression for a real same-slot
 *    non-skip run — see [CronJobWorker.shouldSuppressSameSlotFire]), and the alarm
 *    receivers cancel the CURRENT slot's backup once the primary slot worker is durably
 *    persisted. Slot-scoping also lets an executing backup re-schedule the NEXT slot
 *    without cancelling itself: [enqueueBackup] REPLACEs only the next slot's name, and
 *    [scheduleLocked] cleans the PREVIOUS slot's backup only after the new path is
 *    durable (skipping the slot the calling backup is itself running) — so a transition
 *    can never cancel the worker that is performing it and degrade the chain to
 *    backup-only execution.
 *
 * Self-replacement note: the fallback worker runs under [workNameFor] and, at completion,
 * [completeNaturalRun] → [scheduleLocked] re-enqueues the NEXT slot under the SAME unique
 * name with REPLACE, and that enqueue's [Operation] is awaited. REPLACE cancels the
 * currently-running work with the same name, which can interrupt that await; the worker
 * now runs its post-transition tail (including the history trim) inside
 * `withContext(NonCancellable)` so the replacement cannot skip cleanup. The next work is
 * committed by the awaited Operation itself, so a failure to persist it surfaces as an
 * exception (and WorkManager retries) rather than being silently dropped.
 */
class CronJobScheduler(
    private val context: Context,
    private val repo: ScheduledJobRepository,
    private val runRepo: ScheduledJobRunRepository,
) {
    private val wm get() = WorkManager.getInstance(context)
    private val alarmManager get() = context.getSystemService(AlarmManager::class.java)

    private val jobLocks = ConcurrentHashMap<String, Mutex>()

    private suspend fun <T> withJobLock(jobId: String, block: suspend () -> T): T {
        val mutex = jobLocks.computeIfAbsent(jobId) { Mutex() }
        return mutex.withLock {
            block()
        }
    }

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
        // Keep the daily keep-alive alarm armed so the AlarmManager pipeline stays warm
        // (Fossify-style dummy alarm; at most one fire per day).
        CronDailyKeepAliveReceiver.armIfAbsent(context)
        // Re-assert the hourly self-healing reconcile on every scheduling call. Boot/time/
        // permission broadcasts also register it, but a fresh install that creates a job
        // without ever seeing one of those broadcasts would otherwise have no periodic
        // safety net. Idempotent — enqueueUniquePeriodicWork with UPDATE.
        CronReconcileWorker.schedulePeriodic(context)
        return withJobLock(job.id) {
            scheduleLocked(job)
        }
    }

    /**
     * @param skipBackupSlotMs the slot whose slot-scoped safety backup must NOT be cleaned
     *  by this transition — the slot a calling backup worker is itself executing. That
     *  worker must complete naturally instead of being cancelled mid-transition (see the
     *  class KDoc self-replacement note and [cleanupStaleSlotBackup]).
     */
    private suspend fun scheduleLocked(
        desired: ScheduledJobEntity,
        skipBackupSlotMs: Long? = null,
    ): ScheduleResult {
        // Control-plane truth is the latest Room row: the caller's snapshot may be stale
        // (a pause/mode edit could have landed between their read and this call), and
        // schedule must never overwrite enabled/mode/config with a stale copy. Only
        // nextRunAtMs is owned/persisted here.
        val job = repo.getById(desired.id) ?: run {
            // Deleted under our feet — sweep any lingering alarms/work.
            cancelLocked(jobId = desired.id, throwOnFailure = true)
            return ScheduleResult(Backend.NONE, null)
        }
        val nowMs = System.currentTimeMillis()
        val nextRun = nextRunMs(job, nowMs)
        if (nextRun == null) {
            repo.update(job.copy(nextRunAtMs = null))
            cancelLocked(jobId = job.id, throwOnFailure = true)
            return ScheduleResult(Backend.NONE, null)
        }

        // The slot being replaced: once the new path is durable, its slot-scoped safety
        // backup (if any) is stale. Saved BEFORE nextRunAtMs is overwritten below.
        val previousSlotMs = job.nextRunAtMs

        // Persist the slot first: nextRunAtMs is the control-plane truth that boot-time
        // reconcile uses to decide whether a job's schedule is still healthy, so it must
        // be durable before we touch alarms/work below.
        repo.update(job.copy(nextRunAtMs = nextRun))

        val exactAlarmGranted = canScheduleExactAlarms()
        val desiredBackend = selectBackend(job.mode, exactAlarmGranted)

        // Ordering contract: the durable WorkManager path is established FIRST and stale
        // alarms are only cancelled AFTER the new path is durable — see the class KDoc
        // point 4 and [scheduleTransitionSteps]. Never cancel before durable replacement.
        val effectiveBackend = when (desiredBackend) {
            Backend.ALARM_CLOCK_DIRECT, Backend.EXACT_ALARM_LLM -> {
                // 1) Durable safety backup FIRST (its own slot-scoped unique name +
                //    KEY_IS_BACKUP). Any failure here propagates — the new durable path
                //    must exist before the old path is torn down (see the class KDoc
                //    point 4 and [scheduleTransitionSteps]).
                enqueueBackup(job, nextRun, nowMs)

                // 2) Arm the new exact alarm. A SecurityException race is possible: the
                // user can revoke SCHEDULE_EXACT_ALARM between canScheduleExactAlarms()
                // and the actual set call; tryArmExactAlarm catches it and returns false.
                val armed = tryArmExactAlarm(job, nextRun, desiredBackend)
                val resolved = resolveBackendAfterArm(desiredBackend, armed)
                if (!armed) {
                    // Permission revoked in the race window → replace the backup with the
                    // immediate flexible work (same slot, idempotent). REPLACE also
                    // replaces the stale fallback under the same unique name, so no
                    // explicit cancel is needed on this path.
                    enqueueFlexible(job, nextRun, nowMs)
                } else {
                    // 3) New path durable — now drop any lingering fallback execution from
                    //    a previous no-permission period (best-effort; the replay /
                    //    at-most-once guard keeps it idempotent if it already fired).
                    cancelUniqueWorkBestEffort(
                        workNameFor(job.id),
                        "schedule: stale fallback cancel failed for ${job.id}",
                    )
                }
                // 4) New path durable — clean the PREVIOUS slot's safety backup (the
                //    current slot's backup was replaced by the enqueue above under the
                //    same slot identity). Never cleans the slot a calling backup worker is
                //    itself running (self-cancel protection).
                cleanupStaleSlotBackup(job.id, previousSlotMs, nextRun, skipBackupSlotMs, "schedule")
                // Clean stale alarms per the ordering contract (see [scheduleTransitionSteps]
                // / [decideAlarmCleanup]).
                when (decideAlarmCleanup(desiredBackend, armed)) {
                    AlarmCleanup.OTHER_MODE_ONLY -> cancelAlarmOfOtherMode(job.id, desiredBackend)
                    AlarmCleanup.BOTH -> cancelAllAlarms(job.id)
                }
                resolved
            }
            Backend.WORK_MANAGER_FALLBACK, Backend.WORK_MANAGER -> {
                // 1) Durable flexible work FIRST (REPLACE keeps the identity stable).
                enqueueFlexible(job, nextRun, nowMs)

                // 2) New path durable — clean the stale exact-path backups: the PREVIOUS
                //    slot's backup AND any backup left over for the CURRENT (same) slot
                //    from a prior exact attempt. Never cleans the slot a calling backup
                //    worker is itself running (self-cancel protection) — that worker
                //    completes naturally, and the flexible work is already durable.
                cleanupStaleSlotBackup(job.id, previousSlotMs, nextRun, skipBackupSlotMs, "schedule")
                if (nextRun != skipBackupSlotMs) {
                    cancelUniqueWorkBestEffort(
                        backupWorkNameFor(job.id, nextRun),
                        "schedule: current-slot backup cancel failed for ${job.id}",
                    )
                }

                // 3) Fallback has no alarm → remove both, now that the new path is durable.
                cancelAllAlarms(job.id)
                desiredBackend
            }
            Backend.NONE -> desiredBackend // handled above, but exhaustiveness
        }

        return ScheduleResult(effectiveBackend, nextRun)
    }

    private suspend fun enqueueFlexible(
        job: ScheduledJobEntity,
        scheduledAtMs: Long,
        nowMs: Long,
    ) {
        val jobId = job.id
        val delayMs = max(0L, scheduledAtMs - nowMs)
        val req = OneTimeWorkRequestBuilder<CronJobWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder()
                .putString(CronJobWorker.KEY_JOB_ID, jobId)
                .putString(CronJobWorker.KEY_JOB_NAME, job.name)
                .putString(CronJobWorker.KEY_JOB_MODE, job.mode)
                .putLong(CronJobWorker.KEY_SCHEDULED_AT_MS, scheduledAtMs)
                .build())
            .addTag(workTagFor(jobId))
            .build()
        // Await the persisted enqueue: fire-and-forget here would let a process kill
        // between this call and WorkManager's DB write silently drop the next fire.
        // See the class KDoc self-replacement note for why awaiting is safe even when
        // this REPLACEs a work that is currently running (the current worker).
        wm.enqueueUniqueWork(workNameFor(jobId), ExistingWorkPolicy.REPLACE, req).await()
    }

    /**
     * Enqueue the exact-backend safety backup under its OWN unique name. Distinct from
     * [enqueueFlexible] so alarm receivers can cancel the current backup precisely once
     * the primary slot worker is durably persisted, and the worker can tell the backup
     * apart via [CronJobWorker.KEY_IS_BACKUP] for at-most-once slot semantics.
     */
    private suspend fun enqueueBackup(job: ScheduledJobEntity, scheduledAtMs: Long, nowMs: Long) {
        val jobId = job.id
        val delayMs = max(0L, scheduledAtMs - nowMs) + EXACT_BACKUP_GRACE_MS
        val req = OneTimeWorkRequestBuilder<CronJobWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder()
                .putString(CronJobWorker.KEY_JOB_ID, jobId)
                .putString(CronJobWorker.KEY_JOB_NAME, job.name)
                .putString(CronJobWorker.KEY_JOB_MODE, job.mode)
                .putLong(CronJobWorker.KEY_SCHEDULED_AT_MS, scheduledAtMs)
                .putBoolean(CronJobWorker.KEY_IS_BACKUP, true)
                .build())
            .addTag(workTagFor(jobId))
            .build()
        wm.enqueueUniqueWork(backupWorkNameFor(jobId, scheduledAtMs), ExistingWorkPolicy.REPLACE, req).await()
    }

    /**
     * Best-effort cancel of a unique WorkManager work, used only for STALE paths whose
     * teardown must never precede the new durable path (see [ScheduleStep]). Non-cancellation
     * failures are logged; [CancellationException] is rethrown so a cancelled coroutine keeps
     * its structured-concurrency semantics (a fire-and-forget swallow would let the caller
     * keep mutating durable state after cancellation).
     */
    private suspend fun cancelUniqueWorkBestEffort(name: String, contextMessage: String) {
        try {
            wm.cancelUniqueWork(name).await()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, contextMessage, t)
        }
    }

    /**
     * Best-effort cancellation of a stale SLOT-SCOPED safety backup
     * ([backupWorkNameFor(jobId, staleSlotMs)]), called ONLY after the new durable path
     * for the transition has been established (see [ScheduleStep]). Skips [skipSlotMs] —
     * the slot a calling backup worker is itself executing, which must complete naturally
     * instead of being cancelled mid-transition. [CancellationException] propagates (see
     * [cancelUniqueWorkBestEffort]).
     */
    private suspend fun cleanupStaleSlotBackup(
        jobId: String,
        staleSlotMs: Long?,
        newSlotMs: Long,
        skipSlotMs: Long?,
        contextLabel: String,
    ) {
        if (staleSlotMs == null || staleSlotMs == newSlotMs || staleSlotMs == skipSlotMs) return
        cancelUniqueWorkBestEffort(
            backupWorkNameFor(jobId, staleSlotMs),
            "$contextLabel: stale backup cancel failed for $jobId (slot $staleSlotMs)",
        )
    }

    /**
     * Trigger a manual fire (trigger_job_now). Distinct work name from the regular schedule
     * + sets KEY_MANUAL=true so the worker skips lastRunAtMs / runs_so_far bumps. Manual
     * fires are bonus — they don't disturb the regular schedule's accounting.
     */
    suspend fun triggerNow(jobId: String) = withJobLock(jobId) {
        val req = OneTimeWorkRequestBuilder<CronJobWorker>()
            .setInitialDelay(0L, TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder()
                .putString(CronJobWorker.KEY_JOB_ID, jobId)
                .putBoolean(CronJobWorker.KEY_MANUAL, true)
                .build())
            .addTag(workTagFor(jobId))
            .build()
        wm.enqueueUniqueWork(manualWorkNameFor(jobId), ExistingWorkPolicy.REPLACE, req).await()
    }

    suspend fun cancel(jobId: String) = withJobLock(jobId) {
        cancelLocked(jobId = jobId, throwOnFailure = true)
    }

    /**
     * Cancel all WorkManager work + alarms for a job. All WorkManager cancels are awaited;
     * alarm cancellation is synchronous and ALWAYS runs. If any WorkManager cancel failed,
     * the first failure is rethrown (unless [throwOnFailure] is false) so callers observe
     * the failure instead of a false "cancelled" — e.g. a pause whose cancel failed leaves
     * pending work that would otherwise fire later.
     */
    private suspend fun cancelLocked(jobId: String, throwOnFailure: Boolean) {
        // Slot-scoped safety backups ([backupWorkNameFor(jobId, slot)]) cannot be
        // enumerated here, but every backup request carries the job's tag — the
        // cancelAllWorkByTag below covers all of them (any slot).
        val operations = listOf(
            wm.cancelUniqueWork(workNameFor(jobId)),
            wm.cancelUniqueWork(manualWorkNameFor(jobId)),
            wm.cancelUniqueWork(catchupWorkNameFor(jobId)),
            wm.cancelAllWorkByTag(workTagFor(jobId)),
        )
        var firstFailure: Throwable? = null
        for (op in operations) {
            try {
                op.await()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                if (firstFailure == null) firstFailure = t
            }
        }
        // Alarm cancellation is synchronous — always run it before surfacing any failure.
        cancelAllAlarms(jobId)
        if (firstFailure != null) {
            Log.e(TAG, "cancel($jobId): a WorkManager cancel failed", firstFailure)
            if (throwOnFailure) throw firstFailure
        }
    }

    /**
     * Linearly "update enabled + schedule/cancel" for a single job (the UI and LLM pause/
     * resume paths). Runs the enabled flip AND the schedule/cancel transition under the
     * per-job lock so no concurrent transition can observe the half-updated state. The
     * enabled=false flip is persisted BEFORE the cancel, so even if the cancel fails the
     * database stays disabled and the caller observes the failure (throw).
     */
    suspend fun setEnabled(jobId: String, enabled: Boolean) = withJobLock(jobId) {
        val latest = repo.getById(jobId) ?: return@withJobLock
        val updated = latest.copy(enabled = enabled)
        repo.update(updated)
        if (enabled) {
            scheduleLocked(updated)
        } else {
            cancelLocked(jobId = jobId, throwOnFailure = true)
        }
    }

    /**
     * Linearly disable a job whose bounds expired (end_at passed / max_runs reached),
     * using a FRESH read so the worker's startup snapshot can never overwrite the user's
     * latest mode/config. Cancellation is best-effort: the disabled flag alone stops all
     * future fires (the worker no-ops on disabled jobs), and a failed cancel is healed by
     * the next reconcile/boot sweep.
     */
    suspend fun disableExpired(jobId: String) = withJobLock(jobId) {
        val latest = repo.getById(jobId) ?: return@withJobLock
        repo.update(latest.copy(enabled = false))
        cancelLocked(jobId = jobId, throwOnFailure = false)
    }

    /**
     * Atomically finalize a natural (non-manual) run from the latest Room row: reflect
     * lastRunAtMs / runsSoFar and the once-or-maxRuns enabled transition while preserving
     * the user's latest enabled/mode/config. If a pause landed during the run, the job
     * stays paused and nothing is re-armed. Re-schedules the next fire when still enabled.
     *
     * [skipBackupSlotMs] is the slot the CALLING worker is itself executing when that
     * worker is a safety backup — its own slot-scoped backup must complete naturally
     * instead of being cancelled by the re-schedule's stale-backup cleanup.
     *
     * Returns the final job row, or null if the job was deleted while running.
     */
    suspend fun completeNaturalRun(
        jobId: String,
        nowMs: Long,
        successCount: Int,
        skipBackupSlotMs: Long? = null,
    ): ScheduledJobEntity? = withJobLock(jobId) {
        val latest = repo.getById(jobId) ?: return@withJobLock null
        val maxReached = latest.maxRuns != null && successCount >= latest.maxRuns
        val updated = latest.copy(
            lastRunAtMs = nowMs,
            runsSoFar = successCount,
            // once/maxRuns are terminal regardless of user intent; otherwise the user's
            // latest enabled flag wins (a mid-run pause must NOT be re-enabled here).
            enabled = if (latest.scheduleType == "once" || maxReached) false else latest.enabled,
        )
        repo.update(updated)
        if (updated.enabled) scheduleLocked(updated, skipBackupSlotMs = skipBackupSlotMs)
        updated
    }

    /**
     * Advance the schedule after a suppressed duplicate fire without counting a run.
     * Mirrors the legacy replay-advance logic but re-reads the latest row and runs under
     * the per-job lock so the user's latest edits are preserved.
     *
     * [duplicateSlotMs] is the slot whose duplicate was suppressed. If the control plane
     * has already moved past it (a healthy fire re-scheduled [ScheduledJobEntity.nextRunAtMs]
     * strictly beyond it — see [hasScheduleAdvancedPastSlot]) or the job is disabled, this is
     * a no-op: the duplicate must never double-advance (or re-arm) a healthy plan.
     *
     * [skipBackupSlotMs] is the slot the CALLING worker is itself executing when that
     * worker is a safety backup — its own slot-scoped backup must complete naturally
     * instead of being cancelled by the re-schedule's stale-backup cleanup.
     */
    suspend fun advanceAfterSuppressedReplay(
        jobId: String,
        priorStartedAtMs: Long,
        duplicateSlotMs: Long,
        skipBackupSlotMs: Long? = null,
    ): ScheduledJobEntity? = withJobLock(jobId) {
        val latest = repo.getById(jobId) ?: return@withJobLock null
        // Disabled → no-op: a suppressed duplicate must never re-arm a paused job.
        if (!latest.enabled) return@withJobLock latest
        // Control plane already advanced past the duplicated slot → no-op.
        if (hasScheduleAdvancedPastSlot(latest.enabled, latest.nextRunAtMs, duplicateSlotMs)) {
            return@withJobLock latest
        }
        val updated = if (latest.scheduleType == "once") {
            // One-shot consumed by the original fire — at-most-once semantics.
            latest.copy(
                enabled = false,
                lastRunAtMs = priorStartedAtMs,
                nextRunAtMs = null,
            )
        } else {
            latest.copy(
                lastRunAtMs = maxOf(latest.lastRunAtMs ?: Long.MIN_VALUE, priorStartedAtMs),
            )
        }
        repo.update(updated)
        if (updated.enabled) scheduleLocked(updated, skipBackupSlotMs = skipBackupSlotMs)
        updated
    }

    /**
     * Re-schedule every enabled job. Per-job exceptions are isolated so one broken job
     * cannot abort the sweep; failing job ids are returned so callers (the boot/reconcile
     * worker) can Result.retry instead of the failure being silently swallowed.
     */
    suspend fun scheduleAllEnabled(): List<String> {
        val failures = mutableListOf<String>()
        for (job in repo.getEnabled()) {
            try {
                schedule(job)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "scheduleAllEnabled: job ${job.id} failed", t)
                failures += job.id
            }
        }
        return failures
    }

    /**
     * Heal schedules from the Room source of truth without replacing healthy future work.
     * Used after boot and after an explicit user launch (the only way to leave force-stop).
     * Per-job exceptions are isolated (see [scheduleAllEnabled]).
     *
     * @param fireBudget global per-reconcile budget for catchup fires. Jobs processed after
     *  the budget is exhausted have every remaining catchup slot recorded as
     *  `skipped_catchup` instead of being enqueued — a reconcile after a long downtime can
     *  no longer queue hundreds/thousands of immediate WorkManager firings across jobs.
     */
    suspend fun reconcileAllEnabled(
        fireBudget: Int = MAX_CATCHUP_FIRES_PER_RECONCILE,
    ): List<String> {
        CronDailyKeepAliveReceiver.armIfAbsent(context)
        val failures = mutableListOf<String>()
        var remainingBudget = fireBudget.coerceAtLeast(0)
        for (job in repo.getEnabled()) {
            try {
                val fired = withJobLock(job.id) { reconcileJobLocked(job, remainingBudget) }
                remainingBudget = (remainingBudget - fired).coerceAtLeast(0)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "reconcileAllEnabled: job ${job.id} failed", t)
                failures += job.id
            }
        }
        return failures
    }

    private suspend fun reconcileJobLocked(initial: ScheduledJobEntity, fireBudget: Int): Int {
        // The Room row is the control-plane truth — re-read in case the snapshot is stale.
        val job = repo.getById(initial.id) ?: return 0
        val nowMs = System.currentTimeMillis()
        val persistedNext = job.nextRunAtMs
        if (persistedNext != null && persistedNext > nowMs) {
            rearmFutureIfNeeded(job, persistedNext, nowMs)
            return 0
        }

        val plan = CatchupPlanner.plan(job, lastRunMs = job.lastRunAtMs, nowMs = nowMs)
        val fired = enqueueCatchupChain(job, plan, fireBudget)
        // Budget-limited slots are deliberately dropped, just like plan.skippedCatchupCount.
        val budgetSkipped = (plan.fireDelaysMs.size - fired).coerceAtLeast(0)
        recordSkippedCatchups(job, plan, nowMs, extraSkipped = budgetSkipped)

        // A missed one-shot is represented by the catchup worker itself. Re-arming the
        // original past RTC alarm would create a duplicate immediate delivery.
        if (job.scheduleType == "once" && plan.fireSlotsMs.isNotEmpty()) {
            repo.update(job.copy(nextRunAtMs = plan.fireSlotsMs.last()))
        } else {
            scheduleLocked(job)
        }
        return fired
    }

    private suspend fun rearmFutureIfNeeded(
        job: ScheduledJobEntity,
        scheduledAtMs: Long,
        nowMs: Long,
    ) {
        val exactAlarmGranted = canScheduleExactAlarms()
        val desiredBackend = selectBackend(job.mode, exactAlarmGranted)
        when (desiredBackend) {
            Backend.ALARM_CLOCK_DIRECT, Backend.EXACT_ALARM_LLM -> {
                // Same ordering as scheduleLocked: durable path first, stale teardown after.
                enqueueBackup(job, scheduledAtMs, nowMs)
                if (!tryArmExactAlarm(job, scheduledAtMs, desiredBackend)) {
                    // Permission revoked between the check and the set — replace the
                    // backup with the immediate flexible work (same slot) and remove both
                    // alarms (fallback has none). REPLACE also replaces the stale fallback
                    // under the same unique name.
                    enqueueFlexible(job, scheduledAtMs, nowMs)
                    cancelAllAlarms(job.id)
                } else {
                    // New path durable — tear down the stale fallback (best-effort) and the
                    // other-mode alarm.
                    cancelUniqueWorkBestEffort(
                        workNameFor(job.id),
                        "rearm: stale fallback cancel failed for ${job.id}",
                    )
                    cancelAlarmOfOtherMode(job.id, desiredBackend)
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
                if (!active) enqueueFlexible(job, scheduledAtMs, nowMs)
                // Drop the stale exact-backend backup for THIS slot (best-effort). Rearm
                // runs under the per-job lock from app/reconcile — never from inside the
                // slot's own backup worker — so this cancel cannot self-cancel; the
                // durable flexible path above is already in place either way.
                cancelUniqueWorkBestEffort(
                    backupWorkNameFor(job.id, scheduledAtMs),
                    "rearm: stale backup cancel failed for ${job.id}",
                )
                // Fallback has no alarm — remove both after the durable path is in place.
                cancelAllAlarms(job.id)
            }
        }
    }

    /**
     * Enqueue at most [fireBudget] catchup fires from [plan]. Returns the number of fires
     * actually enqueued. The caller converts the rest into `skipped_catchup` history rows.
     */
    private suspend fun enqueueCatchupChain(
        job: ScheduledJobEntity,
        plan: CatchupPlanner.CatchupPlan,
        fireBudget: Int,
    ): Int {
        val fireCount = plan.fireDelaysMs.size.coerceAtMost(fireBudget.coerceAtLeast(0))
        val requests = plan.fireDelaysMs.take(fireCount).mapIndexed { index, delayMs ->
            OneTimeWorkRequestBuilder<CronJobWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setInputData(Data.Builder()
                    .putString(CronJobWorker.KEY_JOB_ID, job.id)
                    .putString(CronJobWorker.KEY_JOB_NAME, job.name)
                    .putString(CronJobWorker.KEY_JOB_MODE, job.mode)
                    .putLong(CronJobWorker.KEY_SCHEDULED_AT_MS, plan.fireSlotsMs[index])
                    .build())
                .addTag(workTagFor(job.id))
                .build()
        }
        if (requests.isEmpty()) return 0

        var chain = wm.beginUniqueWork(
            catchupWorkNameFor(job.id),
            ExistingWorkPolicy.REPLACE,
            requests.first(),
        )
        requests.drop(1).forEach { chain = chain.then(it) }
        // Await the chain enqueue so a missed catchup isn't silently dropped.
        chain.enqueue().await()
        return fireCount
    }

    private suspend fun recordSkippedCatchups(
        job: ScheduledJobEntity,
        plan: CatchupPlanner.CatchupPlan,
        nowMs: Long,
        extraSkipped: Int,
    ) {
        // History is capped at 100 rows per job. Avoid doing thousands of writes after a
        // long offline period only to trim them immediately.
        val totalSkipped = plan.skippedCatchupCount + extraSkipped.coerceAtLeast(0)
        repeat(totalSkipped.coerceAtMost(100)) {
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
        if (totalSkipped > 0) runRepo.trim(job.id, keep = 100)
    }

    fun canScheduleExactAlarms(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return alarmManager.canScheduleExactAlarms()
    }

    /**
     * Attempts to arm an exact alarm for the given [backend]. Returns true on success,
     * false if a [SecurityException] was caught — this happens when the user revokes
     * SCHEDULE_EXACT_ALARM between [canScheduleExactAlarms] and the actual set call.
     * On failure, any partially-armed alarm is cleaned up via [cancelAlarmOfOtherMode]
     * plus the caller's fallback handling.
     *
     * Structured so the SecurityException-prone Android calls are isolated in one place;
     * the fallback decision is delegated to the pure [resolveBackendAfterArm] which is
     * JVM-testable without an AlarmManager.
     */
    private fun tryArmExactAlarm(job: ScheduledJobEntity, scheduledAtMs: Long, backend: Backend): Boolean {
        return try {
            when (backend) {
                Backend.ALARM_CLOCK_DIRECT -> scheduleAlarmClockDirect(job, scheduledAtMs)
                Backend.EXACT_ALARM_LLM -> scheduleExactAlarmLlm(job, scheduledAtMs)
                else -> return false
            }
            true
        } catch (_: SecurityException) {
            // Permission revoked in the race window. Clean up any partially-armed alarm
            // so it doesn't fire unexpectedly later.
            cancelAllAlarms(job.id)
            false
        }
    }

    // ---- Direct mode: setAlarmClock (user-visible alarm) ----

    private fun scheduleAlarmClockDirect(job: ScheduledJobEntity, scheduledAtMs: Long) {
        val jobId = job.id
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
        val triggerPI = directAlarmPendingIntent(jobId, scheduledAtMs, job.name, job.mode)
        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(scheduledAtMs, showIntent),
            triggerPI,
        )
    }

    // Display extras (job name/mode) ride along so the receiver → worker chain can build
    // notifications even when the process was cold-started by the alarm and Room is not
    // ready yet. Extras never affect PendingIntent identity, so cancel calls may omit them.
    private fun directAlarmPendingIntent(
        jobId: String,
        scheduledAtMs: Long,
        jobName: String? = null,
        jobMode: String? = null,
    ): PendingIntent {
        val intent = Intent(context, DirectCronAlarmReceiver::class.java)
            .setAction(DirectCronAlarmReceiver.ACTION_FIRE)
            .setData(Uri.parse("rikkahub://cron-direct/$jobId"))
            .putExtra(CronJobWorker.KEY_JOB_ID, jobId)
            .putExtra(CronJobWorker.KEY_JOB_NAME, jobName)
            .putExtra(CronJobWorker.KEY_JOB_MODE, jobMode)
            .putExtra(CronJobWorker.KEY_SCHEDULED_AT_MS, scheduledAtMs)
            // Foreground broadcast queue — faster delivery for user-visible alarms.
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // ---- LLM mode: setExactAndAllowWhileIdle ----

    private fun scheduleExactAlarmLlm(job: ScheduledJobEntity, scheduledAtMs: Long) {
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            scheduledAtMs,
            llmAlarmPendingIntent(job.id, scheduledAtMs, job.name, job.mode),
        )
    }

    private fun llmAlarmPendingIntent(
        jobId: String,
        scheduledAtMs: Long,
        jobName: String? = null,
        jobMode: String? = null,
    ): PendingIntent {
        val intent = Intent(context, ExactCronAlarmReceiver::class.java)
            .setAction(ExactCronAlarmReceiver.ACTION_FIRE)
            .setData(Uri.parse("rikkahub://cron-llm/$jobId"))
            .putExtra(CronJobWorker.KEY_JOB_ID, jobId)
            .putExtra(CronJobWorker.KEY_JOB_NAME, jobName)
            .putExtra(CronJobWorker.KEY_JOB_MODE, jobMode)
            .putExtra(CronJobWorker.KEY_SCHEDULED_AT_MS, scheduledAtMs)
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Cancel the direct-mode alarm only. */
    private fun cancelDirectAlarm(jobId: String) {
        val pi = directAlarmPendingIntent(jobId, 0L)
        alarmManager.cancel(pi)
        pi.cancel()
    }

    /** Cancel the llm-mode alarm only. */
    private fun cancelLlmAlarm(jobId: String) {
        val pi = llmAlarmPendingIntent(jobId, 0L)
        alarmManager.cancel(pi)
        pi.cancel()
    }

    /** Cancel the alarm of the mode we are NOT currently arming (the other backend's PI). */
    private fun cancelAlarmOfOtherMode(jobId: String, backend: Backend) {
        when (backend) {
            Backend.ALARM_CLOCK_DIRECT -> cancelLlmAlarm(jobId)
            Backend.EXACT_ALARM_LLM -> cancelDirectAlarm(jobId)
            else -> cancelAllAlarms(jobId)
        }
    }

    /** Cancel both mode-specific alarms. Called on cancel and on fallback transitions. */
    private fun cancelAllAlarms(jobId: String) {
        cancelDirectAlarm(jobId)
        cancelLlmAlarm(jobId)
    }

    private fun workNameFor(jobId: String) = "cron_job_$jobId"
    private fun manualWorkNameFor(jobId: String) = "cron_job_${jobId}_manual"
    private fun catchupWorkNameFor(jobId: String) = "cron_job_${jobId}_catchup"

    companion object {
        /**
         * Grace window for the WorkManager safety backup that stays armed while an exact
         * backend is active (see class KDoc, point 5). Long enough that a healthy exact
         * fire always completes and re-schedules over the backup first (the LLM mode has a
         * 15-minute execution cap); short enough that a lost exact path (process killed
         * between alarm fire and worker, or a permission revoke that deletes the alarm)
         * is recovered within a reasonable delay. The backup's at-most-once suppression is
         * NOT window-based, so this constant does not need to track the replay window.
         */
        internal const val EXACT_BACKUP_GRACE_MS = 20L * 60_000L

        /**
         * Global per-reconcile budget for catchup fires (item 4). One reconcile pass over
         * many enabled jobs must not enqueue an unbounded number of immediate WorkManager
         * requests after a long offline period; jobs beyond this budget have their
         * remaining catchup slots recorded as `skipped_catchup` instead.
         */
        internal const val MAX_CATCHUP_FIRES_PER_RECONCILE = 60

        // Retained for compatibility with legacy rows and external references. The
        // schedulePrecision column still exists (no Room migration), but it no longer
        // selects a backend — job.mode does.
        const val PRECISION_FLEXIBLE = "flexible"
        const val PRECISION_EXACT = "exact"

        internal fun workTagFor(jobId: String) = "cron_job_tag_$jobId"

        /**
         * Unique SLOT-SCOPED name of the exact-backend safety backup work. Scoping by
         * [scheduledAtMs] means enqueuing the NEXT slot's backup replaces a DIFFERENT
         * unique work than the one currently running — a running backup can re-schedule
         * the next exact slot without cancelling itself ([enqueueBackup] uses REPLACE on
         * this name; with a job-fixed name that REPLACE would cancel the executing backup
         * and interrupt the transition before the next exact alarm was armed). Distinct
         * from the regular execution identity so alarm receivers can cancel the CURRENT
         * slot's backup precisely and the worker can apply at-most-once slot semantics.
         * Every backup request carries [workTagFor], so a job-wide cancellation
         * ([CronJobScheduler.cancelLocked]) still covers every slot's backup via the tag.
         */
        internal fun backupWorkNameFor(jobId: String, scheduledAtMs: Long) =
            "cron_job_${jobId}_backup_$scheduledAtMs"

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
         * True when the control-plane schedule is already healthy for [duplicateSlotMs]:
         * the job is enabled and its persisted [ScheduledJobEntity.nextRunAtMs] points
         * strictly past the duplicated slot. In that case a suppressed duplicate must NOT
         * advance (or re-schedule) anything — the plan already moved on (see
         * [advanceAfterSuppressedReplay]). Pure function so JVM tests can pin the guard.
         */
        internal fun hasScheduleAdvancedPastSlot(
            enabled: Boolean,
            nextRunAtMs: Long?,
            duplicateSlotMs: Long,
        ): Boolean = enabled && nextRunAtMs != null && nextRunAtMs > duplicateSlotMs

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
