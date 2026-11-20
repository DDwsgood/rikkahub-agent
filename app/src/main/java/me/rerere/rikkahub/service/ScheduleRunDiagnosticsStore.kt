package me.rerere.rikkahub.service

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.scheduleDiagnosticsDataStore by preferencesDataStore(name = "schedule_run_diagnostics")

/**
 * 5-stage segmented trace for a single scheduled job execution:
 * 1. [scheduledAtMs] — planned slot time
 * 2. [alarmReceivedAtMs] — time the AlarmManager BroadcastReceiver fired (null for WorkManager/manual)
 * 3. [workerStartedAtMs] — time [CronJobWorker.doWork] began executing
 * 4. [foregroundResult] / [foregroundAtMs] — FGS promotion result and timestamp
 * 5. [finishedAtMs] / [executionDurationMs] / [deliveryFinishedAtMs] / [deliveryDurationMs] /
 *    [outcome] / [deliveryResult] — execution completion vs external delivery completion
 *
 * Execution and delivery durations are computed with monotonic `SystemClock.elapsedRealtime()`
 * so wall-clock adjustments (NTP/timezone) never distort measured durations.
 */
@Serializable
data class ScheduleRunTrace(
    val runId: String,
    val jobId: String,
    val jobName: String,
    val mode: String,
    val triggerSource: String,
    val scheduledAtMs: Long,
    val alarmReceivedAtMs: Long? = null,
    val workerStartedAtMs: Long,
    val foregroundResult: String,
    val foregroundAtMs: Long? = null,
    val finishedAtMs: Long? = null,
    /** Monotonic execution duration (worker start → task completion, excluding webhook). */
    val executionDurationMs: Long? = null,
    /** Timestamp of the most recent external delivery attempt (set for both interim retries and terminal outcomes). */
    val lastDeliveryAttemptAtMs: Long? = null,
    /** Monotonic duration of the most recent external delivery attempt. */
    val lastDeliveryAttemptDurationMs: Long? = null,
    /** Timestamp of FINAL (terminal) external delivery settlement; remains `null` while awaiting retry. */
    val deliveryFinishedAtMs: Long? = null,
    /** Monotonic external delivery duration of the terminal settlement (if settled). */
    val deliveryDurationMs: Long? = null,
    val outcome: String = "running",
    val deliveryResult: String? = null,
) {
    /** Alarm delivery latency: `alarmReceivedAtMs - scheduledAtMs` (if triggered via AlarmManager). */
    val alarmDeliveryDelayMs: Long?
        get() = alarmReceivedAtMs?.let { it - scheduledAtMs }

    /** Worker startup latency after alarm receipt (or after scheduledAtMs for WM-only triggers). */
    val workerStartDelayMs: Long
        get() = workerStartedAtMs - (alarmReceivedAtMs ?: scheduledAtMs)

    /** Effective execution duration, preferring monotonic measurement over wall-clock subtraction. */
    val effectiveExecutionDurationMs: Long?
        get() = executionDurationMs ?: finishedAtMs?.let { (it - workerStartedAtMs).coerceAtLeast(0L) }
}

class ScheduleRunDiagnosticsStore private constructor(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.scheduleDiagnosticsDataStore)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val allTracesFlow: Flow<List<ScheduleRunTrace>> =
        dataStore.data.map { prefs ->
            decode(prefs[KEY_TRACES])
        }

    fun tracesForJobFlow(jobId: String, limit: Int = 12): Flow<List<ScheduleRunTrace>> =
        allTracesFlow.map { list ->
            list.filter { it.jobId == jobId }.take(limit)
        }

    /**
     * Upserts a trace by [ScheduleRunTrace.runId] monotonically: later-stage execution or
     * external delivery results already persisted on the same `runId` (such as a fast
     * [ScheduleWebhookWorker] settlement) are never regressed back to `running` or
     * `webhook:queued`. Enforces per-job retention so one high-frequency job cannot evict
     * all other jobs' diagnostic traces.
     */
    suspend fun record(trace: ScheduleRunTrace) {
        dataStore.edit { prefs ->
            val current = decode(prefs[KEY_TRACES]).toMutableList()
            val existing = current.firstOrNull { it.runId == trace.runId }
            val merged = mergeTrace(existing, trace)
            current.removeAll { it.runId == trace.runId }
            current.add(0, merged)
            val retained = retainPerJobQuota(current, maxPerJob = MAX_TRACES_PER_JOB, maxTotal = MAX_TRACES_GLOBAL)
            prefs[KEY_TRACES] = json.encodeToString(ListSerializer(ScheduleRunTrace.serializer()), retained)
        }
    }

    /**
     * Patches an existing in-flight (`running`) trace when boot recovery discovers the process
     * died mid-execution, keeping the earlier alarm/worker/FGS checkpoints intact.
     */
    suspend fun markStrandedTerminal(
        runId: String,
        outcome: String,
        finishedAtMs: Long,
        deliveryResult: String? = "process_terminated",
    ) {
        dataStore.edit { prefs ->
            val current = decode(prefs[KEY_TRACES]).toMutableList()
            val idx = current.indexOfFirst { it.runId == runId }
            if (idx >= 0) {
                val old = current[idx]
                if (old.outcome == "running") {
                    current[idx] = old.copy(
                        finishedAtMs = finishedAtMs,
                        outcome = outcome,
                        deliveryResult = deliveryResult,
                    )
                    prefs[KEY_TRACES] = json.encodeToString(ListSerializer(ScheduleRunTrace.serializer()), current)
                }
            }
        }
    }

    /**
     * Updates the external delivery fields of an existing persisted run trace (invoked by the
     * decoupled [ScheduleWebhookWorker]), preserving any local failure notification tag while
     * distinguishing transient retries (`isFinal = false`, leaving `deliveryFinishedAtMs = null`
     * and stamping `lastDeliveryAttemptAtMs`) from terminal settlement (`isFinal = true`).
     * Never regresses an already-terminal delivery back to an interim retry state.
     */
    suspend fun updateDelivery(
        runId: String,
        deliveryFinishedAtMs: Long? = null,
        deliveryDurationMs: Long? = null,
        webhookSummary: String,
        attemptAtMs: Long = deliveryFinishedAtMs ?: System.currentTimeMillis(),
        attemptDurationMs: Long = deliveryDurationMs ?: 0L,
        isFinal: Boolean = true,
    ) {
        dataStore.edit { prefs ->
            val current = decode(prefs[KEY_TRACES]).toMutableList()
            val idx = current.indexOfFirst { it.runId == runId }
            if (idx >= 0) {
                val old = current[idx]
                // Never regress an already-settled terminal delivery back to an interim retry status.
                if (!isFinal && old.deliveryFinishedAtMs != null) return@edit
                val mergedDelivery = mergeDeliverySummary(old.deliveryResult, webhookSummary)
                current[idx] = if (isFinal) {
                    old.copy(
                        lastDeliveryAttemptAtMs = attemptAtMs,
                        lastDeliveryAttemptDurationMs = attemptDurationMs,
                        deliveryFinishedAtMs = deliveryFinishedAtMs ?: attemptAtMs,
                        deliveryDurationMs = deliveryDurationMs ?: attemptDurationMs,
                        deliveryResult = mergedDelivery,
                    )
                } else {
                    old.copy(
                        lastDeliveryAttemptAtMs = attemptAtMs,
                        lastDeliveryAttemptDurationMs = attemptDurationMs,
                        deliveryResult = mergedDelivery,
                    )
                }
                prefs[KEY_TRACES] = json.encodeToString(ListSerializer(ScheduleRunTrace.serializer()), current)
            }
        }
    }

    suspend fun clearForJob(jobId: String) {
        dataStore.edit { prefs ->
            val next = decode(prefs[KEY_TRACES]).filterNot { it.jobId == jobId }
            prefs[KEY_TRACES] = json.encodeToString(ListSerializer(ScheduleRunTrace.serializer()), next)
        }
    }

    suspend fun recent(limit: Int = 20): List<ScheduleRunTrace> =
        allTracesFlow.first().take(limit)

    private fun decode(raw: String?): List<ScheduleRunTrace> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(ScheduleRunTrace.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    companion object {
        private val KEY_TRACES = stringPreferencesKey("traces_json")
        private const val MAX_TRACES_PER_JOB = 12
        private const val MAX_TRACES_GLOBAL = 120

        internal fun createForTest(dataStore: DataStore<Preferences>): ScheduleRunDiagnosticsStore =
            ScheduleRunDiagnosticsStore(dataStore)

        internal fun mergeTrace(existing: ScheduleRunTrace?, incoming: ScheduleRunTrace): ScheduleRunTrace {
            if (existing == null) return incoming
            val preserveExistingTerminal = existing.deliveryFinishedAtMs != null && incoming.deliveryFinishedAtMs == null
            val preserveExistingAttempt = !preserveExistingTerminal &&
                existing.lastDeliveryAttemptAtMs != null &&
                incoming.lastDeliveryAttemptAtMs == null &&
                incoming.deliveryFinishedAtMs == null
            val preserveExistingDelivery = preserveExistingTerminal || preserveExistingAttempt
            val mergedDeliveryResult = if (preserveExistingDelivery) {
                val existingWebhookToken = existing.deliveryResult.orEmpty()
                    .split(',')
                    .map { it.trim() }
                    .lastOrNull { it.startsWith("webhook:") }
                if (existingWebhookToken != null) {
                    mergeDeliverySummary(incoming.deliveryResult, existingWebhookToken)
                } else {
                    incoming.deliveryResult ?: existing.deliveryResult
                }
            } else {
                incoming.deliveryResult ?: existing.deliveryResult
            }
            return incoming.copy(
                finishedAtMs = incoming.finishedAtMs ?: existing.finishedAtMs,
                executionDurationMs = incoming.executionDurationMs ?: existing.executionDurationMs,
                outcome = if (incoming.outcome == "running" && existing.outcome != "running") {
                    existing.outcome
                } else {
                    incoming.outcome
                },
                lastDeliveryAttemptAtMs = if (preserveExistingDelivery) {
                    existing.lastDeliveryAttemptAtMs
                } else {
                    incoming.lastDeliveryAttemptAtMs ?: existing.lastDeliveryAttemptAtMs
                },
                lastDeliveryAttemptDurationMs = if (preserveExistingDelivery) {
                    existing.lastDeliveryAttemptDurationMs
                } else {
                    incoming.lastDeliveryAttemptDurationMs ?: existing.lastDeliveryAttemptDurationMs
                },
                deliveryFinishedAtMs = if (preserveExistingTerminal) existing.deliveryFinishedAtMs else incoming.deliveryFinishedAtMs,
                deliveryDurationMs = if (preserveExistingTerminal) existing.deliveryDurationMs else incoming.deliveryDurationMs,
                deliveryResult = mergedDeliveryResult,
            )
        }

        internal fun mergeDeliverySummary(existing: String?, webhookSummary: String): String {
            val parts = existing.orEmpty()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("webhook:") }
                .toMutableList()
            parts.add(webhookSummary)
            return parts.joinToString(", ")
        }

        internal fun retainPerJobQuota(
            traces: List<ScheduleRunTrace>,
            maxPerJob: Int,
            maxTotal: Int,
        ): List<ScheduleRunTrace> {
            val counts = mutableMapOf<String, Int>()
            val result = ArrayList<ScheduleRunTrace>(minOf(traces.size, maxTotal))
            for (trace in traces) {
                val seen = counts[trace.jobId] ?: 0
                if (seen >= maxPerJob) continue
                counts[trace.jobId] = seen + 1
                result += trace
                if (result.size >= maxTotal) break
            }
            return result
        }
    }
}
