package me.rerere.rikkahub.workflow.trigger

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit

private val Context.timeCronSlotDataStore by preferencesDataStore(name = "workflow_timecron_slot_checkpoints")

/**
 * Durable slot checkpoints for one-shot TimeCron workers:
 * - [CLAIMED]: persisted before firing. On replay the action's outcome is unknown; only renew.
 * - [FIRED_PENDING_RENEWAL]: the current slot's side-effect (`cb.onFire` or weekday skip) already
 *   completed; renewal enqueue for the next slot has not yet been confirmed recorded.
 * - [RENEWED]: the next slot has been durably enqueued in WorkManager; this checkpoint MUST remain
 *   persisted until WorkManager confirms THIS WorkRequest reached a terminal (`state.isFinished`)
 *   state so a crash between `enqueueOneShot` and `Result.success()` commit neither re-fires the
 *   action nor appends a duplicate next slot.
 */
@Serializable
internal enum class TimeCronSlotPhase {
    CLAIMED,
    FIRED_PENDING_RENEWAL,
    RENEWED,
}

@Serializable
internal data class TimeCronSlotCheckpoint(
    val slotKey: String,
    val workflowId: String,
    val phase: TimeCronSlotPhase,
    val renewalTag: String,
    val updatedAtMs: Long,
)

/**
 * Durable per-WorkRequest slot checkpoint store.
 *
 * Contract:
 * - [upsert] MUST throw on persistence failure (never silently ignore write errors).
 * - Active checkpoints MUST NEVER be evicted by count; cleanup occurs exclusively via
 *   [removeConfirmedFinished] once WorkManager confirms a WorkRequest has finished.
 */
internal interface TimeCronFiredSlotStore {
    suspend fun get(slotKey: String): TimeCronSlotCheckpoint?
    suspend fun allCheckpoints(): List<TimeCronSlotCheckpoint>
    suspend fun upsert(checkpoint: TimeCronSlotCheckpoint)
    suspend fun removeConfirmedFinished(finishedSlotKeys: Set<String>)
}

internal class DataStoreTimeCronSlotStore internal constructor(
    private val dataStore: DataStore<Preferences>,
) : TimeCronFiredSlotStore {
    constructor(context: Context) : this(context.timeCronSlotDataStore)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun get(slotKey: String): TimeCronSlotCheckpoint? =
        allCheckpoints().firstOrNull { it.slotKey == slotKey }

    override suspend fun allCheckpoints(): List<TimeCronSlotCheckpoint> =
        decode(dataStore.data.first()[KEY_CHECKPOINTS])

    override suspend fun upsert(checkpoint: TimeCronSlotCheckpoint) {
        // DataStore.edit atomically writes temp file + fsync + rename and throws IOException on
        // failure (never silently dropping persistence errors or evicting active checkpoints).
        dataStore.edit { prefs ->
            val current = decode(prefs[KEY_CHECKPOINTS]).toMutableList()
            current.removeAll { it.slotKey == checkpoint.slotKey }
            current.add(checkpoint)
            prefs[KEY_CHECKPOINTS] = json.encodeToString(
                ListSerializer(TimeCronSlotCheckpoint.serializer()),
                current,
            )
        }
    }

    override suspend fun removeConfirmedFinished(finishedSlotKeys: Set<String>) {
        if (finishedSlotKeys.isEmpty()) return
        dataStore.edit { prefs ->
            val current = decode(prefs[KEY_CHECKPOINTS])
            val next = current.filterNot { it.slotKey in finishedSlotKeys }
            if (next.size == current.size) return@edit
            if (next.isEmpty()) {
                prefs.remove(KEY_CHECKPOINTS)
            } else {
                prefs[KEY_CHECKPOINTS] = json.encodeToString(
                    ListSerializer(TimeCronSlotCheckpoint.serializer()),
                    next,
                )
            }
        }
    }

    private fun decode(raw: String?): List<TimeCronSlotCheckpoint> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(TimeCronSlotCheckpoint.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    companion object {
        private val KEY_CHECKPOINTS = stringPreferencesKey("slot_checkpoints_json")
    }
}

/**
 * Abstraction over WorkManager operations used by [TimeCronTriggerFamily] so migration,
 * bounded sync retries, idempotent renewal, and post-termination cleanup can be unit-tested on JVM.
 */
internal interface TimeCronWorkAdapter {
    suspend fun queryLegacyEntries(workflowId: String): List<TimeCronPolicy.LegacyWorkEntry>
    suspend fun cancelLegacyWork(workflowId: String)
    suspend fun cancelOneShotWork(workflowId: String)
    suspend fun enqueuePeriodic(
        workflowId: String,
        periodMs: Long,
        delayMs: Long,
        policy: ExistingPeriodicWorkPolicy,
    )
    suspend fun enqueueOneShot(
        workflowId: String,
        delayMs: Long,
        policy: ExistingWorkPolicy,
        renewalTag: String? = null,
    )
    /** Returns `true` if a non-terminal next-slot WorkRequest bearing [renewalTag] is already queued. */
    suspend fun hasActiveRenewal(workflowId: String, renewalTag: String): Boolean
    /** Returns `true` ONLY when WorkManager confirms [workId] is finished (`state.isFinished` or pruned). */
    suspend fun isWorkRequestFinished(workId: String): Boolean
}

private class WorkManagerTimeCronAdapter(private val context: Context) : TimeCronWorkAdapter {
    override suspend fun queryLegacyEntries(workflowId: String): List<TimeCronPolicy.LegacyWorkEntry> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(TimeCronTriggerFamily.workName(workflowId))
            .first()
            .map { info ->
                TimeCronPolicy.LegacyWorkEntry(
                    // Recognize ALL non-terminal WorkManager states (ENQUEUED, RUNNING, BLOCKED).
                    isActive = !info.state.isFinished,
                    isPeriodic = info.periodicityInfo != null,
                )
            }

    override suspend fun cancelLegacyWork(workflowId: String) {
        WorkManager.getInstance(context)
            .cancelUniqueWork(TimeCronTriggerFamily.workName(workflowId))
            .await()
    }

    override suspend fun cancelOneShotWork(workflowId: String) {
        WorkManager.getInstance(context)
            .cancelUniqueWork(TimeCronTriggerFamily.workNameOneShot(workflowId))
            .await()
    }

    override suspend fun enqueuePeriodic(
        workflowId: String,
        periodMs: Long,
        delayMs: Long,
        policy: ExistingPeriodicWorkPolicy,
    ) {
        val req = PeriodicWorkRequestBuilder<WorkflowTimeCronWorker>(periodMs, TimeUnit.MILLISECONDS)
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(
                TimeCronTriggerFamily.KEY_WORKFLOW_ID to workflowId,
                TimeCronTriggerFamily.KEY_PERIODIC_V1 to true,
            ))
            .setConstraints(Constraints.NONE)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(TimeCronTriggerFamily.workName(workflowId), policy, req)
            .await()
    }

    override suspend fun enqueueOneShot(
        workflowId: String,
        delayMs: Long,
        policy: ExistingWorkPolicy,
        renewalTag: String?,
    ) {
        val builder = OneTimeWorkRequestBuilder<WorkflowTimeCronWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(
                TimeCronTriggerFamily.KEY_WORKFLOW_ID to workflowId,
                TimeCronTriggerFamily.KEY_PERIODIC_V1 to false,
            ))
        if (!renewalTag.isNullOrBlank()) {
            builder.addTag(renewalTag)
        }
        WorkManager.getInstance(context)
            .enqueueUniqueWork(TimeCronTriggerFamily.workNameOneShot(workflowId), policy, builder.build())
            .await()
    }

    override suspend fun hasActiveRenewal(workflowId: String, renewalTag: String): Boolean =
        WorkManager.getInstance(context)
            .getWorkInfosByTagFlow(renewalTag)
            .first()
            .any { info ->
                info.state != androidx.work.WorkInfo.State.CANCELLED &&
                    info.state != androidx.work.WorkInfo.State.FAILED
            }

    override suspend fun isWorkRequestFinished(workId: String): Boolean {
        val uuid = runCatching { UUID.fromString(workId) }.getOrNull() ?: return true
        val info = WorkManager.getInstance(context)
            .getWorkInfoByIdFlow(uuid)
            .first()
        return info == null || info.state.isFinished
    }
}

/**
 * Phase 12 - time / cron family.
 *
 * Scheduling & recovery guarantees:
 * - Initial / newly-added schedule uses KEEP so an existing (possibly in-flight) worker isn't
 *   cancelled; real config edits use UPDATE (periodic) or REPLACE (one-shot).
 * - Legacy pre-upgrade one-shot entries in ANY non-terminal state (`ENQUEUED`, `RUNNING`,
 *   `BLOCKED`) are preserved; transient lookup or enqueue failures during `sync()` trigger a
 *   bounded independent retry loop without relying on another workflow definition edit.
 * - One-shot actions are durably claimed at the action boundary. A retried claimed slot only
 *   renews, using a stable per-slot tag. Checkpoints are pruned only after WorkManager confirms
 *   the slot finished.
 */
internal class TimeCronTriggerFamily internal constructor(
    private val scope: CoroutineScope,
    private val workAdapter: TimeCronWorkAdapter,
    private val firedSlotStore: TimeCronFiredSlotStore,
    private val syncRetryDelaysMs: LongArray = DEFAULT_SYNC_RETRY_DELAYS_MS,
    private val repoFallbackLookup: suspend (String) -> WorkflowDefinition? = { workflowId ->
        TimeCronWorkerHelper.repositoryLookup(workflowId)
            ?.takeIf { it.entity.enabled }
            ?.definition
    },
    private val engineCallbackFallback: () -> TriggerFireCallback = {
        TimeCronWorkerHelper.resolveEngineCallback()
    },
) : WorkflowTriggerFamily {

    constructor(context: Context, scope: CoroutineScope) : this(
        scope = scope,
        workAdapter = WorkManagerTimeCronAdapter(context),
        firedSlotStore = DataStoreTimeCronSlotStore(context),
    )

    override val name = "time_cron"

    private val syncMutex = Mutex()
    @Volatile private var lastSnapshot: List<WorkflowDefinition> = emptyList()
    @Volatile private var desiredSnapshot: List<WorkflowDefinition> = emptyList()
    @Volatile private var fireCallback: TriggerFireCallback? = null
    private var retryJob: Job? = null

    override fun handles(spec: TriggerSpec): Boolean = spec is TriggerSpec.TimeCron

    override suspend fun sync(matching: List<WorkflowDefinition>, callback: TriggerFireCallback) {
        syncMutex.withLock {
            fireCallback = callback
            desiredSnapshot = matching
            retryJob?.cancel()
            retryJob = null
            val hasDeferred = syncLocked(matching)
            if (hasDeferred && syncRetryDelaysMs.isNotEmpty()) {
                scheduleBoundedRetryLocked()
            }
        }
        runCatching { pruneConfirmedFinishedCheckpoints(excludeSlotKey = null) }
    }

    private suspend fun syncLocked(matching: List<WorkflowDefinition>): Boolean {
        val previous = lastSnapshot.associateBy { it.id }
        val current = matching.associateBy { it.id }
        // Cancel removed
        for (id in previous.keys - current.keys) {
            cancelWork(id)
        }
        // Schedule added or changed; retain prior entry (or omit) when deferred on transient error
        // so bounded retries re-attempt without waiting for a workflow definition change.
        val committed = mutableListOf<WorkflowDefinition>()
        var hasDeferred = false
        for ((id, wf) in current) {
            val prev = previous[id]
            if (TimeCronPolicy.needsScheduling(prev, wf)) {
                val ok = scheduleWork(wf, TimeCronPolicy.isNewSchedule(prev))
                if (ok) {
                    committed += wf
                } else {
                    hasDeferred = true
                    if (prev != null) {
                        committed += prev
                    }
                }
            } else {
                committed += wf
            }
        }
        lastSnapshot = committed
        return hasDeferred
    }

    private fun scheduleBoundedRetryLocked() {
        retryJob = scope.launch {
            for (attempt in syncRetryDelaysMs.indices) {
                delay(syncRetryDelaysMs[attempt])
                val stillDeferred = syncMutex.withLock {
                    if (fireCallback == null || desiredSnapshot.isEmpty()) return@launch
                    syncLocked(desiredSnapshot)
                }
                if (!stillDeferred) return@launch
            }
            runCatching {
                Log.w(TAG, "time_cron: exhausted ${syncRetryDelaysMs.size} bounded sync retries")
            }
        }
    }

    /**
     * Removes checkpoints ONLY for WorkRequests confirmed finished by WorkManager (`isFinished`
     * or pruned). Never removes [excludeSlotKey] (the currently executing WorkRequest) and never
     * evicts active/unconfirmed checkpoints by count.
     */
    internal suspend fun pruneConfirmedFinishedCheckpoints(excludeSlotKey: String? = null) {
        val all = try {
            firedSlotStore.allCheckpoints()
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            return
        }
        if (all.isEmpty()) return
        val finishedKeys = mutableSetOf<String>()
        for (cp in all) {
            if (cp.slotKey == excludeSlotKey) continue
            val confirmedFinished = try {
                workAdapter.isWorkRequestFinished(cp.slotKey)
            } catch (c: CancellationException) {
                throw c
            } catch (_: Throwable) {
                // Fail safe: query failure does NOT mean the request finished — keep checkpoint.
                false
            }
            if (confirmedFinished) {
                finishedKeys += cp.slotKey
            }
        }
        if (finishedKeys.isNotEmpty()) {
            try {
                firedSlotStore.removeConfirmedFinished(finishedKeys)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                runCatching { Log.w(TAG, "time_cron: failed to prune finished checkpoints", t) }
            }
        }
    }

    override suspend fun shutdown() {
        syncMutex.withLock {
            retryJob?.cancel()
            retryJob = null
            desiredSnapshot = emptyList()
            for (wf in lastSnapshot) cancelWork(wf.id)
            lastSnapshot = emptyList()
            fireCallback = null
        }
    }

    suspend fun cancelWork(workflowId: String) {
        try {
            workAdapter.cancelLegacyWork(workflowId)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            runCatching { Log.w(TAG, "time_cron: cancel periodic work failed for $workflowId", t) }
        }
        try {
            workAdapter.cancelOneShotWork(workflowId)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            runCatching { Log.w(TAG, "time_cron: cancel one-shot work failed for $workflowId", t) }
        }
    }

    private suspend fun scheduleWork(
        wf: WorkflowDefinition,
        isNew: Boolean = true,
        appendAfterCurrent: Boolean = false,
        rethrowOnFailure: Boolean = false,
        renewalTag: String? = null,
    ): Boolean {
        val spec = wf.trigger as? TriggerSpec.TimeCron ?: return true
        val zone = spec.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()

        val periodMs = derivePeriodMs(spec)
        if (periodMs != null && periodMs >= 15 * 60 * 1000L) {
            return try {
                val activeLegacy = workAdapter.queryLegacyEntries(wf.id).filter { it.isActive }
                val hasLegacyOneShot = activeLegacy.any { !it.isPeriodic }
                // KEEP would otherwise retain a pre-upgrade one-shot occupying the periodic name.
                if (hasLegacyOneShot) workAdapter.cancelLegacyWork(wf.id)
                workAdapter.cancelOneShotWork(wf.id)

                val nextFireMs = computeNextFireMs(spec, zone, System.currentTimeMillis())
                val delayMs = (nextFireMs - System.currentTimeMillis()).coerceAtLeast(0L)
                val policy = if (!isNew && !hasLegacyOneShot && activeLegacy.any { it.isPeriodic }) {
                    ExistingPeriodicWorkPolicy.UPDATE
                } else {
                    ExistingPeriodicWorkPolicy.KEEP
                }
                workAdapter.enqueuePeriodic(wf.id, periodMs, delayMs, policy)
                true
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                runCatching { Log.w(TAG, "time_cron: periodic migration or enqueue failed for ${wf.id}", t) }
                if (rethrowOnFailure) throw t
                false
            }
        }

        var legacyLookupFailed = false
        val legacyEntries: List<TimeCronPolicy.LegacyWorkEntry> = if (!appendAfterCurrent) {
            try {
                workAdapter.queryLegacyEntries(wf.id)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                legacyLookupFailed = true
                runCatching { Log.w(TAG, "time_cron: legacy work lookup failed for ${wf.id}; deferring migration", t) }
                emptyList()
            }
        } else {
            emptyList()
        }

        when (
            TimeCronPolicy.decideLegacyOneShotMigration(
                isNew = isNew,
                appendAfterCurrent = appendAfterCurrent,
                legacyEntries = legacyEntries,
                legacyLookupFailed = legacyLookupFailed,
            )
        ) {
            TimeCronPolicy.LegacyOneShotMigrationAction.KEEP_LEGACY_ONE_SHOT -> {
                return !legacyLookupFailed
            }
            TimeCronPolicy.LegacyOneShotMigrationAction.CANCEL_LEGACY_AND_ENQUEUE_NEW -> {
                try {
                    workAdapter.cancelLegacyWork(wf.id)
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    runCatching { Log.w(TAG, "time_cron: stale legacy cancel failed for ${wf.id}", t) }
                    if (rethrowOnFailure) throw t
                    return false
                }
            }
            TimeCronPolicy.LegacyOneShotMigrationAction.ENQUEUE_NEW_ONLY -> Unit
        }

        val nextFireMs = computeNextFireMs(spec, zone, System.currentTimeMillis())
        val delayMs = (nextFireMs - System.currentTimeMillis()).coerceAtLeast(60_000L)
        val policy = when {
            isNew -> ExistingWorkPolicy.KEEP
            appendAfterCurrent -> ExistingWorkPolicy.APPEND_OR_REPLACE
            else -> ExistingWorkPolicy.REPLACE
        }
        return try {
            workAdapter.enqueueOneShot(wf.id, delayMs, policy, renewalTag = renewalTag)
            true
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            runCatching { Log.w(TAG, "time_cron: one-shot enqueue failed for ${wf.id}", t) }
            if (rethrowOnFailure) throw t
            false
        }
    }

    /**
     * Fires the workflow and durably renews the next one-shot slot without duplicate execution or
     * duplicate chain appends across crashes:
     *
     * - If checkpoint is [TimeCronSlotPhase.RENEWED]: skips BOTH `cb.onFire` and renewal enqueue.
     * - If checkpoint is [TimeCronSlotPhase.CLAIMED] or [TimeCronSlotPhase.FIRED_PENDING_RENEWAL]:
     *   skips `cb.onFire`, checks
     *   [TimeCronWorkAdapter.hasActiveRenewal] before enqueueing so a crash between `enqueueOneShot`
     *   and `RENEWED` commit never appends a second next slot, then records `RENEWED`.
     * - Never removes the current slot's checkpoint before returning `Result.success()`; cleanup
     *   only runs once WorkManager confirms the WorkRequest is finished.
     * - A fresh one-shot must persist its claim before invoking the action. If recording the claim
     *   fails, WorkManager can retry without repeating any side effect.
     */
    suspend fun onWorkerFired(
        workflowId: String,
        workerExecutionId: String? = null,
        requestIsPeriodic: Boolean? = null,
    ) {
        // Best-effort cleanup of OLDER WorkManager-confirmed finished checkpoints (never current slot).
        runCatching { pruneConfirmedFinishedCheckpoints(excludeSlotKey = workerExecutionId) }

        // A persisted edit can precede registry sync, so a worker must use the current definition.
        val wf = repoFallbackLookup(workflowId) ?: return
        val spec = wf.trigger as? TriggerSpec.TimeCron ?: return
        val periodMs = derivePeriodMs(spec)
        val needsOneShotRenewal = TimeCronPolicy.shouldReEnqueueOneShot(periodMs)
        if (requestIsPeriodic != null && requestIsPeriodic == needsOneShotRenewal) return

        val existingCheckpoint = workerExecutionId?.let { firedSlotStore.get(it) }

        when (existingCheckpoint?.phase) {
            TimeCronSlotPhase.RENEWED -> {
                // Process died after next slot was enqueued and RENEWED persisted, before
                // WorkManager marked THIS WorkRequest SUCCEEDED. Do not re-fire or re-enqueue.
                return
            }
            TimeCronSlotPhase.CLAIMED, TimeCronSlotPhase.FIRED_PENDING_RENEWAL -> {
                if (existingCheckpoint.phase == TimeCronSlotPhase.CLAIMED) {
                    runCatching {
                        Log.w(TAG, "time_cron: outcome unknown for ${wf.id} ($workerExecutionId); not re-firing")
                    }
                }
                if (needsOneShotRenewal) renewClaimedSlot(wf, existingCheckpoint)
                return
            }
            null -> Unit
        }

        // Fresh slot execution: evaluate weekday filter and fire action if eligible.
        var shouldFire = true
        if (!spec.timeOfDay.isNullOrBlank() && spec.daysOfWeek.isNotEmpty()) {
            val zone = spec.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() }
                ?: ZoneId.systemDefault()
            val today = ZonedDateTime.now(zone).dayOfWeek
            if (today !in spec.daysOfWeek.map { isoDow(it) }) {
                runCatching { Log.d(TAG, "time_cron: $workflowId skipped, $today not in days_of_week") }
                shouldFire = false
            }
        }
        if (!needsOneShotRenewal || workerExecutionId == null) {
            if (shouldFire) (fireCallback ?: engineCallbackFallback()).onScheduledFire(wf.id, spec) {}
            if (needsOneShotRenewal) {
                scheduleWork(wf, isNew = false, appendAfterCurrent = true, rethrowOnFailure = true)
            }
            return
        }

        val claim = TimeCronSlotCheckpoint(
            slotKey = workerExecutionId,
            workflowId = wf.id,
            phase = TimeCronSlotPhase.CLAIMED,
            renewalTag = renewalTagFor(workerExecutionId),
            updatedAtMs = System.currentTimeMillis(),
        )

        if (shouldFire) {
            (fireCallback ?: engineCallbackFallback()).onScheduledFire(wf.id, spec) {
                firedSlotStore.upsert(claim)
            }
        }

        val fired = claim.copy(
            phase = TimeCronSlotPhase.FIRED_PENDING_RENEWAL,
            updatedAtMs = System.currentTimeMillis(),
        )
        firedSlotStore.upsert(fired)
        renewClaimedSlot(wf, fired)
    }

    private suspend fun renewClaimedSlot(wf: WorkflowDefinition, checkpoint: TimeCronSlotCheckpoint) {
        if (!workAdapter.hasActiveRenewal(wf.id, checkpoint.renewalTag)) {
            scheduleWork(
                wf = wf,
                isNew = false,
                appendAfterCurrent = true,
                rethrowOnFailure = true,
                renewalTag = checkpoint.renewalTag,
            )
        }
        firedSlotStore.upsert(
            checkpoint.copy(
                phase = TimeCronSlotPhase.RENEWED,
                updatedAtMs = System.currentTimeMillis(),
            )
        )
    }

    companion object {
        private const val TAG = "WorkflowTrigger"
        const val KEY_WORKFLOW_ID = "workflow_id"
        const val KEY_PERIODIC_V1 = "time_cron_periodic_v1"
        fun workName(workflowId: String) = "wf_timecron_$workflowId"
        fun workNameOneShot(workflowId: String) = "wf_timecron_oneshot_$workflowId"
        fun renewalTagFor(slotKey: String) = "wf_timecron_renewed_from_$slotKey"

        internal val DEFAULT_SYNC_RETRY_DELAYS_MS = longArrayOf(
            5_000L,
            15_000L,
            30_000L,
            60_000L,
            120_000L,
        )

        fun derivePeriodMs(spec: TriggerSpec.TimeCron): Long? {
            if (!spec.timeOfDay.isNullOrBlank()) {
                return if (spec.daysOfWeek.isEmpty()) 24L * 60 * 60 * 1000 else null
            }
            val cron = spec.cron?.trim() ?: return null
            val every = Regex("^@every\\s+(\\d+)([smhd])$").find(cron)
            if (every != null) {
                val n = every.groupValues[1].toLong()
                val unit = every.groupValues[2]
                return when (unit) {
                    "s" -> if (n < 60) null else n * 1000
                    "m" -> n * 60 * 1000
                    "h" -> n * 60 * 60 * 1000
                    "d" -> n * 24 * 60 * 60 * 1000
                    else -> null
                }
            }
            return when (cron) {
                "@hourly" -> 60L * 60 * 1000
                "@daily", "@midnight" -> 24L * 60 * 60 * 1000
                "@weekly" -> 7L * 24 * 60 * 60 * 1000
                else -> null
            }
        }

        fun computeNextFireMs(spec: TriggerSpec.TimeCron, zone: ZoneId, nowMs: Long): Long {
            val now = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMs), zone)
            if (!spec.timeOfDay.isNullOrBlank()) {
                val (h, m) = spec.timeOfDay.split(":").let { it[0].toInt() to it[1].toInt() }
                var candidate = now.toLocalDate().atTime(java.time.LocalTime.of(h, m)).atZone(zone)
                if (!candidate.isAfter(now)) candidate = candidate.plusDays(1)
                if (spec.daysOfWeek.isNotEmpty()) {
                    val allowed = spec.daysOfWeek.map { isoDow(it) }.toSet()
                    var hops = 0
                    while (candidate.dayOfWeek !in allowed && hops < 8) {
                        candidate = candidate.plusDays(1); hops++
                    }
                }
                return candidate.toInstant().toEpochMilli()
            }
            derivePeriodMs(spec)?.let { return nowMs + it }
            spec.cron?.trim()?.takeIf { it.isNotBlank() }?.let { cron ->
                me.rerere.rikkahub.service.CronExpressionParser.parse(cron).getOrNull()?.let { parsed ->
                    me.rerere.rikkahub.service.CronExpressionParser.nextExecution(parsed, now)
                        ?.let { return it.toInstant().toEpochMilli() }
                }
            }
            return nowMs + 60L * 60 * 1000
        }

        private fun isoDow(iso: Int): DayOfWeek = when (iso) {
            1 -> DayOfWeek.MONDAY; 2 -> DayOfWeek.TUESDAY; 3 -> DayOfWeek.WEDNESDAY
            4 -> DayOfWeek.THURSDAY; 5 -> DayOfWeek.FRIDAY; 6 -> DayOfWeek.SATURDAY
            else -> DayOfWeek.SUNDAY
        }
    }
}
