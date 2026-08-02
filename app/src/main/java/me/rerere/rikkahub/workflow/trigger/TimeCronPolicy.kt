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
}
