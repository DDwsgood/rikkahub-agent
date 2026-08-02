package me.rerere.rikkahub.workflow.trigger

import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [TimeCronPolicy] decision helpers. These cover the scheduling
 * policy extracted from the time/cron family without requiring Android/Room/Koin.
 *
 * The policy functions determine:
 *  1. Whether a workflow needs to be (re)scheduled given its previous state.
 *  2. Whether the schedule is "new" (KEEP) vs a "real config change" (UPDATE/REPLACE).
 *  3. Whether a one-shot workflow should be re-enqueued after a fire.
 */
class TimeCronPolicyTest {

    private fun wf(
        id: String = "wf-1",
        enabled: Boolean = true,
        trigger: TriggerSpec.TimeCron = TriggerSpec.TimeCron(timeOfDay = "09:00"),
        updatedAtMs: Long = 1000L,
    ) = WorkflowDefinition(
        id = id,
        name = "test",
        trigger = trigger,
        actions = emptyList(),
        enabled = enabled,
        updatedAtMs = updatedAtMs,
    )

    // --- needsScheduling ---

    @Test fun `null previous always needs scheduling`() {
        assertTrue(TimeCronPolicy.needsScheduling(prev = null, current = wf()))
    }

    @Test fun `identical enabled workflow does not need rescheduling`() {
        val w = wf()
        assertFalse(TimeCronPolicy.needsScheduling(prev = w, current = w))
    }

    @Test fun `trigger change needs rescheduling`() {
        val prev = wf(trigger = TriggerSpec.TimeCron(timeOfDay = "09:00"))
        val curr = wf(trigger = TriggerSpec.TimeCron(timeOfDay = "10:00"))
        assertTrue(TimeCronPolicy.needsScheduling(prev, curr))
    }

    @Test fun `updatedAtMs change needs rescheduling`() {
        val prev = wf(updatedAtMs = 1000L)
        val curr = wf(updatedAtMs = 2000L)
        assertTrue(TimeCronPolicy.needsScheduling(prev, curr))
    }

    @Test fun `previously disabled needs rescheduling even if config unchanged`() {
        val prev = wf(enabled = false)
        val curr = wf(enabled = true)
        // Same trigger + same updatedAtMs, but prev was disabled
        assertTrue(TimeCronPolicy.needsScheduling(prev, curr))
    }

    // --- isNewSchedule ---

    @Test fun `null previous is new schedule`() {
        assertTrue(TimeCronPolicy.isNewSchedule(prev = null))
    }

    @Test fun `previously disabled is new schedule`() {
        assertTrue(TimeCronPolicy.isNewSchedule(prev = wf(enabled = false)))
    }

    @Test fun `previously enabled is not new schedule`() {
        assertFalse(TimeCronPolicy.isNewSchedule(prev = wf(enabled = true)))
    }

    // --- shouldReEnqueueOneShot ---

    @Test fun `null period re-enqueues one-shot`() {
        // Arbitrary 5-field cron -> null period -> one-shot path
        assertTrue(TimeCronPolicy.shouldReEnqueueOneShot(periodMs = null))
    }

    @Test fun `period below 15 minutes re-enqueues one-shot`() {
        // @every 60s = 60000ms, below 15min minimum -> one-shot reschedule
        assertTrue(TimeCronPolicy.shouldReEnqueueOneShot(periodMs = 60_000L))
    }

    @Test fun `period exactly 15 minutes does not re-enqueue one-shot`() {
        // 15min = 900000ms, at the periodic boundary -> periodic path
        assertFalse(TimeCronPolicy.shouldReEnqueueOneShot(periodMs = 900_000L))
    }

    @Test fun `period above 15 minutes does not re-enqueue one-shot`() {
        // @hourly = 3600000ms -> periodic path
        assertFalse(TimeCronPolicy.shouldReEnqueueOneShot(periodMs = 3_600_000L))
    }

    @Test fun `24h daily period does not re-enqueue one-shot`() {
        assertFalse(TimeCronPolicy.shouldReEnqueueOneShot(periodMs = 24L * 60 * 60 * 1000))
    }
}
