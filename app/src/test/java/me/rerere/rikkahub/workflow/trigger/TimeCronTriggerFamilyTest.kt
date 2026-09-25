package me.rerere.rikkahub.workflow.trigger

import me.rerere.rikkahub.workflow.model.TriggerSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class TimeCronTriggerFamilyTest {

    @Test
    fun `plain time_of_day stays on 24h periodic path`() {
        assertEquals(
            24L * 60 * 60 * 1000,
            TimeCronTriggerFamily.derivePeriodMs(TriggerSpec.TimeCron(timeOfDay = "09:00")),
        )
    }

    @Test
    fun `time_of_day with days_of_week uses one-shot path to avoid drift`() {
        assertNull(
            TimeCronTriggerFamily.derivePeriodMs(
                TriggerSpec.TimeCron(timeOfDay = "09:00", daysOfWeek = listOf(1, 3, 5)),
            ),
        )
    }

    @Test
    fun `computeNextFireMs skips to the next eligible weekday`() {
        val zone = ZoneId.of("UTC")
        // 2026-06-01 is a Monday (ISO 1). Request Wednesday + Friday 09:00.
        val now = java.time.ZonedDateTime.of(2026, 6, 1, 10, 0, 0, 0, zone)
            .toInstant().toEpochMilli()
        val next = TimeCronTriggerFamily.computeNextFireMs(
            TriggerSpec.TimeCron(timeOfDay = "09:00", daysOfWeek = listOf(3, 5)),
            zone,
            now,
        )
        val nextZdt = java.time.ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(next), zone)
        assertEquals(java.time.DayOfWeek.WEDNESDAY, nextZdt.dayOfWeek)
        assertEquals(9, nextZdt.hour)
        assertTrue(next > now)
    }
}
