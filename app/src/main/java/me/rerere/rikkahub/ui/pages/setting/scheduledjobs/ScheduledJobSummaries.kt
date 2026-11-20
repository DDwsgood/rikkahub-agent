package me.rerere.rikkahub.ui.pages.setting.scheduledjobs

import android.content.Context
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity
import me.rerere.rikkahub.service.CronJobScheduler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One-line human summary of a job's schedule. Used in the list row's "When:" subtitle and at
 * the top of the detail screen. We intentionally keep this short — the detail screen renders
 * the full breakdown elsewhere.
 *
 * Examples:
 *   once at Wed 16:09          (one-shot, future)
 *   once at 7 May 16:09        (one-shot, > 24h away)
 *   every day at 09:00         (cron `0 9 * * *` or @daily)
 *   every 30 min               (cron `@every 30m`)
 *   every Mon, Wed, Fri 09:00  (cron "0 9 * * MON,WED,FRI")
 *   custom: <expr>              (cron we couldn't pretty-print)
 */
private interface ScheduleSummaryStrings {
    val locale: Locale
    fun onceNoTime(): String
    fun onceAt(time: String): String
    fun custom(expr: String): String
    fun everyHour(): String
    fun daily(time: String): String
    fun weekly(time: String): String
    fun monthly(time: String): String
    fun yearly(time: String): String
    fun everyInterval(rest: String): String
    fun everyMinute(): String
    fun everyNMinutes(n: Int): String
    fun everyNHours(n: Int): String
    fun everyDayAt(time: String): String
    fun everyDaysAt(days: String, time: String): String
    fun modeLlm(): String
    fun modeDirect(): String
}

private object DefaultScheduleSummaryStrings : ScheduleSummaryStrings {
    override val locale: Locale = Locale.ENGLISH
    override fun onceNoTime(): String = "once (no time set)"
    override fun onceAt(time: String): String = "once at $time"
    override fun custom(expr: String): String = "custom: $expr"
    override fun everyHour(): String = "every hour"
    override fun daily(time: String): String = "every day at $time"
    override fun weekly(time: String): String = "every Sunday at $time"
    override fun monthly(time: String): String = "first of every month at $time"
    override fun yearly(time: String): String = "every Jan 1 at $time"
    override fun everyInterval(rest: String): String = "every $rest"
    override fun everyMinute(): String = "every minute"
    override fun everyNMinutes(n: Int): String = "every $n min"
    override fun everyNHours(n: Int): String = "every $n hours"
    override fun everyDayAt(time: String): String = "every day at $time"
    override fun everyDaysAt(days: String, time: String): String = "every $days at $time"
    override fun modeLlm(): String = "LLM-driven"
    override fun modeDirect(): String = "direct (no LLM)"
}

private class ContextScheduleSummaryStrings(private val context: Context) : ScheduleSummaryStrings {
    override val locale: Locale get() = Locale.getDefault()
    override fun onceNoTime(): String = context.getString(R.string.schedule_summary_once_no_time)
    override fun onceAt(time: String): String = context.getString(R.string.schedule_summary_once_at, time)
    override fun custom(expr: String): String = context.getString(R.string.schedule_summary_custom, expr)
    override fun everyHour(): String = context.getString(R.string.schedule_summary_every_hour)
    override fun daily(time: String): String = context.getString(R.string.schedule_summary_daily, time)
    override fun weekly(time: String): String = context.getString(R.string.schedule_summary_weekly, time)
    override fun monthly(time: String): String = context.getString(R.string.schedule_summary_monthly, time)
    override fun yearly(time: String): String = context.getString(R.string.schedule_summary_yearly, time)
    override fun everyInterval(rest: String): String = context.getString(R.string.schedule_summary_every_interval, rest)
    override fun everyMinute(): String = context.getString(R.string.schedule_summary_every_minute)
    override fun everyNMinutes(n: Int): String = context.getString(R.string.schedule_summary_every_n_minutes, n)
    override fun everyNHours(n: Int): String = context.getString(R.string.schedule_summary_every_n_hours, n)
    override fun everyDayAt(time: String): String = context.getString(R.string.schedule_summary_every_day_at, time)
    override fun everyDaysAt(days: String, time: String): String =
        context.getString(R.string.schedule_summary_every_days_at, days, time)
    override fun modeLlm(): String = context.getString(R.string.schedule_mode_llm)
    override fun modeDirect(): String = context.getString(R.string.schedule_mode_direct)
}

fun summariseSchedule(context: Context, job: ScheduledJobEntity): String =
    summariseSchedule(ContextScheduleSummaryStrings(context), job)

fun summariseSchedule(job: ScheduledJobEntity): String =
    summariseSchedule(DefaultScheduleSummaryStrings, job)

private fun summariseSchedule(strings: ScheduleSummaryStrings, job: ScheduledJobEntity): String {
    return when (job.scheduleType) {
        "once" -> {
            val ms = job.atUnixMs ?: return strings.onceNoTime()
            strings.onceAt(formatAbsoluteTime(ms, strings.locale))
        }
        "cron" -> {
            val expr = job.cronExpression?.trim().orEmpty()
            prettyCron(strings, expr) ?: strings.custom(expr)
        }
        else -> job.scheduleType
    }
}

private fun formatAbsoluteTime(ms: Long, locale: Locale = Locale.getDefault()): String {
    val now = System.currentTimeMillis()
    val ageMs = ms - now
    val sameDayCutoff = 24L * 60 * 60 * 1000
    return if (ageMs in 0..sameDayCutoff) {
        SimpleDateFormat("EEE HH:mm", locale).format(Date(ms))
    } else {
        SimpleDateFormat("d MMM HH:mm", locale).format(Date(ms))
    }
}

/**
 * Best-effort pretty-printer for the supported cron forms. Returns null when the expression
 * is too complex to summarise cleanly — caller falls back to "custom: <expr>".
 *
 * Recognised:
 *  - @hourly, @daily, @weekly, @monthly, @yearly
 *  - @every Ns|Nm|Nh|Nd
 *  - "M H * * *"              -> "every day at HH:MM"
 *  - "M H * * MON,WED,FRI"    -> "every Mon, Wed, Fri at HH:MM"
 *  - "(slash)N * * * *"       -> "every N min"  (where (slash)N is `*` followed by `/N`)
 *  - "0 (slash)N * * *"       -> "every N hours"
 */
private fun prettyCron(strings: ScheduleSummaryStrings, expr: String): String? {
    val e = expr.trim()
    if (e.isEmpty()) return null
    when (e.lowercase()) {
        "@hourly" -> return strings.everyHour()
        "@daily", "@midnight" -> return strings.daily("00:00")
        "@weekly" -> return strings.weekly("00:00")
        "@monthly" -> return strings.monthly("00:00")
        "@yearly", "@annually" -> return strings.yearly("00:00")
    }
    if (e.startsWith("@every", ignoreCase = true)) {
        val rest = e.substring("@every".length).trim()
        return strings.everyInterval(rest)
    }
    val parts = e.split(Regex("\\s+"))
    if (parts.size != 5) return null
    val (minute, hour, dom, month, dow) = parts.let { listOf(it[0], it[1], it[2], it[3], it[4]) }

    // every N min — "*/N * * * *"
    if (hour == "*" && dom == "*" && month == "*" && dow == "*" && minute.matches(Regex("\\*/\\d+"))) {
        val n = minute.removePrefix("*/").toIntOrNull() ?: return null
        return if (n == 1) strings.everyMinute()
        else strings.everyNMinutes(n)
    }
    // every N hours — "0 */N * * *"
    if (minute == "0" && dom == "*" && month == "*" && dow == "*" && hour.matches(Regex("\\*/\\d+"))) {
        val n = hour.removePrefix("*/").toIntOrNull() ?: return null
        return if (n == 1) strings.everyHour()
        else strings.everyNHours(n)
    }
    // every day at HH:MM — "M H * * *"
    if (dom == "*" && month == "*" && minute.toIntOrNull() in 0..59 && hour.toIntOrNull() in 0..23) {
        val hh = hour.toInt()
        val mm = minute.toInt()
        val time = "%02d:%02d".format(hh, mm)
        if (dow == "*") return strings.everyDayAt(time)
        // specific weekdays — "MON,WED,FRI" or "1,3,5"
        val days = parseDows(dow) ?: return null
        if (days.size in 1..6) {
            val dayNames = days.joinToString(", ") {
                java.time.DayOfWeek.of(it).getDisplayName(
                    java.time.format.TextStyle.SHORT,
                    strings.locale,
                )
            }
            return strings.everyDaysAt(dayNames, time)
        }
    }
    return null
}

private val DOW_INDEX = mapOf(
    "0" to 7, "7" to 7, "1" to 1, "2" to 2,
    "3" to 3, "4" to 4, "5" to 5, "6" to 6,
    "SUN" to 7, "MON" to 1, "TUE" to 2,
    "WED" to 3, "THU" to 4, "FRI" to 5, "SAT" to 6,
)

private fun parseDows(spec: String): List<Int>? {
    if (spec.contains("/") || spec.contains("-")) return null
    val parts = spec.split(",").map { it.trim().uppercase() }
    val days = parts.map { DOW_INDEX[it] ?: return null }
    return days
}

/**
 * Returns "llm" or "direct" + a short human label. Used to render the mode chip / row label.
 */
fun modeLabel(context: Context, job: ScheduledJobEntity): String =
    modeLabel(ContextScheduleSummaryStrings(context), job)

fun modeLabel(job: ScheduledJobEntity): String =
    modeLabel(DefaultScheduleSummaryStrings, job)

private fun modeLabel(strings: ScheduleSummaryStrings, job: ScheduledJobEntity): String = when (job.mode) {
    "llm" -> strings.modeLlm()
    "direct" -> strings.modeDirect()
    else -> job.mode
}

/**
 * Pure function that maps (mode, exactAlarmGranted) to a backend enum, mirroring
 * [CronJobScheduler.selectBackend]. Used by the UI to render the strategy line without
 * needing a Context/AlarmManager.
 */
fun selectBackendLabel(mode: String, exactAlarmGranted: Boolean): CronJobScheduler.Backend =
    CronJobScheduler.selectBackend(mode, exactAlarmGranted)

fun formatAbsoluteForDetail(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))
