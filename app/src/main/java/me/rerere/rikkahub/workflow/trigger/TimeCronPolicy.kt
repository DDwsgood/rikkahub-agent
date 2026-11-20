package me.rerere.rikkahub.workflow.trigger

import me.rerere.rikkahub.workflow.model.WorkflowDefinition

/**
 * Pure decision helpers for the time/cron trigger family's WorkManager scheduling.
 * Extracted so the policy logic can be unit-tested without Android/Room.
 *
 * The three decisions:
 *  1. [needsScheduling] - does this workflow need to be (re)scheduled at all?
 *  2. [isNewSchedule] - is it a brand-new schedule (KEEP) or a real config change (UPDATE/REPLACE)?
 *  3. [shouldReEnqueueOneShot] - after a successful fire, should the one-shot chain continue?
 */
internal object TimeCronPolicy {
    /** WorkManager's minimum periodic interval (15 minutes). */
    private const val MIN_PERIODIC_MS = 15L * 60 * 1000

    /**
     * Whether a workflow needs to be (re)scheduled given its previous state.
     * New workflows, workflows with real config changes, and workflows that were
     * previously disabled all need scheduling. Unchanged ones don't.
     */
    fun needsScheduling(prev: WorkflowDefinition?, current: WorkflowDefinition): Boolean {
        if (prev == null) return true
        if (prev.trigger != current.trigger) return true
        if (prev.updatedAtMs != current.updatedAtMs) return true
        if (!prev.enabled) return true
        return false
    }

    /**
     * Whether the previous state indicates a "new" schedule (no prior workflow
     * definition, or the prior definition was disabled). New schedules use KEEP
     * so they don't cancel potentially-existing work; real config changes use
     * UPDATE (periodic) or REPLACE (one-shot).
     */
    fun isNewSchedule(prev: WorkflowDefinition?): Boolean {
        return prev == null || !prev.enabled
    }

    /**
     * Whether a one-shot workflow should be re-enqueued after a fire. True for
     * arbitrary cron (null period) or periods below WorkManager's 15-minute
     * minimum (which fall back to one-shot rescheduling from the worker).
     */
    fun shouldReEnqueueOneShot(periodMs: Long?): Boolean {
        return periodMs == null || periodMs < MIN_PERIODIC_MS
    }

    /** Minimal representation of an existing WorkManager entry under the legacy `wf_timecron_<id>` name. */
    data class LegacyWorkEntry(
        val isActive: Boolean,
        val isPeriodic: Boolean,
    )

    enum class LegacyOneShotMigrationAction {
        /**
         * A non-terminal (`ENQUEUED` / `RUNNING` / `BLOCKED`) pre-upgrade one-shot work already
         * occupies the legacy name — or the legacy lookup failed on cold-start sync so we must
         * fail safe rather than cancel or duplicate an unreadable legacy chain. Leave it untouched
         * so its already-scheduled slot fires on time and seamlessly hands off to
         * `workNameOneShot(id)` in `onWorkerFired()`.
         */
        KEEP_LEGACY_ONE_SHOT,

        /**
         * Cancel the legacy `workName(id)` entry (either a legacy periodic work or an explicit user
         * config change) and enqueue the new `workNameOneShot(id)`.
         */
        CANCEL_LEGACY_AND_ENQUEUE_NEW,

        /**
         * No active legacy entry needs cancellation (or this is a post-fire continuation); simply
         * enqueue under `workNameOneShot(id)`.
         */
        ENQUEUE_NEW_ONLY,
    }

    /**
     * Decides how to handle any WorkManager entry under the legacy unique work name (`wf_timecron_<id>`)
     * when scheduling a one-shot workflow (`wf_timecron_oneshot_<id>`).
     *
     * Upgrade safety:
     * - Prior versions scheduled BOTH periodic and one-shot workflows under `wf_timecron_<id>`.
     * - On first sync after upgrade (`isNew = true`, `appendAfterCurrent = false`), if ANY non-terminal
     *   (`!state.isFinished`, covering `ENQUEUED`, `RUNNING`, and `BLOCKED`) non-periodic work exists
     *   under the legacy name — or if querying WorkManager failed (`legacyLookupFailed = true`) —
     *   preserve/defer instead of cancelling or creating a duplicate parallel chain.
     * - If an active legacy *periodic* work exists (e.g. an old broken 24h weekday workflow), cancel
     *   the legacy name and enqueue under the new one-shot name.
     * - If `!isNew && !appendAfterCurrent` (explicit user edit), cancel legacy and replace new.
     */
    fun decideLegacyOneShotMigration(
        isNew: Boolean,
        appendAfterCurrent: Boolean,
        legacyEntries: List<LegacyWorkEntry>,
        legacyLookupFailed: Boolean = false,
    ): LegacyOneShotMigrationAction {
        if (appendAfterCurrent) return LegacyOneShotMigrationAction.ENQUEUE_NEW_ONLY
        if (legacyLookupFailed) {
            return if (isNew) {
                LegacyOneShotMigrationAction.KEEP_LEGACY_ONE_SHOT
            } else {
                LegacyOneShotMigrationAction.CANCEL_LEGACY_AND_ENQUEUE_NEW
            }
        }
        val activeLegacy = legacyEntries.filter { it.isActive }
        if (!isNew) {
            // Explicit config edit: clear any leftover legacy work and REPLACE the one-shot chain.
            return if (activeLegacy.isNotEmpty()) {
                LegacyOneShotMigrationAction.CANCEL_LEGACY_AND_ENQUEUE_NEW
            } else {
                LegacyOneShotMigrationAction.ENQUEUE_NEW_ONLY
            }
        }
        // Cold-start / upgrade sync (isNew == true):
        val hasActiveLegacyOneShot = activeLegacy.any { !it.isPeriodic }
        if (hasActiveLegacyOneShot) {
            return LegacyOneShotMigrationAction.KEEP_LEGACY_ONE_SHOT
        }
        val hasActiveLegacyPeriodic = activeLegacy.any { it.isPeriodic }
        return if (hasActiveLegacyPeriodic) {
            LegacyOneShotMigrationAction.CANCEL_LEGACY_AND_ENQUEUE_NEW
        } else {
            LegacyOneShotMigrationAction.ENQUEUE_NEW_ONLY
        }
    }
}
