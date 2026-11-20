package me.rerere.rikkahub.service

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.entity.ScheduledJobRunEntity
import me.rerere.rikkahub.data.preferences.OutboxClaimResult
import me.rerere.rikkahub.data.preferences.PendingWebhookDelivery
import me.rerere.rikkahub.data.preferences.ScheduleDeliveryConfig
import me.rerere.rikkahub.data.preferences.ScheduleDeliveryPreferences
import me.rerere.rikkahub.data.preferences.WebhookDeliveryState
import me.rerere.rikkahub.data.repository.ScheduledJobRunRepository
import okhttp3.OkHttpClient
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val TAG = "ScheduleWebhookWorker"

/**
 * Durable WorkManager outbox worker for external Webhook delivery.
 *
 * Security & reliability invariants:
 * - **Per-`deliveryId` persistent state machine**: Every claim, retry, and terminal settlement
 *   operates as a conditional atomic update on the single persisted [PendingWebhookDelivery] record
 *   in [ScheduleDeliveryPreferences]. Once settled to [WebhookDeliveryState.TERMINAL], a bounded
 *   tombstone guarantees that stale reconcile snapshots or re-enqueued workers NEVER duplicate a
 *   completed delivery or reset its retry budget.
 * - **Persistent 3-attempt cap**: `attemptsUsed` is durably incremented BEFORE opening an HTTP
 *   socket, so total POST attempts for a `deliveryId` never exceed [MAX_TOTAL_ATTEMPTS] (`3`) even
 *   across process crashes, retries, and reconciliations. Delivery semantics on ambiguous network
 *   failures (process death mid-POST) are at-least-once within this 3-attempt cap; `KEEP` provides
 *   local queue coalescing only and `X-RikkaHub-Delivery-Id` is a best-effort tracing hint (not
 *   guaranteed remote idempotency).
 * - **Recipient isolation**: Every claim validates the live [ScheduleDeliveryConfig] inside the
 *   same atomic transaction against the non-reusable SHA-256 `destinationBinding`. Switching
 *   recipients (even A -> B -> A) or disabling webhooks cancels delivery with zero HTTP bytes sent.
 * - **Interim vs terminal diagnostics**: Retryable failures stamp `lastDeliveryAttemptAtMs` with
 *   `isFinal = false` (leaving `deliveryFinishedAtMs = null`) so UI never displays a pending retry
 *   as finished.
 */
class ScheduleWebhookWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    private val deliveryPrefs: ScheduleDeliveryPreferences by inject()
    private val diagnosticsStore: ScheduleRunDiagnosticsStore by inject()

    override suspend fun doWork(): Result {
        val runId = inputData.getString(KEY_RUN_ID) ?: return Result.success()
        val deliveryId = inputData.getString(KEY_DELIVERY_ID) ?: return Result.success()

        val startElapsedMs = SystemClock.elapsedRealtime()
        val decision = try {
            executePersistedDeliveryAttempt(
                deliveryPrefs = deliveryPrefs,
                diagnosticsStore = diagnosticsStore,
                deliveryId = deliveryId,
                fallbackRunId = runId,
                claimId = "${id}_$runAttemptCount",
                currentBootId = ProcessRuntimeIdentity.currentBootId(applicationContext),
                currentProcessId = ProcessRuntimeIdentity.processInstanceId,
                startElapsedMs = startElapsedMs,
            )
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            runCatching { Log.w(TAG, "webhook worker store error id=$deliveryId err=${t::class.simpleName}") }
            return if ((runAttemptCount + 1) < MAX_TOTAL_ATTEMPTS) Result.retry() else Result.success()
        }

        return if (decision.shouldRetry) Result.retry() else Result.success()
    }

    internal data class AttemptOutcome(
        val shouldRetry: Boolean,
        val diagnosticSummary: String,
        val sentHttpRequest: Boolean,
        val isTerminal: Boolean = !shouldRetry,
    )

    companion object {
        const val KEY_RUN_ID = "run_id"
        const val KEY_DELIVERY_ID = "delivery_id"
        const val KEY_JOB_ID = "job_id"
        const val KEY_JOB_NAME = "job_name"
        const val KEY_MODE = "mode"
        const val KEY_OUTCOME = "outcome"
        const val KEY_SUMMARY = "summary"
        const val KEY_TIMESTAMP_MS = "timestamp_ms"
        const val KEY_DESTINATION_BINDING = "destination_binding"

        /**
         * Hard persistent cap on total HTTP POST attempts per `deliveryId` across all WorkManager
         * retries, reconciliations, and process restarts (1 HTTP POST per attempt).
         */
        const val MAX_TOTAL_ATTEMPTS = 3

        /** Monotonic lease window during which an active `IN_FLIGHT` claim in the same boot & process blocks concurrent double-claim. */
        internal const val IN_FLIGHT_LEASE_MS = 60_000L

        /** Monotonic grace window for same-process non-running STAGED records before reclaiming as orphaned. */
        internal const val SAME_PROCESS_STAGED_ORPHAN_GRACE_MS = 60_000L

        /** Fallback threshold for legacy pre-upgrade STAGED records lacking process/boot owner tags. */
        internal const val LEGACY_STAGED_ORPHAN_MS = 30L * 60_000L

        private val NON_DELIVERABLE_RUN_OUTCOMES = setOf(
            "process_killed_replay",
            "concurrent_skip",
            "skipped_catchup",
        )

        /**
         * Verifies that a terminal Room [row] matches the exact normal-path commit that staged
         * [entry] — never promoting a `STAGED` success payload when the normal Room commit threw
         * and the crash handler persisted a `failed` row for the same `runId` (P1-3).
         */
        internal fun matchesCommittedRunRow(
            entry: PendingWebhookDelivery,
            row: ScheduledJobRunEntity,
        ): Boolean {
            if (row.id != entry.runId || row.jobId != entry.jobId) return false
            val finishedAt = row.finishedAtMs ?: return false
            if (row.outcome in NON_DELIVERABLE_RUN_OUTCOMES) return false
            if (row.outcome != entry.outcome) return false
            if (finishedAt != entry.timestampMs) return false
            if (entry.commitVerificationEnabled) {
                if (row.conversationId != entry.expectedConversationId) return false
                if (row.errorMessage != entry.expectedErrorMessage) return false
            } else {
                if (entry.expectedConversationId != null && row.conversationId != entry.expectedConversationId) {
                    return false
                }
                if (entry.expectedErrorMessage != null && row.errorMessage != entry.expectedErrorMessage) {
                    return false
                }
                if (entry.expectedErrorMessage == null && row.errorMessage?.startsWith("worker crashed:") == true) {
                    return false
                }
            }
            return true
        }

        /**
         * Determines whether a `STAGED` entry whose Room row is still non-terminal (`finishedAtMs == null`)
         * has definitively lost its owner process/coroutine (never misclassifying a live running job).
         */
        internal fun isStagedOwnerLost(
            entry: PendingWebhookDelivery,
            currentBootId: String,
            currentProcessId: String,
            currentElapsedMs: Long,
            nowMs: Long,
            isRunActiveInCurrentProcess: Boolean,
        ): Boolean {
            if (isRunActiveInCurrentProcess) return false
            // 1. Different boot -> prior owner process is guaranteed dead.
            if (!entry.ownerBootId.isNullOrBlank() && entry.ownerBootId != currentBootId) {
                return true
            }
            // 2. Different app process -> single-process app means prior CronJobWorker process died.
            if (!entry.ownerProcessId.isNullOrBlank() && entry.ownerProcessId != currentProcessId) {
                return true
            }
            // 3. Same process, no longer active in CronJobRunningTracker -> orphaned after short monotonic grace.
            if (!entry.ownerProcessId.isNullOrBlank() && entry.ownerProcessId == currentProcessId) {
                val monotonicDelta = currentElapsedMs - entry.stagedElapsedMs
                return monotonicDelta >= SAME_PROCESS_STAGED_ORPHAN_GRACE_MS || monotonicDelta < 0L
            }
            // 4. Legacy pre-upgrade STAGED entry without owner tags -> fall back to 30m non-active staleness.
            return (nowMs - entry.createdAtMs) >= LEGACY_STAGED_ORPHAN_MS
        }

        private const val MAX_SUMMARY_DATA_CHARS = 3_500

        fun workNameFor(deliveryId: String): String = "cron_webhook_$deliveryId"

        /**
         * Verifies that the live [config] is still usable AND bound to the exact non-reusable
         * recipient version captured when the delivery was queued. Returns `null` when authorized,
         * or a non-sensitive cancellation code when blocked.
         */
        internal fun evaluateRecipientGate(
            config: ScheduleDeliveryConfig,
            expectedBinding: String?,
        ): String? {
            if (!config.isWebhookUsable) return "webhook:disabled"
            if (expectedBinding.isNullOrBlank() || config.destinationBinding() != expectedBinding) {
                return "webhook:cancelled_recipient_changed"
            }
            return null
        }

        internal fun shouldRetry(result: WebhookDeliveryResult, attemptsUsed: Int): Boolean =
            !result.success && result.retryable && attemptsUsed < MAX_TOTAL_ATTEMPTS

        /**
         * Core persistent single-attempt executor shared by [doWork] and JVM unit tests.
         *
         * 1. Atomically claims `deliveryId` in [deliveryPrefs] (verifying state != `TERMINAL`,
         *    live recipient binding, active lease, and persistent `attemptsUsed < MAX_TOTAL_ATTEMPTS`,
         *    and durably incrementing `attemptsUsed` BEFORE opening any network socket).
         * 2. Executes at most ONE HTTP POST (`maxAttempts = 1`) using the exact `liveConfig`
         *    validated by the claim transaction.
         * 3. Atomically settles `deliveryId` into `TERMINAL` or `RETRY_WAIT` under `NonCancellable`
         *    and records either final (`isFinal = true`) or interim retry (`isFinal = false`)
         *    diagnostics.
         */
        internal suspend fun executePersistedDeliveryAttempt(
            deliveryPrefs: ScheduleDeliveryPreferences,
            diagnosticsStore: ScheduleRunDiagnosticsStore?,
            deliveryId: String,
            fallbackRunId: String,
            claimId: String = UUID.randomUUID().toString(),
            currentBootId: String = "boot-test",
            currentProcessId: String = ProcessRuntimeIdentity.processInstanceId,
            nowMsProvider: () -> Long = { System.currentTimeMillis() },
            startElapsedMs: Long = ProcessRuntimeIdentity.monotonicNowMs(),
            elapsedMsProvider: () -> Long = { ProcessRuntimeIdentity.monotonicNowMs() },
            clientOverride: OkHttpClient? = null,
        ): AttemptOutcome {
            val claim = deliveryPrefs.claimDeliveryAttempt(
                deliveryId = deliveryId,
                claimId = claimId,
                nowMs = nowMsProvider(),
                currentBootId = currentBootId,
                currentProcessId = currentProcessId,
                currentElapsedMs = elapsedMsProvider(),
                maxTotalAttempts = MAX_TOTAL_ATTEMPTS,
                inFlightLeaseMs = IN_FLIGHT_LEASE_MS,
                recipientGate = ::evaluateRecipientGate,
            )

            return when (claim) {
                is OutboxClaimResult.AlreadySettled -> AttemptOutcome(
                    shouldRetry = false,
                    diagnosticSummary = claim.terminalSummary ?: "webhook:already_settled",
                    sentHttpRequest = false,
                    isTerminal = true,
                )
                OutboxClaimResult.NotCommitted,
                OutboxClaimResult.InFlightElsewhere -> AttemptOutcome(
                    shouldRetry = false,
                    diagnosticSummary = "webhook:deferred",
                    sentHttpRequest = false,
                    isTerminal = false,
                )
                is OutboxClaimResult.Rejected -> {
                    val nowMs = nowMsProvider()
                    val durationMs = (elapsedMsProvider() - startElapsedMs).coerceAtLeast(0L)
                    runCatching {
                        diagnosticsStore?.updateDelivery(
                            runId = fallbackRunId,
                            deliveryFinishedAtMs = nowMs,
                            deliveryDurationMs = durationMs,
                            webhookSummary = claim.reason,
                            isFinal = true,
                        )
                    }
                    AttemptOutcome(
                        shouldRetry = false,
                        diagnosticSummary = claim.reason,
                        sentHttpRequest = false,
                        isTerminal = true,
                    )
                }
                is OutboxClaimResult.BudgetExhausted -> {
                    val nowMs = nowMsProvider()
                    val durationMs = (elapsedMsProvider() - startElapsedMs).coerceAtLeast(0L)
                    runCatching {
                        diagnosticsStore?.updateDelivery(
                            runId = fallbackRunId,
                            deliveryFinishedAtMs = nowMs,
                            deliveryDurationMs = durationMs,
                            webhookSummary = "webhook:attempts_exhausted",
                            isFinal = true,
                        )
                    }
                    AttemptOutcome(
                        shouldRetry = false,
                        diagnosticSummary = "webhook:attempts_exhausted",
                        sentHttpRequest = false,
                        isTerminal = true,
                    )
                }
                is OutboxClaimResult.Claimed -> {
                    val rec = claim.record
                    val payload = ScheduleWebhookPayload(
                        deliveryId = rec.deliveryId,
                        jobId = rec.jobId,
                        jobName = rec.jobName,
                        mode = rec.mode,
                        outcome = rec.outcome,
                        summary = rec.summary,
                        timestampMs = rec.timestampMs,
                    )
                    val httpResult = try {
                        ScheduleWebhookNotifier.deliver(
                            config = claim.liveConfig,
                            payload = payload,
                            clientOverride = clientOverride,
                            maxAttempts = 1,
                        )
                    } catch (c: CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        runCatching { Log.w(TAG, "webhook worker error id=$deliveryId err=${t::class.simpleName}") }
                        WebhookDeliveryResult(
                            attempted = true,
                            success = false,
                            retryable = true,
                            summary = "webhook:error",
                        )
                    }

                    withContext(NonCancellable) {
                        val settledAtMs = nowMsProvider()
                        val durationMs = (elapsedMsProvider() - startElapsedMs).coerceAtLeast(0L)
                        val isTerminal = deliveryPrefs.settleDeliveryAttempt(
                            deliveryId = deliveryId,
                            claimId = claimId,
                            success = httpResult.success,
                            retryable = httpResult.retryable,
                            summary = httpResult.summary,
                            nowMs = settledAtMs,
                            maxTotalAttempts = MAX_TOTAL_ATTEMPTS,
                        )
                        val diagSummary = if (isTerminal) {
                            httpResult.summary
                        } else {
                            val code = httpResult.summary.removePrefix("webhook:")
                            "webhook:retrying(${claim.attemptNumber}/${claim.maxAttempts}:$code)"
                        }
                        runCatching {
                            diagnosticsStore?.updateDelivery(
                                runId = rec.runId,
                                deliveryFinishedAtMs = if (isTerminal) settledAtMs else null,
                                deliveryDurationMs = if (isTerminal) durationMs else null,
                                webhookSummary = diagSummary,
                                attemptAtMs = settledAtMs,
                                attemptDurationMs = durationMs,
                                isFinal = isTerminal,
                            )
                        }
                        AttemptOutcome(
                            shouldRetry = !isTerminal,
                            diagnosticSummary = diagSummary,
                            sentHttpRequest = httpResult.attempted,
                            isTerminal = isTerminal,
                        )
                    }
                }
            }
        }

        /**
         * Stateless single-attempt helper retained for direct unit tests of recipient-gate and
         * single-POST retry cap semantics.
         */
        internal suspend fun executeDeliveryAttempt(
            config: ScheduleDeliveryConfig,
            expectedBinding: String?,
            payload: ScheduleWebhookPayload,
            runAttemptCount: Int,
            clientOverride: OkHttpClient? = null,
        ): AttemptOutcome {
            val rejection = evaluateRecipientGate(config, expectedBinding)
            if (rejection != null) {
                return AttemptOutcome(
                    shouldRetry = false,
                    diagnosticSummary = rejection,
                    sentHttpRequest = false,
                    isTerminal = true,
                )
            }

            val result = try {
                ScheduleWebhookNotifier.deliver(
                    config = config,
                    payload = payload,
                    clientOverride = clientOverride,
                    maxAttempts = 1,
                )
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                runCatching { Log.w(TAG, "webhook worker error id=${payload.deliveryId} err=${t::class.simpleName}") }
                WebhookDeliveryResult(
                    attempted = true,
                    success = false,
                    retryable = true,
                    summary = "webhook:error",
                )
            }

            val retry = shouldRetry(result, attemptsUsed = runAttemptCount + 1)
            return AttemptOutcome(
                shouldRetry = retry,
                diagnosticSummary = result.summary,
                sentHttpRequest = result.attempted,
                isTerminal = !retry,
            )
        }

        internal fun buildInputData(
            runId: String,
            payload: ScheduleWebhookPayload,
            destinationBinding: String,
        ): Data =
            Data.Builder()
                .putString(KEY_RUN_ID, runId)
                .putString(KEY_DELIVERY_ID, payload.deliveryId)
                .putString(KEY_JOB_ID, payload.jobId)
                .putString(KEY_JOB_NAME, payload.jobName.take(200))
                .putString(KEY_MODE, payload.mode.take(32))
                .putString(KEY_OUTCOME, payload.outcome.take(64))
                .putString(KEY_SUMMARY, payload.summary.take(MAX_SUMMARY_DATA_CHARS))
                .putLong(KEY_TIMESTAMP_MS, payload.timestampMs)
                .putString(KEY_DESTINATION_BINDING, destinationBinding)
                .build()

        /**
         * Durably enqueues Webhook delivery under a delivery-scoped unique name and awaits
         * WorkManager DB persistence. `KEEP` coalesces duplicate local queue entries while active;
         * cross-run deduplication and persistent retry budgets are enforced by the per-`deliveryId`
         * state machine inside [ScheduleDeliveryPreferences].
         */
        suspend fun enqueueAwait(
            context: Context,
            runId: String,
            payload: ScheduleWebhookPayload,
            destinationBinding: String,
        ) {
            val req = OneTimeWorkRequestBuilder<ScheduleWebhookWorker>()
                .setInputData(buildInputData(runId, payload, destinationBinding))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .addTag("cron_webhook")
                .addTag("cron_webhook_job_${payload.jobId}")
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(workNameFor(payload.deliveryId), ExistingWorkPolicy.KEEP, req)
                .await()
        }

        /**
         * Reconciles durable outbox entries against live state and (for `STAGED` records only)
         * committed Room run rows.
         *
         * - `COMMITTED` / `RETRY_WAIT` records NEVER depend on Room run history (immune to trim).
         * - Re-checks live state before enqueueing, AND even if a Worker settles concurrently,
         *   the persisted `TERMINAL` tombstone guarantees zero duplicate HTTP delivery.
         * - Propagates enqueue errors so [CronReconcileWorker] retries rather than dropping work.
         */
        suspend fun reconcilePendingOutbox(
            context: Context,
            deliveryPrefs: ScheduleDeliveryPreferences,
            runRepo: ScheduledJobRunRepository,
            diagnosticsStore: ScheduleRunDiagnosticsStore,
        ) {
            val currentBootId = ProcessRuntimeIdentity.currentBootId(context)
            val currentProcessId = ProcessRuntimeIdentity.processInstanceId
            reconcilePendingOutboxWith(
                deliveryPrefs = deliveryPrefs,
                lookupRun = { runId -> runRepo.getById(runId) },
                onEnqueue = { entry ->
                    enqueueAwait(
                        context = context,
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
                },
                onUpdateDiagnostics = { runId, summary ->
                    runCatching {
                        diagnosticsStore.updateDelivery(
                            runId = runId,
                            deliveryFinishedAtMs = System.currentTimeMillis(),
                            deliveryDurationMs = 0L,
                            webhookSummary = summary,
                            isFinal = true,
                        )
                    }
                },
                currentBootId = currentBootId,
                currentProcessId = currentProcessId,
                isRunActiveInCurrentProcess = { runId -> isCronRunActiveInCurrentProcess(runId) },
                onMarkOrphanedRunStranded = { runRow, nowMs ->
                    runCatching {
                        if (runRow.finishedAtMs == null) {
                            runRepo.update(
                                runRow.copy(
                                    finishedAtMs = nowMs,
                                    outcome = "process_killed_replay",
                                    errorMessage = "worker terminated before terminal commit",
                                )
                            )
                            diagnosticsStore.markStrandedTerminal(
                                runId = runRow.id,
                                outcome = "process_killed_replay",
                                finishedAtMs = nowMs,
                                deliveryResult = "process_terminated",
                            )
                        }
                    }
                },
            )
        }

        internal suspend fun reconcilePendingOutboxWith(
            deliveryPrefs: ScheduleDeliveryPreferences,
            lookupRun: suspend (String) -> ScheduledJobRunEntity?,
            onEnqueue: suspend (PendingWebhookDelivery) -> Unit,
            onUpdateDiagnostics: suspend (runId: String, summary: String) -> Unit,
            currentBootId: String = "boot-test",
            currentProcessId: String = ProcessRuntimeIdentity.processInstanceId,
            nowMsProvider: () -> Long = { System.currentTimeMillis() },
            elapsedMsProvider: () -> Long = { ProcessRuntimeIdentity.monotonicNowMs() },
            isRunActiveInCurrentProcess: (String) -> Boolean = { false },
            onMarkOrphanedRunStranded: (suspend (ScheduledJobRunEntity, Long) -> Unit)? = null,
        ) {
            val activeSnapshot = deliveryPrefs.allPendingWebhooks()
            if (activeSnapshot.isEmpty()) return

            for (snapshotEntry in activeSnapshot) {
                val nowMs = nowMsProvider()
                val elapsedMs = elapsedMsProvider()
                // Re-read the live record before acting so a settlement after the initial snapshot
                // read never triggers a pointless re-enqueue.
                var live = deliveryPrefs.getOutboxRecord(snapshotEntry.deliveryId) ?: continue
                if (live.state == WebhookDeliveryState.TERMINAL) continue

                if (live.state == WebhookDeliveryState.STAGED) {
                    val runRow = lookupRun(live.runId)
                    when {
                        runRow == null -> {
                            deliveryPrefs.markOutboxTerminal(
                                deliveryId = live.deliveryId,
                                summary = "webhook:uncommitted_discarded",
                                nowMs = nowMs,
                                onlyIfStateIn = setOf(WebhookDeliveryState.STAGED),
                                expectedRunId = live.runId,
                            )
                            continue
                        }
                        runRow.finishedAtMs == null -> {
                            val ownerLost = isStagedOwnerLost(
                                entry = live,
                                currentBootId = currentBootId,
                                currentProcessId = currentProcessId,
                                currentElapsedMs = elapsedMs,
                                nowMs = nowMs,
                                isRunActiveInCurrentProcess = isRunActiveInCurrentProcess(live.runId),
                            )
                            if (ownerLost) {
                                val marked = deliveryPrefs.markOutboxTerminal(
                                    deliveryId = live.deliveryId,
                                    summary = "webhook:owner_process_lost",
                                    nowMs = nowMs,
                                    onlyIfStateIn = setOf(WebhookDeliveryState.STAGED),
                                    expectedRunId = live.runId,
                                )
                                if (marked) {
                                    onMarkOrphanedRunStranded?.invoke(runRow, nowMs)
                                }
                            }
                            continue
                        }
                        !matchesCommittedRunRow(live, runRow) -> {
                            // Room reached a different terminal outcome/timestamp (e.g. normal commit
                            // threw and catch handler wrote `failed`, or stranded sweep marked
                            // `process_killed_replay`): NEVER promote a contradictory STAGED payload.
                            deliveryPrefs.markOutboxTerminal(
                                deliveryId = live.deliveryId,
                                summary = "webhook:commit_mismatch_discarded",
                                nowMs = nowMs,
                                onlyIfStateIn = setOf(WebhookDeliveryState.STAGED),
                                expectedRunId = live.runId,
                            )
                            continue
                        }
                        else -> {
                            if (!deliveryPrefs.markOutboxCommitted(live.deliveryId, nowMs, expectedRunId = live.runId)) continue
                            live = deliveryPrefs.getOutboxRecord(live.deliveryId) ?: continue
                            if (live.state == WebhookDeliveryState.TERMINAL) continue
                        }
                    }
                }

                // Skip actively leased IN_FLIGHT attempts using monotonic boot/process lease;
                // dead-process, cross-boot, or expired leases fall through for explicit recovery.
                if (
                    PendingWebhookDelivery.isInFlightLeaseActive(
                        record = live,
                        currentBootId = currentBootId,
                        currentProcessId = currentProcessId,
                        currentElapsedMs = elapsedMs,
                        leaseMs = IN_FLIGHT_LEASE_MS,
                    )
                ) {
                    continue
                }

                val liveConfig = deliveryPrefs.current()
                val rejection = evaluateRecipientGate(liveConfig, live.destinationBinding)
                if (rejection != null) {
                    val marked = deliveryPrefs.markOutboxTerminal(
                        deliveryId = live.deliveryId,
                        summary = rejection,
                        nowMs = nowMs,
                    )
                    if (marked) {
                        onUpdateDiagnostics(live.runId, rejection)
                    }
                    continue
                }

                if (live.attemptsUsed >= MAX_TOTAL_ATTEMPTS) {
                    val marked = deliveryPrefs.markOutboxTerminal(
                        deliveryId = live.deliveryId,
                        summary = "webhook:attempts_exhausted",
                        nowMs = nowMs,
                    )
                    if (marked) {
                        onUpdateDiagnostics(live.runId, "webhook:attempts_exhausted")
                    }
                    continue
                }

                // Final live-state check immediately before enqueueing.
                val fresh = deliveryPrefs.getOutboxRecord(live.deliveryId) ?: continue
                if (fresh.state == WebhookDeliveryState.TERMINAL) continue
                onEnqueue(fresh)
            }
        }
    }
}
