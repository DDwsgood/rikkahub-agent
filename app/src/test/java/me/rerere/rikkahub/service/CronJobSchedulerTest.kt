package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class CronJobSchedulerTest {

    private fun job(
        scheduleType: String = "cron",
        cron: String? = "0 9 * * *",
        atMs: Long? = null,
        timezone: String? = "America/New_York",
        startAt: Long? = null,
        endAt: Long? = null,
        maxRuns: Int? = null,
        runsSoFar: Int = 0,
        lastRun: Long? = null,
        enabled: Boolean = true,
        precision: String = "flexible",
    ) = ScheduledJobEntity(
        id = "j", name = "n", prompt = "p", assistantId = "a",
        scheduleType = scheduleType, atUnixMs = atMs, cronExpression = cron,
        timezone = timezone, startAtUnixMs = startAt, endAtUnixMs = endAt,
        maxRuns = maxRuns, runsSoFar = runsSoFar, lastRunAtMs = lastRun,
        enabled = enabled, createdAtMs = 0L, schedulePrecision = precision,
    )

    @Test
    fun `disabled returns null`() {
        assertNull(CronJobScheduler.nextRunMs(job(enabled = false), nowMs = 0L))
    }

    @Test
    fun `once future returns its timestamp`() {
        val at = 1_800_000_000_000L
        assertEquals(at, CronJobScheduler.nextRunMs(job(scheduleType = "once", atMs = at), nowMs = 0L))
    }

    @Test
    fun `once already-fired returns null`() {
        val at = 100L
        assertNull(CronJobScheduler.nextRunMs(
            job(scheduleType = "once", atMs = at, lastRun = at + 1), nowMs = at + 100L))
    }

    @Test
    fun `cron simple daily 9am NY tz`() {
        // Basis: 2026-06-01 08:00 NY local → next is 2026-06-01 09:00 NY
        val zone = ZoneId.of("America/New_York")
        val basis = LocalDateTime.of(2026, 6, 1, 8, 0).atZone(zone).toInstant().toEpochMilli()
        val expected = LocalDateTime.of(2026, 6, 1, 9, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, CronJobScheduler.nextRunMs(job(), nowMs = basis))
    }

    @Test
    fun `start_at_unix_ms in future delays first fire`() {
        val zone = ZoneId.of("America/New_York")
        val now = LocalDateTime.of(2026, 6, 1, 8, 0).atZone(zone).toInstant().toEpochMilli()
        val startAt = LocalDateTime.of(2026, 6, 5, 0, 0).atZone(zone).toInstant().toEpochMilli()
        val expected = LocalDateTime.of(2026, 6, 5, 9, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, CronJobScheduler.nextRunMs(job(startAt = startAt), nowMs = now))
    }

    @Test
    fun `end_at_unix_ms past returns null`() {
        val past = 100L
        assertNull(CronJobScheduler.nextRunMs(job(endAt = past), nowMs = past + 1000L))
    }

    @Test
    fun `max_runs reached returns null`() {
        assertNull(CronJobScheduler.nextRunMs(job(maxRuns = 5, runsSoFar = 5), nowMs = 0L))
    }

    @Test
    fun `backend selection - direct mode with permission uses alarm clock`() {
        assertEquals(
            CronJobScheduler.Backend.ALARM_CLOCK_DIRECT,
            CronJobScheduler.selectBackend("direct", exactAlarmGranted = true),
        )
    }

    @Test
    fun `backend selection - llm mode with permission uses exact alarm`() {
        assertEquals(
            CronJobScheduler.Backend.EXACT_ALARM_LLM,
            CronJobScheduler.selectBackend("llm", exactAlarmGranted = true),
        )
    }

    @Test
    fun `backend selection - no permission falls back to WorkManager for both modes`() {
        assertEquals(
            CronJobScheduler.Backend.WORK_MANAGER_FALLBACK,
            CronJobScheduler.selectBackend("direct", exactAlarmGranted = false),
        )
        assertEquals(
            CronJobScheduler.Backend.WORK_MANAGER_FALLBACK,
            CronJobScheduler.selectBackend("llm", exactAlarmGranted = false),
        )
    }

    @Test
    fun `backend selection - unknown mode falls back to WorkManager even with permission`() {
        assertEquals(
            CronJobScheduler.Backend.WORK_MANAGER_FALLBACK,
            CronJobScheduler.selectBackend("unknown", exactAlarmGranted = true),
        )
    }

    @Test
    fun `legacy precision does not influence backend selection`() {
        // The schedulePrecision column still exists (no Room migration) but mode drives
        // the backend, not precision. A legacy 'exact' row with mode='llm' still gets
        // EXACT_ALARM_LLM when permission is granted.
        assertEquals(
            CronJobScheduler.Backend.EXACT_ALARM_LLM,
            CronJobScheduler.selectBackend("llm", exactAlarmGranted = true),
        )
    }

    @Test
    fun `legacy exact rows still compute a next run`() {
        // Historical rows persisted with schedulePrecision='exact' must keep scheduling —
        // precision no longer affects backend choice, only the fire time matters.
        val at = 1_800_000_000_000L
        assertEquals(
            at,
            CronJobScheduler.nextRunMs(
                job(scheduleType = "once", atMs = at, precision = CronJobScheduler.PRECISION_EXACT),
                nowMs = 0L,
            ),
        )
    }

    @Test
    fun `exact execution work identity is stable per job slot`() {
        val first = CronJobScheduler.exactExecutionWorkName("job-1", 1_000L)
        assertEquals(first, CronJobScheduler.exactExecutionWorkName("job-1", 1_000L))
        assertTrue(first != CronJobScheduler.exactExecutionWorkName("job-1", 2_000L))
        assertTrue(first != CronJobScheduler.exactExecutionWorkName("job-2", 1_000L))
    }

    @Test
    fun `direct execution work identity is stable per job slot`() {
        val first = CronJobScheduler.directExecutionWorkName("job-1", 1_000L)
        assertEquals(first, CronJobScheduler.directExecutionWorkName("job-1", 1_000L))
        assertTrue(first != CronJobScheduler.directExecutionWorkName("job-1", 2_000L))
        assertTrue(first != CronJobScheduler.directExecutionWorkName("job-2", 1_000L))
    }

    @Test
    fun `direct and llm work names do not collide`() {
        val direct = CronJobScheduler.directExecutionWorkName("job-1", 1_000L)
        val llm = CronJobScheduler.exactExecutionWorkName("job-1", 1_000L)
        assertTrue("direct and llm work names must differ", direct != llm)
    }

    @Test
    fun `resolveBackendAfterArm returns desired backend on successful arm`() {
        assertEquals(
            CronJobScheduler.Backend.ALARM_CLOCK_DIRECT,
            CronJobScheduler.resolveBackendAfterArm(
                CronJobScheduler.Backend.ALARM_CLOCK_DIRECT, armSucceeded = true,
            ),
        )
        assertEquals(
            CronJobScheduler.Backend.EXACT_ALARM_LLM,
            CronJobScheduler.resolveBackendAfterArm(
                CronJobScheduler.Backend.EXACT_ALARM_LLM, armSucceeded = true,
            ),
        )
    }

    @Test
    fun `resolveBackendAfterArm falls back to WORK_MANAGER_FALLBACK on SecurityException`() {
        // Simulates the race where SCHEDULE_EXACT_ALARM is revoked between
        // canScheduleExactAlarms() and setAlarmClock/setExactAndAllowWhileIdle.
        assertEquals(
            CronJobScheduler.Backend.WORK_MANAGER_FALLBACK,
            CronJobScheduler.resolveBackendAfterArm(
                CronJobScheduler.Backend.ALARM_CLOCK_DIRECT, armSucceeded = false,
            ),
        )
        assertEquals(
            CronJobScheduler.Backend.WORK_MANAGER_FALLBACK,
            CronJobScheduler.resolveBackendAfterArm(
                CronJobScheduler.Backend.EXACT_ALARM_LLM, armSucceeded = false,
            ),
        )
    }

    @Test
    fun `resolveBackendAfterArm for fallback and none backends passes through`() {
        // Non-exact backends never go through tryArmExactAlarm, so armSucceeded is
        // irrelevant — but resolveBackendAfterArm should still behave sanely.
        assertEquals(
            CronJobScheduler.Backend.WORK_MANAGER_FALLBACK,
            CronJobScheduler.resolveBackendAfterArm(
                CronJobScheduler.Backend.WORK_MANAGER_FALLBACK, armSucceeded = true,
            ),
        )
    }

    @Test
    fun `showIntent request code is a stable constant`() {
        // showIntent uses requestCode=0 (not jobId.hashCode()) to avoid PendingIntent
        // identity collisions. All jobs share one showIntent PendingIntent instance
        // (same Activity + action), which is correct since they all just open the app.
        assertEquals(0, CronJobScheduler.SHOW_INTENT_REQUEST_CODE)
    }

    @Test
    fun `work tag is stable per job`() {
        val first = CronJobScheduler.workTagFor("job-1")
        assertEquals(first, CronJobScheduler.workTagFor("job-1"))
        assertTrue(first != CronJobScheduler.workTagFor("job-2"))
    }

    @Test
    fun `DST forward skip-day next fire correct`() {
        // 0 2 * * * in America/New_York on the 2026 DST forward day (March 8 2026 2am
        // doesn't exist — clock jumps 2:00 → 3:00). Expect: skip that fire, next fire is
        // March 9 2am, NOT March 8 3am.
        val zone = ZoneId.of("America/New_York")
        // basis = March 7 2026 03:00 NY (well before the spring-forward)
        val basis = LocalDateTime.of(2026, 3, 7, 3, 0).atZone(zone).toInstant().toEpochMilli()
        val next = CronJobScheduler.nextRunMs(
            job(cron = "0 2 * * *"), nowMs = basis
        )!!
        // The very next 2am fire should be March 8 — but the wall-clock 2am doesn't exist
        // that day. cron-utils' behavior: returns the first valid clock match, which is
        // March 9 2am (the test passes if the next fire is on or after March 9). We assert
        // the date >= March 9.
        val expectedMin = LocalDateTime.of(2026, 3, 9, 0, 0).atZone(zone).toInstant().toEpochMilli()
        assertTrue("DST forward should not fire on the skip-day", next >= expectedMin)
    }
}
