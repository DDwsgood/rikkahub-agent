package me.rerere.rikkahub.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [KeepaliveEligibilityChecker.Result.allSatisfied] — the pure
 * conjunction gating the high-reliability background toggle. The Context-reading
 * half of the checker (notifications, exact alarms, widget ids) needs Android and
 * is not covered here.
 */
class KeepaliveEligibilityCheckerTest {

    private fun result(
        notifications: Boolean,
        exactAlarms: Boolean,
        widget: Boolean,
    ) = KeepaliveEligibilityChecker.Result(
        notificationsGranted = notifications,
        exactAlarmsGranted = exactAlarms,
        widgetPlaced = widget,
    )

    @Test
    fun `all satisfied only when every precondition holds`() {
        assertTrue(result(true, true, true).allSatisfied)
    }

    @Test
    fun `each missing precondition alone blocks eligibility`() {
        assertFalse(result(false, true, true).allSatisfied)
        assertFalse(result(true, false, true).allSatisfied)
        assertFalse(result(true, true, false).allSatisfied)
    }

    @Test
    fun `nothing granted is not satisfied`() {
        assertFalse(result(false, false, false).allSatisfied)
    }
}
