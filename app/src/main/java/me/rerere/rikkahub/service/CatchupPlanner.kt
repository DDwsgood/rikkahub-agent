package me.rerere.rikkahub.service

import com.cronutils.model.time.ExecutionTime
import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity
import java.time.Instant
import java.time.ZoneId

/**
 * Pure function. Given a job's catchup policy and the last-run / now timestamps,
 * compute (a) how many WorkManager enqueues to issue and at what stagger, and
 * (b) how many run-history rows to write with outcome='skipped_catchup' for the
 * windows we deliberately drop.
 *
 * Lives outside CronBootReceiver so it can be unit-tested without WorkManager.
 */
object CatchupPlanner {

    private const val FIRE_ALL_CAP = 20
    private const val FIRE_ALL_STAGGER_MS = 2_000L

    data class CatchupPlan(
        /** Delays to pass to OneTimeWorkRequestBuilder.setInitialDelay. */
        val fireDelaysMs: List<Long>,
        /** Stable cron/once slots carried into each worker for replay-safe idempotency. */
        val fireSlotsMs: List<Long>,
        /** Number of windows we deliberately did NOT fire (record as 'skipped_catchup'). */
        val skippedCatchupCount: Int,
    )

    fun plan(job: ScheduledJobEntity, lastRunMs: Long?, nowMs: Long): CatchupPlan {
        return when (job.scheduleType) {
            "once" -> planOnce(job, nowMs)
            "cron" -> planCron(job, lastRunMs, nowMs)
            else   -> CatchupPlan(emptyList(), emptyList(), 0)
        }
    }

    /**
     * once-mode catchup: if the target time has passed and the job has never fired,
     * enqueue it immediately (delay 0). No skipped rows — the fire actually happens,
     * just late. If it already fired (lastRunAtMs != null), nothing to do.
     */
    private fun planOnce(job: ScheduledJobEntity, nowMs: Long): CatchupPlan {
        val at = job.atUnixMs ?: return CatchupPlan(emptyList(), emptyList(), 0)
        val alreadyFired = job.lastRunAtMs != null
        if (alreadyFired) return CatchupPlan(emptyList(), emptyList(), 0)
        return if (at < nowMs) {
            // Missed once-fire — enqueue immediately. The worker writes a normal run row
            // so the user sees the fire in history (just slightly late). No skipped rows
            // because no windows were deliberately dropped.
            CatchupPlan(listOf(0L), listOf(at), 0)
        } else {
            // Fire is still in the future; normal scheduler.schedule() will handle it.
            CatchupPlan(emptyList(), emptyList(), 0)
        }
    }

    private fun planCron(job: ScheduledJobEntity, lastRunMs: Long?, nowMs: Long): CatchupPlan {
        val expr = job.cronExpression ?: return CatchupPlan(emptyList(), emptyList(), 0)
        val cron = CronExpressionParser.parse(expr).getOrNull()
            ?: return CatchupPlan(emptyList(), emptyList(), 0)
        val zone = job.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
        val et = ExecutionTime.forCron(cron)

        val rawFrom = lastRunMs ?: job.createdAtMs
        // startAtUnixMs is inclusive in nextRunMs(), while matchesBetween starts strictly
        // after its cursor. Move the explicit lower bound back by 1 ms so a cron slot that
        // lands exactly on startAtUnixMs remains eligible.
        val startBasis = job.startAtUnixMs?.let { if (it == Long.MIN_VALUE) it else it - 1L }
        val from = startBasis?.let { maxOf(rawFrom, it) } ?: rawFrom
        val missedSlots = matchesBetween(et, zone, fromMsExclusive = from, toMsInclusive = nowMs)
            .let { slots ->
                job.endAtUnixMs?.let { end -> slots.filter { slot -> slot <= end } } ?: slots
            }
        val missedCount = missedSlots.size

        return when (job.catchup) {
            "skip"      -> CatchupPlan(emptyList(), emptyList(), missedCount)
            "fire_once" -> if (missedCount == 0) {
                CatchupPlan(emptyList(), emptyList(), 0)
            } else {
                CatchupPlan(listOf(0L), listOf(missedSlots.last()), missedCount - 1)
            }
            "fire_all"  -> {
                val capped = missedCount.coerceAtMost(FIRE_ALL_CAP)
                val delays = (0 until capped).map { it * FIRE_ALL_STAGGER_MS }
                val skipped = (missedCount - capped).coerceAtLeast(0)
                // Prefer the most recent capped slots. Very old autonomous actions are
                // less useful and more likely to surprise the user after a long downtime.
                CatchupPlan(delays, missedSlots.takeLast(capped), skipped)
            }
            else -> CatchupPlan(emptyList(), emptyList(), 0)
        }
    }

    /** Walk forward from [fromMsExclusive], collecting stable slots up to [toMsInclusive]. */
    private fun matchesBetween(
        et: ExecutionTime,
        zone: ZoneId,
        fromMsExclusive: Long,
        toMsInclusive: Long,
    ): List<Long> {
        if (toMsInclusive <= fromMsExclusive) return emptyList()
        var cursor = Instant.ofEpochMilli(fromMsExclusive).atZone(zone)
        val slots = ArrayList<Long>()
        while (true) {
            val next = et.nextExecution(cursor).orElse(null) ?: break
            val nextMs = next.toInstant().toEpochMilli()
            if (nextMs > toMsInclusive) break
            slots += nextMs
            cursor = next
            // Safety net — should never happen but bail if we somehow loop > 10000.
            if (slots.size > 10_000) break
        }
        return slots
    }
}
