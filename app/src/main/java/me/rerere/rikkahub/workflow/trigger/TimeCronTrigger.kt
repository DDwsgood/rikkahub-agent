package me.rerere.rikkahub.workflow.trigger

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Phase 12 - time / cron family.
 *
 * Reuses WorkManager (same backend as scheduled jobs) but with workflow-scoped unique-work
 * names so one workflow's schedule doesn't collide with another's, and so unsync removes
 * exactly the disabled workflow's worker without touching anything else.
 *
 * v1 supports the time_of_day + days_of_week subset. The `cron` field is also accepted
 * (per spec - same dialect as scheduled jobs): `@every Ns`, `@hourly`, `@daily`, `@weekly`
 * map to fixed periods, and arbitrary 5-field cron runs on the one-shot chain with the next
 * fire computed exactly via [me.rerere.rikkahub.service.CronExpressionParser] (shared with
 * scheduled jobs). The validator rejects anything neither path can handle.
 *
 * We use [WorkflowTimeCronWorker] (separate file) which receives the workflow id and
 * dispatches into the engine.
 *
 * Scheduling policy (worker lifecycle reliability):
 *  - Initial / newly-added schedule: use KEEP so an existing (possibly in-flight) worker
 *    isn't cancelled. Only a real config change (trigger / updatedAtMs / re-enable) uses
 *    UPDATE (periodic) or REPLACE (one-shot) to rebuild.
 *  - One-shot path re-enqueues the next fire ONLY after the current fire completes.
 *    Disabled / not-found fires return success without re-enqueuing.
 *  - The worker structurally awaits the engine fire so it doesn't report success early.
 */
internal class TimeCronTriggerFamily(
    private val context: Context,
    private val scope: CoroutineScope,
) : WorkflowTriggerFamily {

    override val name = "time_cron"

    @Volatile private var lastSnapshot: List<WorkflowDefinition> = emptyList()
    @Volatile private var fireCallback: TriggerFireCallback? = null

    override fun handles(spec: TriggerSpec): Boolean = spec is TriggerSpec.TimeCron

    override suspend fun sync(matching: List<WorkflowDefinition>, callback: TriggerFireCallback) {
        fireCallback = callback
        val previous = lastSnapshot.associateBy { it.id }
        val current = matching.associateBy { it.id }
        // Cancel removed
        for (id in previous.keys - current.keys) {
            cancelWork(id)
        }
        // Schedule added or changed
        for ((id, wf) in current) {
            val prev = previous[id]
            if (TimeCronPolicy.needsScheduling(prev, wf)) {
                scheduleWork(wf, TimeCronPolicy.isNewSchedule(prev))
            }
        }
        lastSnapshot = matching
    }

    override suspend fun shutdown() {
        for (wf in lastSnapshot) cancelWork(wf.id)
        lastSnapshot = emptyList()
        fireCallback = null
    }

    fun cancelWork(workflowId: String) {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(workName(workflowId)) }
            .onFailure { Log.w(TAG, "time_cron: cancel work failed for $workflowId", it) }
    }

    /**
     * Schedule work for a workflow.
     *
     * @param wf the workflow definition
     * @param isNew true for first-ever schedule or re-enable from disabled - uses KEEP so
     *   an existing in-flight worker isn't cancelled. false for real config changes - uses
     *   UPDATE (periodic) or REPLACE (one-shot) to rebuild with the new spec.
     */
    private fun scheduleWork(
        wf: WorkflowDefinition,
        isNew: Boolean = true,
        appendAfterCurrent: Boolean = false,
    ) {
        val spec = wf.trigger as? TriggerSpec.TimeCron ?: return
        val zone = spec.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()

        // Periodic path: time_of_day-based or @every - both reduce to a 24h period for
        // time_of_day, or the parsed N-second period for @every. WorkManager's smallest
        // period is 15 minutes, so very-short cycles fall back to one-shot rescheduling
        // from the worker (worker re-enqueues itself).
        val periodMs = derivePeriodMs(spec)
        if (periodMs != null && periodMs >= 15 * 60 * 1000L) {
            val nextFireMs = computeNextFireMs(spec, zone, System.currentTimeMillis())
            val delay = (nextFireMs - System.currentTimeMillis()).coerceAtLeast(0L)
            val req = PeriodicWorkRequestBuilder<WorkflowTimeCronWorker>(periodMs, TimeUnit.MILLISECONDS)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_WORKFLOW_ID to wf.id))
                .setConstraints(Constraints.NONE)
                .build()
            // KEEP for new/re-enabled schedules - don't cancel an existing (possibly
            // in-flight) periodic worker. UPDATE for real config changes - rebuilds the
            // period/initial-delay while preserving the unique name. UPDATE does not
            // cancel a currently-running instance; it only updates the next schedule.
            val policy = if (isNew) ExistingPeriodicWorkPolicy.KEEP else ExistingPeriodicWorkPolicy.UPDATE
            runCatching {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    workName(wf.id), policy, req,
                )
            }.onFailure { Log.w(TAG, "time_cron: periodic enqueue failed for ${wf.id}", it) }
            return
        }

        // One-shot path: schedule the next fire; the worker re-enqueues itself on completion.
        val nextFireMs = computeNextFireMs(spec, zone, System.currentTimeMillis())
        val delay = (nextFireMs - System.currentTimeMillis()).coerceAtLeast(60_000L)
        val req = OneTimeWorkRequestBuilder<WorkflowTimeCronWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(KEY_WORKFLOW_ID to wf.id))
            .build()
        // KEEP for new/re-enabled - don't cancel an existing one-shot that may be
        // mid-fire. REPLACE only for real config changes (trigger/updatedAtMs differ).
        val policy = when {
            isNew -> ExistingWorkPolicy.KEEP
            appendAfterCurrent -> ExistingWorkPolicy.APPEND_OR_REPLACE
            else -> ExistingWorkPolicy.REPLACE
        }
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(
                workName(wf.id), policy, req,
            )
        }.onFailure { Log.w(TAG, "time_cron: one-shot enqueue failed for ${wf.id}", it) }
    }

    /**
     * Internal - fires the workflow then re-enqueues if needed. Called from the worker.
     *
     * Structured: awaits the callback completion so the worker doesn't report success
     * early. Cold-start fallback resolves the callback via Koin if the registry hasn't
     * wired it yet, and throws on Koin failure so the worker retries.
     */
    suspend fun onWorkerFired(workflowId: String) {
        // Resolve the fire callback. Prefer the registry-wired one; fall back to Koin
        // for the cold-start race where WorkManager fires before TriggerRegistry.start.
        val cb = fireCallback ?: run {
            // Koin failure throws -> worker returns Result.retry.
            // This is intentional: we must not silently drop a scheduled fire.
            TimeCronWorkerHelper.resolveEngineCallback()
        }

        // Post-boot or post-process-death race: WorkManager wakes the worker before
        // [TriggerRegistry.start] has emitted from the repo's flow, leaving `lastSnapshot`
        // empty. Fall back to a direct repository fetch so the fire isn't silently dropped.
        // The engine still re-checks enabled / cooldown / conditions, so this is safe.
        val wf = lastSnapshot.firstOrNull { it.id == workflowId }
            ?: run {
                val loaded = TimeCronWorkerHelper.repositoryLookup(workflowId) ?: return
                if (!loaded.entity.enabled) return
                loaded.definition
            }
        // days_of_week gate. The time_of_day path runs as a fixed 24h PeriodicWorkRequest
        // which fires every day; the day restriction is only honoured here. Without this
        // gate a "Mondays 09:00" workflow fires daily after its first Monday.
        val spec = wf.trigger as? TriggerSpec.TimeCron
        if (spec != null && !spec.timeOfDay.isNullOrBlank() && spec.daysOfWeek.isNotEmpty()) {
            val zone = spec.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() }
                ?: ZoneId.systemDefault()
            val today = ZonedDateTime.now(zone).dayOfWeek
            if (today !in spec.daysOfWeek.map { isoDow(it) }) {
                Log.d(TAG, "time_cron: $workflowId skipped, $today not in days_of_week")
                // One-shot path still needs re-enqueueing even if today is filtered out,
                // so the next eligible day's fire is armed.
                val periodMs = spec.let { derivePeriodMs(it) }
                if (TimeCronPolicy.shouldReEnqueueOneShot(periodMs)) {
                    scheduleWork(wf, isNew = false, appendAfterCurrent = true)
                }
                return
            }
        }
        // Structured wait: the engine converts action/condition failures into a persisted
        // FireOutcome. An exception escaping this call is therefore infrastructure failure
        // and must reach WorkflowTimeCronWorker so WorkManager can retry it.
        cb.onFire(wf.id, wf.trigger)

        // For one-shot path (period < 15min or null), re-enqueue with the next fire.
        // This happens AFTER the fire completes, so there's no window where the worker
        // reports success before the next fire is armed. Disabled/not-found workflows
        // return early above without reaching this re-enqueue.
        val periodMs = (wf.trigger as? TriggerSpec.TimeCron)?.let { derivePeriodMs(it) }
        if (TimeCronPolicy.shouldReEnqueueOneShot(periodMs)) {
            scheduleWork(wf, isNew = false, appendAfterCurrent = true)
        }
    }

    companion object {
        private const val TAG = "WorkflowTrigger"
        const val KEY_WORKFLOW_ID = "workflow_id"
        fun workName(workflowId: String) = "wf_timecron_$workflowId"

        /**
         * Returns null for arbitrary cron (one-shot path) or the period in ms for the
         * supported subset. Daily HH:mm = 24h. @every Ns = N seconds. @hourly = 1h.
         * @daily = 24h. days_of_week with time_of_day still uses 24h period (worker's
         * fire skips when day doesn't match).
         */
        fun derivePeriodMs(spec: TriggerSpec.TimeCron): Long? {
            if (!spec.timeOfDay.isNullOrBlank()) return 24L * 60 * 60 * 1000
            val cron = spec.cron?.trim() ?: return null
            // @every Ns
            val every = Regex("^@every\\s+(\\d+)([smhd])$").find(cron)
            if (every != null) {
                val n = every.groupValues[1].toLong()
                val unit = every.groupValues[2]
                return when (unit) {
                    "s" -> n * 1000
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
                else -> null  // 5-field cron - fall back to one-shot
            }
        }

        /** Compute the next fire time. For unsupported cron forms, returns now+15 min. */
        fun computeNextFireMs(spec: TriggerSpec.TimeCron, zone: ZoneId, nowMs: Long): Long {
            val now = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMs), zone)
            // time_of_day + optional days_of_week
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
            // @every Ns
            derivePeriodMs(spec)?.let { return nowMs + it }
            // 5-field cron: compute the exact next execution via the shared parser
            // (same dialect as scheduled jobs). Without this, "0 9 * * 1" style
            // expressions silently degraded to hourly fires.
            spec.cron?.trim()?.takeIf { it.isNotBlank() }?.let { cron ->
                me.rerere.rikkahub.service.CronExpressionParser.parse(cron).getOrNull()?.let { parsed ->
                    me.rerere.rikkahub.service.CronExpressionParser.nextExecution(parsed, now)
                        ?.let { return it.toInstant().toEpochMilli() }
                }
            }
            // Fallback for unsupported cron - fire roughly hourly so the user gets
            // something useful even with an exotic schedule. Worker will re-evaluate.
            return nowMs + 60L * 60 * 1000
        }

        private fun isoDow(iso: Int): DayOfWeek = when (iso) {
            1 -> DayOfWeek.MONDAY; 2 -> DayOfWeek.TUESDAY; 3 -> DayOfWeek.WEDNESDAY
            4 -> DayOfWeek.THURSDAY; 5 -> DayOfWeek.FRIDAY; 6 -> DayOfWeek.SATURDAY
            else -> DayOfWeek.SUNDAY
        }
    }
}
