package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.db.entity.ScheduledJobRunEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the replay-guard bug where a manual fire (trigger_job_now) stamped the FUTURE
 * scheduled slot into its run row, causing the next regular fire at that slot to be
 * suppressed as `process_killed_replay`.
 *
 * Live reproduction on `beta` 2026-05-16:
 *   - Job "Daily Weather Brief" created May 15 3:27:06 PM Riyadh (cron `0 8 * * *`).
 *     `nextRunAtMs` was correctly set to May 16 8:00:00 AM.
 *   - The LLM immediately auto-fired `trigger_job_now` (30s later) as a test run.
 *     The manual fire stamped `scheduledAtMs = May 16 8:00 AM` and succeeded.
 *   - May 16 8:00 AM the real cron fired. Replay guard saw the manual row with the
 *     same `scheduledAtMs` and suppressed today's run as `process_killed_replay`.
 *   - User did not get their weather brief.
 *
 * The fix is twofold and both halves are tested here:
 *   1. [computeRunSlot]: manual fires stamp nowMs, not job.nextRunAtMs.
 *   2. [shouldSuppressSameSlotFire] (used by both [shouldSuppressAsReplay] and
 *      [shouldSuppressBackupFire]): the source-aware rule — a row whose startedAtMs is at
 *      or after its scheduledAtMs is a REAL execution and suppresses duplicates at ANY age
 *      (late primaries, backups and retries with backoff beyond any window must never
 *      double-execute a slot); a legacy manual row (startedAtMs BEFORE its stamped future
 *      slot) only suppresses within REPLAY_WINDOW_MS, so a stale 16h-old row can never
 *      suppress a fresh fire.
 */
class CronJobWorkerReplayGuardTest {

    private fun row(
        scheduledAtMs: Long,
        startedAtMs: Long,
        outcome: String = "success",
    ) = ScheduledJobRunEntity(
        id = "row-${startedAtMs}",
        jobId = "job-1",
        mode = "llm",
        scheduledAtMs = scheduledAtMs,
        startedAtMs = startedAtMs,
        finishedAtMs = startedAtMs + 1_000L,
        outcome = outcome,
        conversationId = null,
        errorMessage = null,
    )

    // ---------- computeRunSlot ----------

    @Test
    fun `manual fire stamps nowMs even when nextRunAtMs points to a future slot`() {
        val now = 1_000L
        val futureSlot = 1_000_000L
        assertEquals(
            "manual fires aren't bound to a scheduled slot — they happened at nowMs",
            now,
            computeRunSlot(isManual = true, jobNextRunAtMs = futureSlot, nowMs = now),
        )
    }

    @Test
    fun `natural fire stamps the planned slot so a WorkManager replay can be detected`() {
        val now = 999_999L
        val slot = 1_000_000L
        assertEquals(slot, computeRunSlot(isManual = false, jobNextRunAtMs = slot, nowMs = now))
    }

    @Test
    fun `natural fire with null nextRunAtMs falls back to nowMs`() {
        val now = 42L
        assertEquals(now, computeRunSlot(isManual = false, jobNextRunAtMs = null, nowMs = now))
    }

    // ---------- shouldSuppressAsReplay ----------

    @Test
    fun `no prior row never suppresses`() {
        assertFalse(shouldSuppressAsReplay(priorRow = null, slotMs = 100L, nowMs = 200L))
    }

    @Test
    fun `different scheduledAtMs never suppresses (subsequent natural tick)`() {
        val prior = row(scheduledAtMs = 100L, startedAtMs = 100L)
        assertFalse(shouldSuppressAsReplay(prior, slotMs = 200L, nowMs = 200L))
    }

    @Test
    fun `matching scheduledAtMs and recent startedAtMs suppresses (genuine replay)`() {
        // A worker crashed mid-execute; WorkManager replays it ~30s later.
        val prior = row(scheduledAtMs = 1_000L, startedAtMs = 1_000L)
        assertTrue(shouldSuppressAsReplay(prior, slotMs = 1_000L, nowMs = 1_000L + 30_000L))
    }

    @Test
    fun `matching scheduledAtMs at the window edge still suppresses`() {
        val prior = row(scheduledAtMs = 1_000L, startedAtMs = 1_000L)
        // nowMs is exactly REPLAY_WINDOW_MS after the prior startedAtMs — still in window.
        assertTrue(shouldSuppressAsReplay(prior, slotMs = 1_000L, nowMs = 1_000L + REPLAY_WINDOW_MS))
    }

    @Test
    fun `matching scheduledAtMs but stale startedAtMs does NOT suppress — the real bug`() {
        // Manual fire from 16h ago stamped scheduledAtMs = future slot. Today's natural
        // fire at that slot must not be suppressed by the stale row.
        val sixteenHoursMs = 16L * 60 * 60 * 1_000L
        val prior = row(scheduledAtMs = 1_778_907_600_000L, startedAtMs = 1_778_848_056_695L)
        val nowMs = prior.startedAtMs + sixteenHoursMs
        assertFalse(
            "a 16h-old row with matching slot is not a WorkManager replay",
            shouldSuppressAsReplay(prior, slotMs = prior.scheduledAtMs, nowMs = nowMs),
        )
    }

    @Test
    fun `concurrent_skip prior row never suppresses`() {
        val prior = row(scheduledAtMs = 1_000L, startedAtMs = 1_000L, outcome = "concurrent_skip")
        assertFalse(shouldSuppressAsReplay(prior, slotMs = 1_000L, nowMs = 1_001L))
    }

    @Test
    fun `skipped_catchup prior row never suppresses`() {
        val prior = row(scheduledAtMs = 1_000L, startedAtMs = 1_000L, outcome = "skipped_catchup")
        assertFalse(shouldSuppressAsReplay(prior, slotMs = 1_000L, nowMs = 1_001L))
    }

    // ---------- backup at-most-once (shouldSuppressBackupFire) ----------

    @Test
    fun `backup suppresses on a same-slot natural row of ANY age - not a fragile short window`() {
        // The old replay window (10 min) is shorter than the backup grace (20 min): a
        // backup arriving 20 min after a slot whose side effects already happened must
        // still be suppressed. A REAL execution (startedAtMs >= scheduledAtMs) suppresses
        // at any age; only legacy manual rows (started BEFORE the slot) keep the window.
        val slot = 1_000_000L
        val prior = row(scheduledAtMs = slot, startedAtMs = slot)
        val backupFiresAt = slot + 20L * 60_000L
        assertTrue(
            "a backup 20min after the slot must NOT re-execute it (at-most-once)",
            shouldSuppressBackupFire(prior, nowMs = backupFiresAt),
        )
        assertTrue(
            "even a real same-slot execution many hours later suppresses the backup",
            shouldSuppressBackupFire(
                row(scheduledAtMs = slot, startedAtMs = slot + 3L * 60 * 60 * 1_000L),
                nowMs = backupFiresAt,
            ),
        )
    }

    @Test
    fun `backup executes when the slot never ran - permission revoked before the alarm`() {
        // Nothing ever executed this slot (the exact alarm was deleted by a permission
        // revoke before it could fire): the backup IS the first execution.
        assertFalse(shouldSuppressBackupFire(priorNonSkipRow = null, nowMs = 1_000L))
    }

    @Test
    fun `newer concurrent_skip does not mask the real same-slot row`() {
        // The DAO's getMostRecentNonSkipForSlot excludes concurrent_skip rows, so the
        // "most recent" row seen by the guard is the REAL same-slot run row even when a
        // concurrent_skip was written later. The backup must suppress against that real row.
        val slot = 1_000L
        val realRow = row(scheduledAtMs = slot, startedAtMs = slot, outcome = "running")
        // (The concurrent_skip row itself is separately asserted to never suppress below.)
        assertTrue(shouldSuppressBackupFire(realRow, nowMs = slot + 60_000L))
        // A non-backup natural fire (window rule) also suppresses against the real row.
        assertTrue(shouldSuppressAsReplay(realRow, slotMs = slot, nowMs = slot + 60_000L))
        // And the concurrent_skip row can never suppress anything (it is excluded upstream).
        val skipRow = row(scheduledAtMs = slot, startedAtMs = slot + 5_000L, outcome = "concurrent_skip")
        assertFalse(shouldSuppressAsReplay(skipRow, slotMs = slot, nowMs = slot + 60_000L))
    }

    // ---------- unified source-aware rule (shouldSuppressSameSlotFire) ----------

    @Test
    fun `real natural row suppresses a duplicate even 20 minutes later`() {
        // WorkManager retry backoff can exceed the old 10-min window. The real execution
        // (startedAtMs >= scheduledAtMs) suppresses its duplicate at any age.
        val slot = 1_000L
        val prior = row(scheduledAtMs = slot, startedAtMs = slot)
        assertTrue(shouldSuppressAsReplay(prior, slotMs = slot, nowMs = slot + 20L * 60_000L))
    }

    @Test
    fun `real natural row suppresses a duplicate even hours later`() {
        val slot = 1_000L
        val prior = row(scheduledAtMs = slot, startedAtMs = slot)
        assertTrue(shouldSuppressAsReplay(prior, slotMs = slot, nowMs = slot + 5L * 60 * 60 * 1_000L))
    }

    @Test
    fun `backup-first then late primary still suppresses - both racing directions`() {
        // The safety backup fires at slot + EXACT_BACKUP_GRACE_MS and executes the slot; the
        // exact-alarm primary can arrive much later (WorkManager backoff / process death).
        // It must NOT re-execute the slot.
        val slot = 1_000L
        val backupRow = row(scheduledAtMs = slot, startedAtMs = slot + 20L * 60_000L)
        val latePrimaryNow = slot + 3L * 60 * 60 * 1_000L
        assertTrue(
            "a primary arriving hours after the backup executed the slot must be suppressed",
            shouldSuppressAsReplay(backupRow, slotMs = slot, nowMs = latePrimaryNow),
        )
        // And the reverse: a primary that executed first suppresses a backup arriving later.
        val primaryRow = row(scheduledAtMs = slot, startedAtMs = slot)
        assertTrue(
            "a backup arriving after the primary executed the slot must be suppressed",
            shouldSuppressBackupFire(primaryRow, nowMs = slot + 20L * 60_000L),
        )
    }

    @Test
    fun `backup never suppresses on a skip row`() {
        // Skip outcomes never participate (the DAO already excludes them); this pins the
        // pure rule's totality for the backup wrapper too.
        val skip = row(scheduledAtMs = 1_000L, startedAtMs = 1_000L, outcome = "concurrent_skip")
        assertFalse(shouldSuppressBackupFire(skip, nowMs = 1_001L))
    }

    @Test
    fun `legacy manual row only suppresses within the window for the backup too`() {
        // A legacy manual row (startedAtMs BEFORE its stamped future slot) must not suppress
        // a fire that arrives much later — the same source-aware rule as the natural path.
        val slot = 1_778_907_600_000L
        val legacyManualRow = row(scheduledAtMs = slot, startedAtMs = slot - 16L * 60 * 60 * 1_000L)
        assertFalse(shouldSuppressBackupFire(legacyManualRow, nowMs = slot))
        // A fresh legacy-style row inside the window still suppresses (a genuine replay of
        // the manual fire's own slot-stamped run).
        val fresh = row(scheduledAtMs = slot, startedAtMs = slot - 5L * 60_000L)
        assertTrue(shouldSuppressBackupFire(fresh, nowMs = slot))
    }

    @Test
    fun `16h old legacy manual row still does not suppress a non-backup natural fire`() {
        // Compatibility protection preserved: a legacy row from the old buggy version that
        // stamped the future slot into a manual fire must not suppress today's real natural
        // fire (non-backup path keeps the recency window).
        val slot = 1_778_907_600_000L
        val legacyManualRow = row(scheduledAtMs = slot, startedAtMs = slot - 16L * 60 * 60 * 1_000L)
        assertFalse(shouldSuppressAsReplay(legacyManualRow, slotMs = slot, nowMs = slot))
    }

    // ---------- composition: the actual bug end-to-end (pure-function form) ----------

    @Test
    fun `manual fire at creation does not poison the next natural fire`() {
        // Replays the live Daily Weather Brief sequence using only the pure helpers.
        val createdAtMs = 1_778_848_026_977L                  // May 15 3:27:06 PM Riyadh
        val plannedSlot = 1_778_907_600_000L                  // May 16 8:00:00 AM Riyadh

        // 1. LLM calls trigger_job_now 30s after creation. Manual fire stamps nowMs.
        val manualNow = createdAtMs + 30_000L
        val manualSlot = computeRunSlot(isManual = true, jobNextRunAtMs = plannedSlot, nowMs = manualNow)
        assertEquals("manual fire must NOT stamp the future slot", manualNow, manualSlot)
        val manualRow = row(scheduledAtMs = manualSlot, startedAtMs = manualNow)

        // 2. May 16 8:00 AM — natural fire. Stamps the planned slot. Guard sees the
        //    manual row but its scheduledAtMs is different (manualNow != plannedSlot)
        //    AND it's 16h old. Both halves of the fix must hold; either alone suffices.
        val naturalNow = plannedSlot
        val naturalSlot = computeRunSlot(isManual = false, jobNextRunAtMs = plannedSlot, nowMs = naturalNow)
        assertEquals(plannedSlot, naturalSlot)
        assertFalse(
            "natural fire must not be suppressed by a stale manual row",
            shouldSuppressAsReplay(manualRow, slotMs = naturalSlot, nowMs = naturalNow),
        )
    }
}
