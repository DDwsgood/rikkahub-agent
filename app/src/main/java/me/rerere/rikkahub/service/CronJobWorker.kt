package me.rerere.rikkahub.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.CronJobWakeActivity
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.agentrun.AgentRunKind
import me.rerere.rikkahub.data.agentrun.AgentRunRepository
import me.rerere.rikkahub.data.agentrun.AgentRunStatus
import me.rerere.rikkahub.data.ai.tools.HeadlessConversations
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findAssistantById
import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity
import me.rerere.rikkahub.data.db.entity.ScheduledJobRunEntity
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.preferences.WebhookDeliveryState
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.ScheduledJobRepository
import me.rerere.rikkahub.data.repository.ScheduledJobRunRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private const val TAG = "CronJobWorker"

/** Outer bound on how long after [startedAtMs] a WorkManager replay can plausibly arrive. */
internal const val REPLAY_WINDOW_MS = 10L * 60_000L

/** Wait for the generation flow to go idle within the caller's deadline (external automation). */
internal suspend fun awaitGenerationTerminal(
    flow: Flow<Job?>,
    timeoutMs: Long,
): Boolean {
    val completed: Unit? = withTimeoutOrNull(timeoutMs) {
        flow.first { it == null }
        Unit // Without this sentinel, both natural completion and timeout return null.
    }
    return completed != null
}

/**
 * Await the actual turn result without a Cron deadline. The generation runs in
 * ChatService's scope, so a stopped worker must explicitly cancel its own turn.
 */
internal suspend fun awaitLlmTurn(turn: SendMessageTurn): SendMessageResult {
    var finished = false
    try {
        val result = turn.result.await()
        finished = true
        return result
    } finally {
        withContext(NonCancellable) {
            turn.job?.let { job ->
                if (finished) job.join() else job.cancelAndJoin()
            }
        }
    }
}

/**
 * The slot to stamp into the run row. Manual fires (trigger_job_now) happen at nowMs and
 * are NOT bound to any scheduled slot — stamping them with [jobNextRunAtMs] would poison
 * the next regular fire's replay guard. Natural fires stamp the slot they were enqueued
 * for so a true WorkManager replay still matches.
 */
internal fun computeRunSlot(isManual: Boolean, jobNextRunAtMs: Long?, nowMs: Long): Long =
    if (isManual) nowMs else (jobNextRunAtMs ?: nowMs)

/**
 * A WorkManager replay re-fires the SAME enqueued work request, so a true replay's
 * [slotMs] matches the prior row's exactly. The age/source decision is delegated to the
 * unified [shouldSuppressSameSlotFire] rule.
 */
internal fun shouldSuppressAsReplay(
    priorRow: ScheduledJobRunEntity?,
    slotMs: Long,
    nowMs: Long,
    windowMs: Long = REPLAY_WINDOW_MS,
): Boolean {
    if (priorRow == null) return false
    if (priorRow.scheduledAtMs != slotMs) return false
    return shouldSuppressSameSlotFire(priorRow, nowMs, windowMs)
}

/**
 * Unified same-slot at-most-once rule for BOTH the natural replay guard
 * ([shouldSuppressAsReplay]) and the exact-backend safety backup
 * ([CronJobScheduler.enqueueBackup]). The caller has already selected the most-recent
 * same-slot non-skip row ([ScheduledJobRunRepository.getMostRecentNonSkipForSlot]).
 *
 * The decision is source-aware via the row's own timestamps, so both racing directions
 * (backup-first-then-primary, and primary-first-then-backup) are idempotent:
 *
 *  - `startedAtMs >= scheduledAtMs` → a REAL natural execution of that slot. Natural fires
 *    always start at/after their stamped slot (exact alarm, WorkManager delay, or a late
 *    retry with backoff beyond any window). Any such row suppresses a duplicate at ANY age:
 *    there is deliberately no execution window, because a duplicate (late primary, late
 *    retry, or the backup) must never re-execute a slot whose side effects already happened.
 *  - `startedAtMs < scheduledAtMs` → a legacy manual fire: old versions stamped the FUTURE
 *    scheduled slot into manual runs, whose [ScheduledJobRunEntity.startedAtMs] precedes
 *    that slot. Only suppress within the original [REPLAY_WINDOW_MS]; a 16-hour-old legacy
 *    row must never suppress today's real fire.
 */
internal fun shouldSuppressSameSlotFire(
    priorRow: ScheduledJobRunEntity?,
    nowMs: Long,
    windowMs: Long = REPLAY_WINDOW_MS,
): Boolean {
    if (priorRow == null) return false
    if (priorRow.outcome == "concurrent_skip" || priorRow.outcome == "skipped_catchup") return false
    return if (priorRow.startedAtMs >= priorRow.scheduledAtMs) {
        true
    } else {
        priorRow.startedAtMs >= nowMs - windowMs
    }
}

/**
 * At-most-once rule for the exact-backend safety backup ([CronJobScheduler.enqueueBackup]):
 * the backup is a KNOWN duplicate of the same natural slot, so a real same-slot execution
 * suppresses it regardless of age. Shared with the natural replay guard via
 * [shouldSuppressSameSlotFire] so both directions of the backup/primary race are idempotent.
 */
internal fun shouldSuppressBackupFire(
    priorNonSkipRow: ScheduledJobRunEntity?,
    nowMs: Long,
): Boolean = shouldSuppressSameSlotFire(priorNonSkipRow, nowMs)

/**
 * What a worker that lost the concurrency race to an already-running fire of the same job
 * should do. Manual fires are bonus — skipping them is fine (the running natural fire keeps
 * the regular chain alive). A natural fire MUST NOT consume its slot: it returns RETRY so
 * WorkManager re-attempts it (with backoff) after the running fire finishes, preserving the
 * next scheduled execution. `concurrent_skip` is still recorded for history, and the replay
 * guard explicitly ignores it ([shouldSuppressAsReplay] excludes the outcome).
 */
internal enum class ConcurrentSkipDecision { SUCCESS, RETRY }

internal fun decideConcurrentSkip(isManual: Boolean): ConcurrentSkipDecision =
    if (isManual) ConcurrentSkipDecision.SUCCESS else ConcurrentSkipDecision.RETRY

/**
 * Tracks which jobs currently have a worker actively running. Prevents two
 * concurrent fires from racing on the same job — the second one writes a
 * 'concurrent_skip' history row and returns immediately.
 */
private object CronJobRunningTracker {
    private val running = ConcurrentHashMap.newKeySet<String>()
    private val activeRuns = ConcurrentHashMap.newKeySet<String>()
    fun start(jobId: String): Boolean = running.add(jobId)
    fun stop(jobId: String) { running.remove(jobId) }
    fun registerRun(runId: String) { activeRuns.add(runId) }
    fun unregisterRun(runId: String) { activeRuns.remove(runId) }
    fun isRunActive(runId: String): Boolean = activeRuns.contains(runId)
}

internal fun isCronRunActiveInCurrentProcess(runId: String): Boolean =
    CronJobRunningTracker.isRunActive(runId)

class CronJobWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    private val repo: ScheduledJobRepository by inject()
    private val runRepo: ScheduledJobRunRepository by inject()
    private val scheduler: CronJobScheduler by inject()
    private val chatService: ChatService by inject()
    private val conversationRepo: ConversationRepository by inject()
    private val settingsStore: SettingsStore by inject()
    private val localTools: LocalTools by inject()
    private val directRunner: DirectModeActionRunner by inject()
    private val agentRunRepo: AgentRunRepository by inject()
    private val diagnosticsStore: ScheduleRunDiagnosticsStore by inject()
    private val deliveryPrefs: me.rerere.rikkahub.data.preferences.ScheduleDeliveryPreferences by inject()

    /** Required for expedited exact-alarm work on Android 11 and lower. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val jobId = inputData.getString(KEY_JOB_ID)
        // The display name rides along in inputData (armed via alarm extras), so the
        // foreground notification never depends on Room being ready in a cold-started
        // process.
        return createExecutionForegroundInfo(
            jobId = jobId,
            jobName = inputData.getString(KEY_JOB_NAME),
        )
    }

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val isManual = inputData.getBoolean(KEY_MANUAL, false)
        val isBackup = inputData.getBoolean(KEY_IS_BACKUP, false)
        val requestedSlotMs = inputData.getLong(KEY_SCHEDULED_AT_MS, Long.MIN_VALUE)
            .takeUnless { it == Long.MIN_VALUE }
        val alarmReceivedAtMs = inputData.getLong(KEY_ALARM_RECEIVED_AT_MS, Long.MIN_VALUE)
            .takeUnless { it == Long.MIN_VALUE }
        val triggerSource = inputData.getString(KEY_TRIGGER_SOURCE) ?: when {
            isManual -> "manual"
            isBackup -> "wm_backup"
            else -> "wm_fallback"
        }

        if (!CronJobRunningTracker.start(jobId)) {
            val skipNowMs = System.currentTimeMillis()
            val skipRunId = Uuid.random().toString()
            recordRun(skipRunId, jobId, scheduledAtMs = requestedSlotMs ?: skipNowMs,
                outcome = "concurrent_skip", mode = inputData.getString(KEY_JOB_MODE) ?: "?", convId = null, errorMessage = null)
            runCatching {
                diagnosticsStore.record(
                    ScheduleRunTrace(
                        runId = skipRunId,
                        jobId = jobId,
                        jobName = inputData.getString(KEY_JOB_NAME) ?: jobId,
                        mode = inputData.getString(KEY_JOB_MODE) ?: "?",
                        triggerSource = triggerSource,
                        scheduledAtMs = requestedSlotMs ?: skipNowMs,
                        alarmReceivedAtMs = alarmReceivedAtMs,
                        workerStartedAtMs = skipNowMs,
                        foregroundResult = "skipped_concurrent",
                        finishedAtMs = skipNowMs,
                        executionDurationMs = 0L,
                        outcome = "concurrent_skip",
                    )
                )
            }
            // A natural fire that loses the concurrency race must NOT consume and drop its
            // slot — returning RETRY lets WorkManager re-attempt it once the running fire
            // completes (its own completion keeps the chain alive either way).
            return when (decideConcurrentSkip(isManual)) {
                ConcurrentSkipDecision.SUCCESS -> Result.success()
                ConcurrentSkipDecision.RETRY -> Result.retry()
            }
        }

        val nowMs = System.currentTimeMillis()
        val workerStartElapsedMs = android.os.SystemClock.elapsedRealtime()
        val runRowId = Uuid.random().toString()
        CronJobRunningTracker.registerRun(runRowId)
        var ledgerId: String? = null
        var runRowInserted = false
        var runRowTerminal = false
        var stagedWebhookDeliveryId: String? = null
        var recoverOutboxAfterCancellation = false
        var jobMode: String? = inputData.getString(KEY_JOB_MODE)
        var jobName: String? = inputData.getString(KEY_JOB_NAME)
        var slotMs: Long = requestedSlotMs ?: nowMs
        var foregroundResult = "skipped"
        var foregroundAtMs: Long? = null

        try {
            val job = repo.getById(jobId) ?: return Result.success()
            if (!job.enabled) return Result.success()
            jobMode = job.mode
            jobName = job.name

            // WorkManager otherwise imposes a roughly ten-minute execution limit. Promote
            // only while this user-requested job is active; there is no always-on service.
            try {
                setForeground(createExecutionForegroundInfo(jobId, job.name))
                foregroundResult = "promoted"
                foregroundAtMs = System.currentTimeMillis()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                // Short jobs can still complete under normal WorkManager limits when an OEM
                // rejects foreground promotion. Keep the run alive and retain diagnostics.
                foregroundResult = "denied:${t::class.simpleName}"
                foregroundAtMs = System.currentTimeMillis()
                Log.w(TAG, "Unable to promote scheduled job $jobId to foreground", t)
            }

            // Bounds re-check (defends against a stale enqueue surviving a job edit). The
            // disable goes through the scheduler's linearized fresh-read transition so the
            // startup snapshot never overwrites the user's latest mode/config.
            if (boundsExpired(job, nowMs)) {
                scheduler.disableExpired(jobId)
                return Result.success()
            }

            // Slot stamp: manual fires use nowMs (they aren't bound to a scheduled slot);
            // natural fires use job.nextRunAtMs so a true WorkManager replay can be detected.
            val scheduledAtMs = computeRunSlot(
                isManual = isManual,
                jobNextRunAtMs = requestedSlotMs ?: job.nextRunAtMs,
                nowMs = nowMs,
            )
            slotMs = scheduledAtMs

            if (!isManual) {
                val priorRow = runRepo.getMostRecentNonSkipForSlot(jobId, scheduledAtMs)
                val suppress = if (isBackup) shouldSuppressBackupFire(priorRow, nowMs)
                               else shouldSuppressAsReplay(priorRow, scheduledAtMs, nowMs)
                if (suppress) {
                    Log.w(TAG, "doWork: suppressing duplicate fire for $jobId " +
                            "(prior row ${priorRow!!.id} same scheduledAtMs=$scheduledAtMs outcome=${priorRow.outcome} backup=$isBackup)")
                    val replayDoneMs = System.currentTimeMillis()
                    runRepo.insert(ScheduledJobRunEntity(
                        id = runRowId,
                        jobId = jobId,
                        mode = job.mode,
                        scheduledAtMs = scheduledAtMs,
                        startedAtMs = nowMs,
                        finishedAtMs = replayDoneMs,
                        outcome = "process_killed_replay",
                        conversationId = null,
                        errorMessage = "duplicate fire suppressed (prior run ${priorRow.id})",
                    ))
                    runCatching {
                        diagnosticsStore.record(
                            ScheduleRunTrace(
                                runId = runRowId,
                                jobId = jobId,
                                jobName = job.name,
                                mode = job.mode,
                                triggerSource = "$triggerSource:replay_suppressed",
                                scheduledAtMs = scheduledAtMs,
                                alarmReceivedAtMs = alarmReceivedAtMs,
                                workerStartedAtMs = nowMs,
                                foregroundResult = foregroundResult,
                                foregroundAtMs = foregroundAtMs,
                                finishedAtMs = replayDoneMs,
                                executionDurationMs = (android.os.SystemClock.elapsedRealtime() - workerStartElapsedMs).coerceAtLeast(0L),
                                outcome = "process_killed_replay",
                                deliveryResult = "suppressed_duplicate",
                            )
                        )
                    }
                    scheduler.advanceAfterSuppressedReplay(
                        jobId, priorRow.startedAtMs, duplicateSlotMs = scheduledAtMs,
                        skipBackupSlotMs = if (isBackup) scheduledAtMs else null,
                    )
                    return Result.success()
                }
            }

            // the optimistic row is NEVER stamped 'success' — an unfinished run must
            // not count towards countSuccessful()/max_runs. Terminal state is written on
            // completion (or by the crash handler below / the boot stranded-row sweep).
            runRepo.insert(ScheduledJobRunEntity(
                id = runRowId,
                jobId = jobId,
                mode = job.mode,
                scheduledAtMs = scheduledAtMs,
                startedAtMs = nowMs,
                finishedAtMs = null,
                outcome = "running",                         // in-flight, not success
                conversationId = null,
                errorMessage = null,
            ))
            runRowInserted = true

            // Incremental checkpoint #1 (in-flight): persisted BEFORE execution so if the OS
            // kills the process mid-run, boot recovery patches this trace with terminal outcome
            // while preserving alarm receipt, worker start, and FGS promotion evidence.
            runCatching {
                diagnosticsStore.record(
                    ScheduleRunTrace(
                        runId = runRowId,
                        jobId = jobId,
                        jobName = job.name,
                        mode = job.mode,
                        triggerSource = triggerSource,
                        scheduledAtMs = scheduledAtMs,
                        alarmReceivedAtMs = alarmReceivedAtMs,
                        workerStartedAtMs = nowMs,
                        foregroundResult = foregroundResult,
                        foregroundAtMs = foregroundAtMs,
                        outcome = "running",
                    )
                )
            }

            // Phase 24 — open the cross-pillar ledger row alongside the domain detail row.
            // domain_id is keyed per-fire (jobId:slot) so a replay or a later fire of the
            // same job is a distinct ledger row. Best-effort: ledger failures never break the
            // cron run.
            ledgerId = agentRunRepo.open(
                kind = AgentRunKind.Cron,
                domainId = "$jobId:$scheduledAtMs",
                metadata = buildJsonObject {
                    put("job_name", job.name)
                    put("mode", job.mode)
                    put("manual", isManual)
                },
            )

            // Optional lock-screen wake (opt-in setting, default off): a full-screen
            // intent alert over the keyguard when a scheduled job fires — the alarm-app
            // pattern. Manual fires never wake the screen.
            val wakeOnLockScreen = try {
                settingsStore.awaitLoadedSettings().scheduledJobWakeOnLockScreen
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "wake-on-lockscreen settings read failed for $jobId", t)
                false
            }
            if (!isManual && wakeOnLockScreen) {
                runCatching { postWakeNotification(job.name) }
                    .onFailure { Log.w(TAG, "wake notification failed for $jobId", it) }
            }

            var directStepSummaries: List<String> = emptyList()
            val (outcome, errorMessage, convIdMaybe) = when (job.mode) {
                "llm"    -> runLlm(job)
                "direct" -> {
                    val (o, e, summaries) = runDirectWithSummaries(job)
                    directStepSummaries = summaries
                    Triple(o, e, null)
                }
                else     -> Triple("failed", "unknown_mode:${job.mode}", null)
            }
            val finishedAtMs = System.currentTimeMillis()
            val executionDurationMs = (android.os.SystemClock.elapsedRealtime() - workerStartElapsedMs).coerceAtLeast(0L)
            val expectedConversationId = convIdMaybe?.toString()
            val expectedErrorMessage = errorMessage?.take(500)

            // Phase 1 of 2-phase write-ahead Webhook outbox: stage the pending outbox entry
            // BEFORE committing the terminal Room run row, capturing exact expected commit fields
            // plus process/boot ownership + monotonic uptime so reconciliation:
            // - NEVER promotes a STAGED payload if Room committed a different crash `failed` row (P1-3).
            // - Reclaims orphaned STAGED rows on periodic passes without device reboot when the
            //   owner process dies, without misclassifying live running jobs (P1-4).
            // - Promotes STAGED -> COMMITTED right after normal Room commit, decoupling delivery
            //   from trimmable run history rows.
            var pendingWebhook: me.rerere.rikkahub.data.preferences.PendingWebhookDelivery? = null
            var outboxStageErrorCode: String? = null
            withContext(NonCancellable) {
                try {
                    val cfg = deliveryPrefs.current()
                    val isSuccess = outcome == "success"
                    val wantsWebhook = cfg.isWebhookUsable &&
                        ((isSuccess && cfg.webhookNotifyOnSuccess) || (!isSuccess && cfg.webhookNotifyOnFailure))
                    if (wantsWebhook) {
                        val resultText = extractDeliverySummary(outcome, errorMessage, convIdMaybe, directStepSummaries)
                        val entry = me.rerere.rikkahub.data.preferences.PendingWebhookDelivery(
                            deliveryId = "$jobId-$scheduledAtMs",
                            runId = runRowId,
                            jobId = jobId,
                            jobName = job.name,
                            mode = job.mode,
                            outcome = outcome,
                            summary = resultText,
                            timestampMs = finishedAtMs,
                            destinationBinding = cfg.destinationBinding(),
                            createdAtMs = finishedAtMs,
                            commitVerificationEnabled = true,
                            expectedConversationId = expectedConversationId,
                            expectedErrorMessage = expectedErrorMessage,
                            ownerProcessId = ProcessRuntimeIdentity.processInstanceId,
                            ownerBootId = ProcessRuntimeIdentity.currentBootId(applicationContext),
                            stagedElapsedMs = ProcessRuntimeIdentity.monotonicNowMs(),
                        )
                        when (deliveryPrefs.stagePendingWebhook(entry)) {
                            me.rerere.rikkahub.data.preferences.OutboxStageResult.STAGED -> {
                                pendingWebhook = entry
                                stagedWebhookDeliveryId = entry.deliveryId
                            }
                            me.rerere.rikkahub.data.preferences.OutboxStageResult.ALREADY_PRESENT -> Unit
                            me.rerere.rikkahub.data.preferences.OutboxStageResult.CAPACITY_EXCEEDED -> {
                                Log.w(TAG, "webhook outbox full for $jobId; rejecting enqueue")
                                outboxStageErrorCode = "webhook:outbox_full"
                            }
                        }
                    }
                } catch (err: Throwable) {
                    Log.w(TAG, "webhook outbox persist error for $jobId: ${err::class.simpleName}")
                    outboxStageErrorCode = "webhook:outbox_error"
                }
            }

            // Keep the Room commit, its in-memory acknowledgement, and outbox promotion
            // together so coroutine cancellation cannot interrupt the acknowledgement.
            val committedRun = withContext(NonCancellable) {
                val updatedRows = runRepo.update(ScheduledJobRunEntity(
                    id = runRowId,
                    jobId = jobId,
                    mode = job.mode,
                    scheduledAtMs = scheduledAtMs,
                    startedAtMs = nowMs,
                    finishedAtMs = finishedAtMs,
                    outcome = outcome,
                    conversationId = expectedConversationId,
                    errorMessage = expectedErrorMessage,
                ))
                if (updatedRows != 1) {
                    // The row may have been removed by task deletion while this worker ran.
                    pendingWebhook?.let { entry ->
                        runCatching {
                            deliveryPrefs.markOutboxTerminal(
                                deliveryId = entry.deliveryId,
                                summary = "webhook:uncommitted_discarded",
                                nowMs = finishedAtMs,
                                onlyIfStateIn = setOf(WebhookDeliveryState.STAGED),
                                expectedRunId = entry.runId,
                            )
                        }.onFailure {
                            CronReconcileWorker.enqueueWebhookOutboxRecovery(applicationContext)
                        }
                    }
                    ledgerId?.let { id ->
                        runCatching { agentRunRepo.markTerminal(id, AgentRunStatus.cancelled, "run_row_removed") }
                            .onFailure { Log.w(TAG, "Unable to close deleted run ledger for $jobId", it) }
                    }
                    return@withContext false
                }
                runRowTerminal = true
                pendingWebhook?.let { entry ->
                    val committed = runCatching {
                        deliveryPrefs.markOutboxCommitted(entry.deliveryId, finishedAtMs, expectedRunId = entry.runId)
                    }.onFailure {
                        Log.w(TAG, "webhook outbox markCommitted failed for $jobId: ${it::class.simpleName}")
                    }.getOrDefault(false)
                    if (!committed) {
                        CronReconcileWorker.enqueueWebhookOutboxRecovery(applicationContext)
                    }
                }
                true
            }
            if (!committedRun) return Result.success()

            ledgerId?.let { id ->
                agentRunRepo.markTerminal(
                    id = id,
                    status = if (outcome == "success") AgentRunStatus.succeeded else AgentRunStatus.failed,
                    lastError = if (outcome == "success") null else "$outcome: ${errorMessage.orEmpty()}",
                )
            }

            // Persist failure notification, completion diagnostics, and durable Webhook enqueue
            // BEFORE calling completeNaturalRun(). In WorkManager fallback mode (no exact-alarm
            // grant), completeNaturalRun() re-enqueues the next slot with ExistingWorkPolicy.REPLACE
            // under this worker's own unique work name, which immediately cancels this worker's
            // coroutine. Furthermore, completion diagnostics are written BEFORE ScheduleWebhookWorker
            // is enqueued so a fast delivery can never race ahead and get overwritten to `queued`.
            val deliveryParts = mutableListOf<String>()
            if (outcome != "success") {
                postFailureNotification(job.name, "$outcome: ${errorMessage.orEmpty()}")
                deliveryParts += "local_failure_notif"
            }
            if (pendingWebhook != null) {
                deliveryParts += "webhook:queued"
            } else if (outboxStageErrorCode != null) {
                deliveryParts += outboxStageErrorCode
            }

            withContext(NonCancellable) {
                // Checkpoint #2 (execution complete + delivery queued) persisted BEFORE enqueuing
                // ScheduleWebhookWorker and BEFORE schedule advancement.
                val finishedTrace = ScheduleRunTrace(
                    runId = runRowId,
                    jobId = jobId,
                    jobName = job.name,
                    mode = job.mode,
                    triggerSource = triggerSource,
                    scheduledAtMs = scheduledAtMs,
                    alarmReceivedAtMs = alarmReceivedAtMs,
                    workerStartedAtMs = nowMs,
                    foregroundResult = foregroundResult,
                    foregroundAtMs = foregroundAtMs,
                    finishedAtMs = finishedAtMs,
                    executionDurationMs = executionDurationMs,
                    outcome = outcome,
                    deliveryResult = deliveryParts.takeIf { it.isNotEmpty() }?.joinToString(", "),
                )
                runCatching { diagnosticsStore.record(finishedTrace) }
                    .onFailure { Log.w(TAG, "diagnosticsStore.record (finished) failed for $jobId", it) }

                pendingWebhook?.let { entry ->
                    try {
                        ScheduleWebhookWorker.enqueueAwait(
                            context = applicationContext,
                            runId = entry.runId,
                            payload = ScheduleWebhookPayload(
                                deliveryId = entry.deliveryId,
                                jobId = entry.jobId,
                                jobName = entry.jobName,
                                mode = entry.mode,
                                outcome = entry.outcome,
                                summary = entry.summary,
                                timestampMs = entry.timestampMs,
                            ),
                            destinationBinding = entry.destinationBinding,
                        )
                    } catch (err: Throwable) {
                        // Keep the committed outbox record intact, mark interim retry status
                        // (deliveryFinishedAtMs remains null), and trigger an independent backed-off
                        // recovery worker so even the last enabled job recovers without waiting for
                        // a new app launch.
                        Log.w(TAG, "webhook outbox enqueue deferred for $jobId: ${err::class.simpleName}")
                        runCatching {
                            diagnosticsStore.updateDelivery(
                                runId = runRowId,
                                webhookSummary = "webhook:enqueue_retry_pending",
                                attemptAtMs = System.currentTimeMillis(),
                                attemptDurationMs = 0L,
                                isFinal = false,
                            )
                        }
                        CronReconcileWorker.enqueueWebhookOutboxRecovery(applicationContext)
                    }
                }

                // LAST STEP: advance schedule chain & trim old history. Even if fallback mode
                // cancels this worker right here via ExistingWorkPolicy.REPLACE, every terminal
                // artifact (outbox, Room row, ledger, failure notif, trace, Webhook work) is durable.
                if (!isManual) {
                    val successCount = runRepo.countSuccessful(job.id)
                    scheduler.completeNaturalRun(
                        jobId, nowMs, successCount,
                        skipBackupSlotMs = if (isBackup) scheduledAtMs else null,
                    )
                }
                runRepo.trim(jobId, keep = 100)
            }

            return Result.success()
        } catch (c: CancellationException) {
            // A cancelled suspend call is not proof that Room did not commit. Leave STAGED
            // intact so reconciliation can inspect the actual row; COMMITTED can be enqueued.
            recoverOutboxAfterCancellation = stagedWebhookDeliveryId != null
            if (!runRowTerminal) {
                withContext(NonCancellable) {
                    val cancelledAtMs = System.currentTimeMillis()
                    runCatching {
                        diagnosticsStore.record(
                            ScheduleRunTrace(
                                runId = runRowId,
                                jobId = jobId,
                                jobName = jobName ?: jobId,
                                mode = jobMode ?: "?",
                                triggerSource = triggerSource,
                                scheduledAtMs = slotMs,
                                alarmReceivedAtMs = alarmReceivedAtMs,
                                workerStartedAtMs = nowMs,
                                foregroundResult = foregroundResult,
                                foregroundAtMs = foregroundAtMs,
                                finishedAtMs = cancelledAtMs,
                                executionDurationMs = (android.os.SystemClock.elapsedRealtime() - workerStartElapsedMs).coerceAtLeast(0L),
                                outcome = "cancelled",
                                deliveryResult = "worker_cancelled",
                            )
                        )
                    }
                }
            }
            throw c
        } catch (t: Throwable) {
            // an unexpected non-cancellation failure must not silently break the
            // schedule chain. Record the REAL failure (never 'success'), close the ledger
            // if it was opened, then re-schedule the next fire via the scheduler. If even
            // the recovery scheduling fails, let WorkManager retry this worker — the chain
            // is then re-armed by the replay path instead.
            Log.e(TAG, "doWork: unexpected failure for $jobId", t)
            // Only mark the run row failed if it hasn't already reached its terminal
            // outcome — a crash AFTER the terminal update (e.g. inside completeNaturalRun
            // or trim) must never overwrite a success row with 'failed'.
            if (!runRowTerminal) {
                val crashedAtMs = System.currentTimeMillis()
                // Cancel any STAGED pre-commit webhook entry immediately so a failed Room
                // commit followed by this crash handler's `failed` row can never promote or send
                // a contradictory STAGED success payload (P1-3).
                stagedWebhookDeliveryId?.let { delId ->
                    withContext(NonCancellable) {
                        runCatching {
                            deliveryPrefs.markOutboxTerminal(
                                deliveryId = delId,
                                summary = "webhook:aborted_before_commit",
                                nowMs = crashedAtMs,
                                onlyIfStateIn = setOf(WebhookDeliveryState.STAGED),
                                expectedRunId = runRowId,
                            )
                        }
                    }
                }
                runCatching {
                    val failedRow = ScheduledJobRunEntity(
                        id = runRowId,
                        jobId = jobId,
                        mode = jobMode ?: "?",
                        scheduledAtMs = slotMs,
                        startedAtMs = nowMs,
                        finishedAtMs = crashedAtMs,
                        outcome = "failed",
                        conversationId = null,
                        errorMessage = ("worker crashed: ${t::class.simpleName}: ${t.message.orEmpty()}")
                            .take(500),
                    )
                    if (runRowInserted) runRepo.update(failedRow) else runRepo.insert(failedRow)
                }.onFailure { Log.w(TAG, "doWork: failed to record crash row for $jobId", it) }
                runCatching {
                    diagnosticsStore.record(
                        ScheduleRunTrace(
                            runId = runRowId,
                            jobId = jobId,
                            jobName = jobName ?: jobId,
                            mode = jobMode ?: "?",
                            triggerSource = triggerSource,
                            scheduledAtMs = slotMs,
                            alarmReceivedAtMs = alarmReceivedAtMs,
                            workerStartedAtMs = nowMs,
                            foregroundResult = foregroundResult,
                            foregroundAtMs = foregroundAtMs,
                            finishedAtMs = crashedAtMs,
                            executionDurationMs = (android.os.SystemClock.elapsedRealtime() - workerStartElapsedMs).coerceAtLeast(0L),
                            outcome = "failed",
                            deliveryResult = "worker_crashed:${t::class.simpleName}",
                        )
                    )
                }.onFailure { Log.w(TAG, "doWork: failed to record crash diagnostic for $jobId", it) }
            }
            runCatching {
                ledgerId?.let { id ->
                    agentRunRepo.markTerminal(id, AgentRunStatus.failed, t.message)
                }
            }
            // Surface the crash — a silent failure leaves the user unaware that the fire
            // was lost. Falls back to the inputData name (armed via alarm extras) when the
            // Room read itself failed in a cold-started process.
            runCatching {
                postFailureNotification(
                    jobName ?: jobId,
                    "worker crashed: ${t::class.simpleName}: ${t.message.orEmpty()}",
                )
            }
            val successCount = runCatching { runRepo.countSuccessful(jobId) }.getOrDefault(0)
            // A manual fire is bonus — its crash must not bump lastRunAtMs/runsSoFar or
            // touch the regular schedule (spec Decision 13). Only natural fires reschedule.
            if (!isManual) {
                return try {
                    scheduler.completeNaturalRun(
                        jobId, System.currentTimeMillis(), successCount,
                        skipBackupSlotMs = if (isBackup) slotMs else null,
                    )
                    Result.success()
                } catch (resched: CancellationException) {
                    throw resched
                } catch (resched: Throwable) {
                    Log.e(TAG, "doWork: unable to reschedule after failure for $jobId", resched)
                    Result.retry()
                }
            }
            return Result.success()
        } finally {
            CronJobRunningTracker.unregisterRun(runRowId)
            CronJobRunningTracker.stop(jobId)
            if (recoverOutboxAfterCancellation) {
                CronReconcileWorker.enqueueWebhookOutboxRecovery(applicationContext)
            }
        }
    }

    /**
     * Checks whether a job has expired before executing.
     *
     * For max_runs we count actual 'success' rows in the DB rather than trusting
     * job.runsSoFar — this makes max_runs immune to the replay race where the process is
     * killed after the run row is inserted (outcome='running') but before repo.update()
     * persists runsSoFar+1. On replay, countSuccessful() sees the success row only if the
     * run actually completed — an in-flight 'running' row is never counted.
     */
    private suspend fun boundsExpired(job: ScheduledJobEntity, nowMs: Long): Boolean {
        if (job.endAtUnixMs != null && nowMs > job.endAtUnixMs) return true
        if (job.maxRuns != null) {
            val successCount = runRepo.countSuccessful(job.id)
            if (successCount >= job.maxRuns) return true
        }
        return false
    }

    private suspend fun runLlm(job: ScheduledJobEntity): Triple<String, String?, Uuid?> {
        val prompt = job.prompt ?: return Triple("failed", "missing_prompt_for_llm_mode", null)
        val assistantUuid = runCatching { Uuid.parse(job.assistantId) }.getOrNull()
            ?: return Triple("failed", "bad_assistant_id:${job.assistantId}", null)

        val conv = Conversation.ofId(
            id = Uuid.random(),
            assistantId = assistantUuid,
            newConversation = true,
        ).copy(title = "[Scheduled] ${job.name}")
        conversationRepo.insertConversation(conv)
        chatService.initializeConversation(conv.id, updateGlobalAssistant = false)
        HeadlessConversations.mark(conv.id)
        try {
            val turn = chatService.startMessageTurn(conv.id, listOf(UIMessagePart.Text(prompt)))
            val result = awaitLlmTurn(turn)
            return if (result.success) {
                Triple("success", null, conv.id)
            } else {
                Triple("failed", result.errorMessage ?: "llm send failed", conv.id)
            }
        } catch (c: CancellationException) {
            // WorkManager cancellation (deadline, replacement, system preemption) must
            // retain its structured-concurrency semantics so the work can be replayed.
            throw c
        } catch (t: Throwable) {
            return Triple("failed", "${t::class.simpleName}: ${t.message.orEmpty()}", conv.id)
        } finally {
            HeadlessConversations.unmark(conv.id)
        }
    }

    private suspend fun runDirectWithSummaries(job: ScheduledJobEntity): Triple<String, String?, List<String>> {
        val actionsJson = job.actionsJson ?: return Triple("failed", "missing_actions_for_direct_mode", emptyList())
        val parsed = DirectModeActionRunner.parse(actionsJson).getOrElse {
            return Triple("failed", "actions_parse:${(it as? DirectModeActionRunner.ParseError)?.code ?: it.message}", emptyList())
        }
        val assistantUuid = runCatching { Uuid.parse(job.assistantId) }.getOrNull()
            ?: return Triple("failed", "bad_assistant_id:${job.assistantId}", emptyList())
        val settings = settingsStore.awaitLoadedSettings()
        val assistant = settings.findAssistantById(assistantUuid)
            ?: return Triple("failed", "assistant_not_found", emptyList())
        val tools = localTools.getTools(
            assistant.localTools,
            me.rerere.rikkahub.data.ai.tools.ToolInvocationContext(
                callerAssistantId = assistantUuid.toString(),
                callerConversationId = null,
                callerWorkspaceId = assistant.workspaceId?.toString(),
                isHeadless = true,
            ),
        )
        val seq = directRunner.run(parsed, tools)
        return Triple(seq.finalOutcome, seq.errorMessage, seq.stepSummaries)
    }

    private suspend fun recordRun(
        runId: String,
        jobId: String,
        scheduledAtMs: Long,
        outcome: String,
        mode: String,
        convId: Uuid?,
        errorMessage: String?,
    ) {
        // Used for the concurrent_skip early return only. doWork() is already a suspend
        // function on the worker's dispatcher, so this insert runs inline. No runBlocking
        // bridge needed (which would have blocked the worker thread).
        runCatching {
            runRepo.insert(ScheduledJobRunEntity(
                id = runId,
                jobId = jobId,
                mode = mode,
                scheduledAtMs = scheduledAtMs,
                startedAtMs = scheduledAtMs,
                finishedAtMs = scheduledAtMs,
                outcome = outcome,
                conversationId = convId?.toString(),
                errorMessage = errorMessage?.take(500),
            ))
        }.onFailure { Log.w(TAG, "recordRun failed", it) }
    }

    private suspend fun extractDeliverySummary(
        outcome: String,
        errorMessage: String?,
        convId: Uuid?,
        directStepSummaries: List<String>,
    ): String {
        if (outcome != "success") {
            // Send sanitized high-level reason to external webhooks (never leak raw internal
            // exception traces/paths/credentials from errorMessage).
            val sanitizedReason = when {
                outcome == "timed_out" -> "Execution timed out"
                errorMessage?.startsWith("action ") == true -> errorMessage.substringBefore(':').trim() + " failed"
                errorMessage?.startsWith("missing_") == true || errorMessage?.startsWith("bad_") == true ||
                    errorMessage?.startsWith("assistant_not_found") == true || errorMessage?.startsWith("unknown_mode") == true ->
                    errorMessage.substringBefore(':').trim()
                else -> "Execution failed ($outcome). Open RikkaHub Scheduled Jobs for details."
            }
            return sanitizedReason
        }
        if (convId != null) {
            val text = runCatching {
                val conv = conversationRepo.getConversationById(convId) ?: return@runCatching ""
                conv.messageNodes
                    .mapNotNull { it.messages.getOrNull(it.selectIndex) }
                    .lastOrNull { it.role == me.rerere.ai.core.MessageRole.ASSISTANT }
                    ?.toText()
                    ?.trim()
                    .orEmpty()
            }.getOrDefault("")
            if (text.isNotBlank()) return text.take(3000)
        }
        if (directStepSummaries.isNotEmpty()) {
            return directStepSummaries.joinToString("\n").take(3000)
        }
        return "Completed ($outcome)"
    }

    private fun postFailureNotification(jobName: String, errorMessage: String) {
        val ctx = applicationContext
        ensureScheduledJobsHighChannel(ctx)
        val builder = NotificationCompat.Builder(ctx, SCHEDULED_JOBS_HIGH_CHANNEL_ID)
            .setContentTitle("Scheduled job failed")
            .setContentText("$jobName: $errorMessage")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$jobName: $errorMessage"))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVibrate(SCHEDULED_JOB_VIBRATE_PATTERN)
            .setAutoCancel(true)
        try {
            NotificationManagerCompat.from(ctx).notify(jobName.hashCode(), builder.build())
        } catch (_: SecurityException) { /* POST_NOTIFICATIONS not granted — fine */ }
    }

    /**
     * Opt-in lock-screen wake: a heads-up notification carrying a full-screen intent to
     * [CronJobWakeActivity], so a firing job can surface over the keyguard — the alarm-app
     * pattern (Etar/AOSP DeskClock). Only posted when the user enabled the "wake screen
     * on fire" setting (default off).
     */
    private fun postWakeNotification(jobName: String) {
        val ctx = applicationContext
        ensureScheduledJobsHighChannel(ctx)
        val wakeIntent = Intent(ctx, CronJobWakeActivity::class.java)
            .putExtra(CronJobWakeActivity.EXTRA_JOB_NAME, jobName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val wakePi = PendingIntent.getActivity(
            ctx,
            0,
            wakeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(ctx, SCHEDULED_JOBS_HIGH_CHANNEL_ID)
            .setContentTitle(ctx.getString(R.string.cron_job_wake_notification_title))
            .setContentText(jobName)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVibrate(SCHEDULED_JOB_VIBRATE_PATTERN)
            .setContentIntent(wakePi)
            .setFullScreenIntent(wakePi, true)
            .setAutoCancel(true)
        try {
            NotificationManagerCompat.from(ctx).notify(WAKE_NOTIFICATION_ID, builder.build())
        } catch (_: SecurityException) { /* POST_NOTIFICATIONS not granted — fine */ }
    }

    private fun createExecutionForegroundInfo(jobId: String?, jobName: String?): ForegroundInfo {
        val ctx = applicationContext
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(EXECUTION_CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(
                EXECUTION_CHANNEL_ID,
                "Scheduled job execution",
                NotificationManager.IMPORTANCE_LOW,
            ))
        }
        val notification = NotificationCompat.Builder(ctx, EXECUTION_CHANNEL_ID)
            .setContentTitle(ctx.getString(R.string.scheduled_job_execution_notification_title))
            .setContentText(
                jobName ?: ctx.getString(R.string.scheduled_job_execution_notification_preparing)
            )
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .build()
        val notificationId = executionNotificationIdFor(jobId ?: id.toString())
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    /**
     * Allocate a stable, collision-free FGS notification id for one active job. The old
     * `jobId.hashCode() and MASK` scheme could map two different UUIDs to the same id,
     * letting one execution notification clobber another. Sequence-based allocation is
     * the same pattern [ChatService] uses for per-conversation notifications: stable for
     * repeated [getForegroundInfo] calls on the same job, unique across different jobs.
     */
    private fun executionNotificationIdFor(jobId: String): Int =
        executionNotificationIds.computeIfAbsent(jobId) {
            nextExecutionNotificationId.incrementAndGet()
        }

    companion object {
        const val KEY_JOB_ID = "cron_job_id"
        const val KEY_JOB_NAME = "cron_job_name"
        const val KEY_JOB_MODE = "cron_job_mode"
        const val KEY_MANUAL = "cron_job_manual"
        const val KEY_SCHEDULED_AT_MS = "cron_job_scheduled_at_ms"
        const val KEY_ALARM_RECEIVED_AT_MS = "cron_job_alarm_received_at_ms"
        const val KEY_TRIGGER_SOURCE = "cron_job_trigger_source"
        const val KEY_IS_BACKUP = "cron_job_is_backup"
        const val CHANNEL_ID = "rikkahub_cron_jobs"
        private const val WAKE_NOTIFICATION_ID = Int.MAX_VALUE - 102
        private const val EXECUTION_CHANNEL_ID = "rikkahub_cron_execution"
        private const val FOREGROUND_NOTIFICATION_ID_PREFIX = 0x50000000
        private val executionNotificationIds = ConcurrentHashMap<String, Int>()
        private val nextExecutionNotificationId = AtomicInteger(FOREGROUND_NOTIFICATION_ID_PREFIX)
    }
}
