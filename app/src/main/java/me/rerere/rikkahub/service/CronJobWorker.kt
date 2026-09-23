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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
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
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.ScheduledJobRepository
import me.rerere.rikkahub.data.repository.ScheduledJobRunRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private const val TAG = "CronJobWorker"

/** Outer bound on how long after [startedAtMs] a WorkManager replay can plausibly arrive. */
internal const val REPLAY_WINDOW_MS = 10L * 60_000L

/**
 * Wait for the ChatService generation job on [flow] to terminate (transition to null)
 * within a wall-clock [timeoutMs] cap. Returns `true` on natural completion, `false` if
 * the cap fired first.
 *
 * The `Unit` sentinel is load-bearing: `.first { it == null }` returns the matched value
 * (null), and `withTimeoutOrNull` also returns null on timeout. Without the sentinel the
 * two outcomes are indistinguishable — every successful LLM-mode cron run was
 * misclassified as `timed_out` until this fix. (SubAgentEngine carries the same fix
 * inline; this helper exists so a JVM unit test can pin the contract.)
 *
 * NOTE: the cron path now awaits the [SendMessageResult] handle returned by
 * [ChatService.sendMessage] (see [awaitSendMessageResult]) because the flow-based wait
 * cannot distinguish "finished" from "finished with a swallowed error". This helper is
 * retained (with its test) as the pinned contract for the sentinel pattern.
 */
internal suspend fun awaitGenerationTerminal(
    flow: Flow<Job?>,
    timeoutMs: Long,
): Boolean {
    val completed: Unit? = withTimeoutOrNull(timeoutMs) {
        flow.first { it == null }
        Unit
    }
    return completed != null
}

/**
 * Await the completion handle returned by [ChatService.sendMessage] within a wall-clock
 * [timeoutMs] cap. Returns the [SendMessageResult] on natural completion, or `null` if the
 * cap fired first (the run is then reported as `timed_out`).
 */
internal suspend fun awaitSendMessageResult(
    handle: CompletableDeferred<SendMessageResult>,
    timeoutMs: Long,
): SendMessageResult? = withTimeoutOrNull(timeoutMs) { handle.await() }

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
    fun start(jobId: String): Boolean = running.add(jobId)
    fun stop(jobId: String) { running.remove(jobId) }
}

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

        if (!CronJobRunningTracker.start(jobId)) {
            recordRun(jobId, scheduledAtMs = System.currentTimeMillis(),
                outcome = "concurrent_skip", mode = "?", convId = null, errorMessage = null)
            // A natural fire that loses the concurrency race must NOT consume and drop its
            // slot — returning RETRY lets WorkManager re-attempt it once the running fire
            // completes (its own completion keeps the chain alive either way).
            return when (decideConcurrentSkip(isManual)) {
                ConcurrentSkipDecision.SUCCESS -> Result.success()
                ConcurrentSkipDecision.RETRY -> Result.retry()
            }
        }

        val nowMs = System.currentTimeMillis()
        val runRowId = Uuid.random().toString()
        var ledgerId: String? = null
        var runRowInserted = false
        var runRowTerminal = false
        var jobMode: String? = inputData.getString(KEY_JOB_MODE)
        var jobName: String? = inputData.getString(KEY_JOB_NAME)
        var slotMs: Long = nowMs

        try {
            val job = repo.getById(jobId) ?: return Result.success()
            if (!job.enabled) return Result.success()
            jobMode = job.mode
            jobName = job.name

            // WorkManager otherwise imposes a roughly ten-minute execution limit. Promote
            // only while this user-requested job is active; there is no always-on service.
            try {
                setForeground(createExecutionForegroundInfo(jobId, job.name))
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                // Short jobs can still complete under normal WorkManager limits when an OEM
                // rejects foreground promotion. Keep the run alive and retain diagnostics.
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

            // Duplicate-fire guard runs BEFORE the optimistic insert, else the insert
            // self-matches. Manual fires skip the guard so the user can always force a
            // fresh fire. Non-skip rows for EXACTLY this slot are consulted (not the
            // global most-recent row), so a newer concurrent_skip can never mask the real
            // same-slot row, and a natural fire is only ever compared against its own slot.
            // The source-aware [shouldSuppressSameSlotFire] rule applies to both the backup
            // and the primary path, so either racing order is idempotent (a real execution
            // suppresses a duplicate at any age; only legacy manual rows keep the window).
            if (!isManual) {
                val priorRow = runRepo.getMostRecentNonSkipForSlot(jobId, scheduledAtMs)
                val suppress = if (isBackup) shouldSuppressBackupFire(priorRow, nowMs)
                               else shouldSuppressAsReplay(priorRow, scheduledAtMs, nowMs)
                if (suppress) {
                    Log.w(TAG, "doWork: suppressing duplicate fire for $jobId " +
                            "(prior row ${priorRow!!.id} same scheduledAtMs=$scheduledAtMs outcome=${priorRow.outcome} backup=$isBackup)")
                    runRepo.insert(ScheduledJobRunEntity(
                        id = runRowId,
                        jobId = jobId,
                        mode = job.mode,
                        scheduledAtMs = scheduledAtMs,
                        startedAtMs = nowMs,
                        finishedAtMs = nowMs,
                        outcome = "process_killed_replay",
                        conversationId = null,
                        errorMessage = "duplicate fire suppressed (prior run ${priorRow.id})",
                    ))
                    // Suppressing a replay must not break the recursive schedule chain. The
                    // original process may have died after side effects but before it armed
                    // the next slot. One-shots are treated as consumed (at-most-once);
                    // recurring jobs advance from the prior fire while retaining the stable
                    // input slot used above for replay identity. Runs through the scheduler
                    // so the transition is linearized and re-reads the latest Room row; the
                    // scheduler no-ops if the control plane already advanced past the slot,
                    // and never cancels the slot-scoped backup this backup worker is itself
                    // running (it completes naturally).
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
            val wakeOnLockScreen = runCatching {
                settingsStore.settingsFlow.first().scheduledJobWakeOnLockScreen
            }.getOrDefault(false)
            if (!isManual && wakeOnLockScreen) {
                runCatching { postWakeNotification(job.name) }
                    .onFailure { Log.w(TAG, "wake notification failed for $jobId", it) }
            }

            val (outcome, errorMessage, convIdMaybe) = when (job.mode) {
                "llm"    -> runLlm(job)
                "direct" -> runDirect(job)
                else     -> Triple("failed", "unknown_mode:${job.mode}", null)
            }

            runRepo.update(ScheduledJobRunEntity(
                id = runRowId,
                jobId = jobId,
                mode = job.mode,
                scheduledAtMs = scheduledAtMs,
                startedAtMs = nowMs,
                finishedAtMs = System.currentTimeMillis(),
                outcome = outcome,
                conversationId = convIdMaybe?.toString(),
                errorMessage = errorMessage?.take(500),
            ))
            runRowTerminal = true

            // Phase 24 — mirror the terminal outcome into the cross-pillar ledger. Cron
            // outcomes map to: success → succeeded; timed_out / failed / unknown → failed.
            // (concurrent_skip / skipped_catchup / process_killed_replay never reach here —
            // they early-return before the ledger row is opened.)
            ledgerId?.let { id ->
                agentRunRepo.markTerminal(
                    id = id,
                    status = if (outcome == "success") AgentRunStatus.succeeded else AgentRunStatus.failed,
                    lastError = if (outcome == "success") null else "$outcome: ${errorMessage.orEmpty()}",
                )
            }

            // Failure notification (post once per failure; the existing channel handles dedup)
            if (outcome != "success") {
                postFailureNotification(job.name, "$outcome: ${errorMessage.orEmpty()}")
            }

            // Manual fires (trigger_job_now) are bonus — they get a history row but
            // don't bump runs_so_far or lastRunAtMs (per spec Decision 13). The regular
            // schedule continues unaffected.
            if (!isManual) {
                // the terminal transition (lastRunAtMs / runsSoFar / once/maxRuns
                // enabled flip + re-schedule) goes through the scheduler's linearized
                // completeNaturalRun, which re-reads the latest Room row — preserving a
                // user pause/mode edit that landed during the run and never re-arming a
                // paused job. Derive runsSoFar from the authoritative success-count query
                // (post-update, so the final outcome is already committed) to avoid
                // runsSoFar drift after a replay.
                val successCount = runRepo.countSuccessful(job.id)
                // skipBackupSlotMs = this worker's own slot when it IS the backup, so the
                // re-schedule never cancels the backup that is currently executing it.
                scheduler.completeNaturalRun(
                    jobId, nowMs, successCount,
                    skipBackupSlotMs = if (isBackup) scheduledAtMs else null,
                )
            }

            // Trim history at the end so this row's insert/update is reflected in the cap.
            runRepo.trim(jobId, keep = 100)

            return Result.success()
        } catch (c: CancellationException) {
            // WorkManager cancellation (deadline, replacement, system preemption) must
            // retain its structured-concurrency semantics so the work can be replayed.
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
                runCatching {
                    val failedRow = ScheduledJobRunEntity(
                        id = runRowId,
                        jobId = jobId,
                        mode = jobMode ?: "?",
                        scheduledAtMs = slotMs,
                        startedAtMs = nowMs,
                        finishedAtMs = System.currentTimeMillis(),
                        outcome = "failed",
                        conversationId = null,
                        errorMessage = ("worker crashed: ${t::class.simpleName}: ${t.message.orEmpty()}")
                            .take(500),
                    )
                    if (runRowInserted) runRepo.update(failedRow) else runRepo.insert(failedRow)
                }.onFailure { Log.w(TAG, "doWork: failed to record crash row for $jobId", it) }
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
            CronJobRunningTracker.stop(jobId)
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
        chatService.initializeConversation(conv.id)
        HeadlessConversations.mark(conv.id)
        try {
            // sendMessage now returns a completion handle so the cron path observes
            // the REAL success/failure — including provider/network/model exceptions that
            // ChatService catches internally and surfaces via addError. The old
            // flow-goes-null wait cannot distinguish "finished" from "finished with error".
            val handle = chatService.sendMessage(conv.id, listOf(UIMessagePart.Text(prompt)))
            // Wait for the send to terminate, with a 15-min wall-clock cap.
            val result = awaitSendMessageResult(
                handle = handle,
                timeoutMs = 15L * 60_000L,
            )
            return when {
                result == null -> Triple("timed_out", "llm turn exceeded 15min", conv.id)
                result.success -> Triple("success", null, conv.id)
                else -> Triple("failed", result.errorMessage ?: "llm send failed", conv.id)
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

    private suspend fun runDirect(job: ScheduledJobEntity): Triple<String, String?, Uuid?> {
        // Replay idempotency guard: if WorkManager replays this worker after a process kill,
        // a run row for this job will already exist with a very recent startedAtMs.
        // Treat any non-skip row created within the last 5 minutes as a duplicate and bail.
        val actionsJson = job.actionsJson ?: return Triple("failed", "missing_actions_for_direct_mode", null)
        val parsed = DirectModeActionRunner.parse(actionsJson).getOrElse {
            return Triple("failed", "actions_parse:${(it as? DirectModeActionRunner.ParseError)?.code ?: it.message}", null)
        }
        // Tool list scoped to the job's assistant — same path ChatService uses.
        val assistantUuid = runCatching { Uuid.parse(job.assistantId) }.getOrNull()
            ?: return Triple("failed", "bad_assistant_id:${job.assistantId}", null)
        val settings = settingsStore.awaitLoadedSettings()
        val assistant = settings.findAssistantById(assistantUuid)
            ?: return Triple("failed", "assistant_not_found", null)
        // Headless context — sub-agent recursion guard fires from this dispatch path so
        // a cron job's direct-mode action sequence cannot itself spawn a sub-agent.
        val tools = localTools.getTools(
            assistant.localTools,
            me.rerere.rikkahub.data.ai.tools.ToolInvocationContext(
                callerAssistantId = assistantUuid.toString(),
                callerConversationId = null,  // direct-mode has no conversation
                callerWorkspaceId = assistant.workspaceId?.toString(),
                isHeadless = true,
            ),
        )
        val seq = directRunner.run(parsed, tools)
        return Triple(seq.finalOutcome, seq.errorMessage, null)
    }

    private suspend fun recordRun(
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
                id = Uuid.random().toString(),
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
        val notificationId = FOREGROUND_NOTIFICATION_ID_PREFIX or
            ((jobId ?: id.toString()).hashCode() and FOREGROUND_NOTIFICATION_ID_MASK)
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

    companion object {
        const val KEY_JOB_ID = "cron_job_id"
        const val KEY_JOB_NAME = "cron_job_name"
        const val KEY_JOB_MODE = "cron_job_mode"
        const val KEY_MANUAL = "cron_job_manual"
        const val KEY_SCHEDULED_AT_MS = "cron_job_scheduled_at_ms"
        const val KEY_IS_BACKUP = "cron_job_is_backup"
        const val CHANNEL_ID = "rikkahub_cron_jobs"
        private const val WAKE_NOTIFICATION_ID = Int.MAX_VALUE - 102
        private const val EXECUTION_CHANNEL_ID = "rikkahub_cron_execution"
        private const val FOREGROUND_NOTIFICATION_ID_PREFIX = 0x50000000
        private const val FOREGROUND_NOTIFICATION_ID_MASK = 0x0fffffff
    }
}
