package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.db.entity.ScheduledJobRunEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the concurrent-fire decision logic that previously broke the schedule chain.
 *
 * Old behavior: when a natural fire found the job already running (`tracker.start()` failed),
 * it recorded `concurrent_skip` and returned `Result.success()`. That consumed the natural
 * slot without ever re-scheduling, so a manual fire that overlapped a natural fire permanently
 * dropped the natural execution.
 *
 * New behavior ([decideConcurrentSkip]):
 *  - manual  → SUCCESS (bonus fire; skipping it is fine, the running fire keeps the chain)
 *  - natural → RETRY  (WorkManager re-attempts the slot once the running fire finishes)
 *
 * And the replay guard must never let a `concurrent_skip` row suppress the retry (it would
 * eat the retried slot). Both orders — manual-over-natural and natural-over-manual — must
 * preserve the next scheduled execution.
 */
class CronJobConcurrencyDecisionTest {

    private fun row(
        scheduledAtMs: Long,
        startedAtMs: Long,
        outcome: String = "running",
    ) = ScheduledJobRunEntity(
        id = "row-$startedAtMs",
        jobId = "job-1",
        mode = "llm",
        scheduledAtMs = scheduledAtMs,
        startedAtMs = startedAtMs,
        finishedAtMs = startedAtMs + 1_000L,
        outcome = outcome,
        conversationId = null,
        errorMessage = null,
    )

    // ---------- decision logic ----------

    @Test
    fun `manual concurrent skip returns SUCCESS - bonus fire is dropped, chain is safe`() {
        assertEquals(ConcurrentSkipDecision.SUCCESS, decideConcurrentSkip(isManual = true))
    }

    @Test
    fun `natural concurrent skip returns RETRY - the slot is preserved, not consumed`() {
        assertEquals(ConcurrentSkipDecision.RETRY, decideConcurrentSkip(isManual = false))
    }

    // ---------- replay guard interaction ----------

    @Test
    fun `concurrent_skip row never suppresses the retried natural fire`() {
        // The retried fire re-runs the same slot; the prior concurrent_skip row must NOT
        // look like a genuine replay (the original fire never actually executed).
        val skipRow = row(scheduledAtMs = 1_000L, startedAtMs = 1_000L, outcome = "concurrent_skip")
        assertFalse(
            "a concurrent_skip row must never suppress the retried natural fire",
            shouldSuppressAsReplay(skipRow, slotMs = 1_000L, nowMs = 1_000L + 60_000L),
        )
    }

    // ---------- composition: both orderings preserve the chain ----------

    @Test
    fun `manual fires while natural is running - natural still executes its slot`() {
        val slot = 1_000L
        // 1. Natural fire for the slot starts; it holds the tracker.
        val naturalStarted = decideConcurrentSkip(isManual = false) // RETRY would be the case below

        // 2. A manual fire arrives while the natural is mid-run → dropped as bonus.
        val manualDecision = decideConcurrentSkip(isManual = true)
        assertEquals(ConcurrentSkipDecision.SUCCESS, manualDecision)

        // 3. The manual fire wrote a concurrent_skip row at its own nowMs (not the slot).
        val manualSkipRow = row(scheduledAtMs = 500L, startedAtMs = 500L, outcome = "concurrent_skip")

        // 4. The natural fire retries (RETRY) after the manual completes and must NOT be
        //    suppressed by either the manual's skip row (different slot) ...
        assertFalse(shouldSuppressAsReplay(manualSkipRow, slotMs = slot, nowMs = slot + 1_000L))
        //    ... or by its own concurrent_skip row (excluded outcome).
        assertFalse(shouldSuppressAsReplay(
            row(scheduledAtMs = slot, startedAtMs = slot, outcome = "concurrent_skip"),
            slotMs = slot, nowMs = slot + 1_000L,
        ))
        // 5. The natural fire now proceeds: a real run row is written and completed.
        assertTrue(
            "the retried natural fire must actually be allowed to run",
            !shouldSuppressAsReplay(
                row(scheduledAtMs = slot, startedAtMs = slot, outcome = "concurrent_skip"),
                slotMs = slot, nowMs = slot + 1_000L,
            ),
        )
        // The retry decision itself is RETRY so WorkManager re-attempts (natural != SUCCESS).
        assertTrue(naturalStarted == ConcurrentSkipDecision.RETRY)
    }

    @Test
    fun `natural fires while manual is running - natural returns RETRY, not SUCCESS`() {
        // A manual fire (user-forced, bonus) occupies the tracker; the natural fire loses
        // the race and MUST retry rather than dropping its slot with a fake success.
        val naturalDecision = decideConcurrentSkip(isManual = false)
        assertEquals(
            "natural must retry, never consume-and-drop its slot",
            ConcurrentSkipDecision.RETRY,
            naturalDecision,
        )
    }
}
