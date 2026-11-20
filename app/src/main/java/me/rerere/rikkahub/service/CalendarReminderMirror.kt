package me.rerere.rikkahub.service

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity
import me.rerere.rikkahub.data.preferences.ScheduleDeliveryPreferences
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Optional system Calendar reminder mirror for scheduled jobs.
 *
 * Writes the upcoming slot of an enabled scheduled job into a writable calendar via
 * [CalendarContract] with an explicit reminder alert. Operations are serialized per job ID,
 * require BOTH event and reminder insertion before reporting success, and only drop stored
 * event mappings once deletion succeeds (queuing uncleaned event IDs for retry on failure
 * or permission revocation).
 */
object CalendarReminderMirror {

    private const val TAG = "CalendarReminderMirror"
    private const val EVENT_DURATION_MS = 5 * 60_000L
    private const val UID_SUFFIX = "@calendar-mirror.rikkahub"
    private const val OWNED_ROW = "${Events._ID}=? AND ${Events.CUSTOM_APP_PACKAGE}=? AND " +
        "${Events.CUSTOM_APP_URI}=? AND ${Events.UID_2445}=? AND ${Events.DELETED}=0"
    private val jobLocks = ConcurrentHashMap<String, Mutex>()

    private fun lockFor(jobId: String): Mutex = jobLocks.computeIfAbsent(jobId) { Mutex() }

    private data class OwnedEvent(val id: Long, val uid: String, val jobId: String) {
        fun selectionArgs(context: Context) = arrayOf(id.toString(), context.packageName, id.toString(), uid)
    }

    fun hasCalendarPermissions(context: Context): Boolean {
        val read = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CALENDAR,
        ) == PackageManager.PERMISSION_GRANTED
        val write = ContextCompat.checkSelfPermission(
            context, Manifest.permission.WRITE_CALENDAR,
        ) == PackageManager.PERMISSION_GRANTED
        return read && write
    }

    fun hasWritableCalendar(context: Context): Boolean =
        hasCalendarPermissions(context) && findWritableCalendarId(context) != null

    /**
     * Retries deleting any previously stranded calendar event IDs (e.g. after permission was
     * temporarily revoked or Calendar provider threw). Returns remaining uncleaned count.
     */
    suspend fun retryPendingDeletes(
        context: Context,
        prefs: ScheduleDeliveryPreferences,
    ): Int {
        val pending = runCatching { prefs.pendingCalendarDeletes() }.getOrDefault(emptySet())
        if (pending.isEmpty()) return 0
        if (!hasCalendarPermissions(context)) return pending.size
        var remaining = 0
        for (eventId in pending) {
            if (deleteCalendarEventConfirmed(context, eventId)) {
                prefs.removePendingCalendarDelete(eventId)
            } else {
                remaining++
            }
        }
        return remaining
    }

    /**
     * Inserts or updates the mirrored Calendar event for [job]'s [nextRunAtMs] slot when
     * calendar mirroring is enabled and runtime permissions are granted. Never throws.
     */
    suspend fun syncJob(
        context: Context,
        prefs: ScheduleDeliveryPreferences,
        job: ScheduledJobEntity,
        nextRunAtMs: Long?,
    ): String = lockFor(job.id).withLock {
        val cfg = runCatching { prefs.current() }.getOrNull() ?: return@withLock "calendar:config_error"
        if (!cfg.calendarMirrorEnabled) return@withLock "calendar:disabled"
        if (!hasCalendarPermissions(context)) return@withLock "calendar:permission_missing"
        retryPendingDeletes(context, prefs)
        if (!job.enabled || nextRunAtMs == null || nextRunAtMs <= 0L) {
            val cleaned = removeJobLocked(context, prefs, job.id)
            return@withLock if (cleaned) "calendar:removed" else "calendar:cleanup_pending"
        }

        return@withLock runCatching {
            val resolver = context.contentResolver
            // A cached ID is only a preference among rows whose ownership the provider confirms.
            // Legacy description-only mirrors are intentionally left untouched.
            val ownedEvents = findOwnedEvents(context).filter { it.jobId == job.id }
            val mappedId = prefs.calendarEventIdFor(job.id)
            val existing = ownedEvents.firstOrNull { it.id == mappedId } ?: ownedEvents.firstOrNull()
            // Clean up any extra duplicate events belonging to this job ID.
            for (extra in ownedEvents) {
                if (extra != existing && !deleteOwnedEvent(context, extra)) {
                    prefs.addPendingCalendarDelete(extra.id)
                }
            }

            val tz = job.timezone?.takeIf { it.isNotBlank() } ?: TimeZone.getDefault().id
            val reminderMinutes = cfg.calendarReminderMinutesBefore.coerceIn(0, 120)
            val description = buildString {
                job.description?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
                append(context.getString(R.string.calendar_mirror_event_desc))
            }

            if (existing != null) {
                val updateValues = ContentValues().apply {
                    put(CalendarContract.Events.TITLE, "[RikkaHub] ${job.name}")
                    put(CalendarContract.Events.DESCRIPTION, description)
                    put(CalendarContract.Events.DTSTART, nextRunAtMs)
                    put(CalendarContract.Events.DTEND, nextRunAtMs + EVENT_DURATION_MS)
                    put(CalendarContract.Events.EVENT_TIMEZONE, tz)
                    put(CalendarContract.Events.HAS_ALARM, 1)
                }
                val updated = resolver.update(Events.CONTENT_URI, updateValues, OWNED_ROW, existing.selectionArgs(context))
                if (updated > 0) {
                    if (mappedId != existing.id) {
                        runCatching { prefs.putCalendarEventId(job.id, existing.id) }
                    }
                    if (ensureReminderExists(context, existing, reminderMinutes)) {
                        return@runCatching "calendar:updated"
                    }
                    return@runCatching "calendar:reminder_insert_failed"
                }
            }

            val calendarId = findWritableCalendarId(context)
                ?: return@runCatching "calendar:no_writable_calendar"

            val uid = "${job.id}.${UUID.randomUUID()}$UID_SUFFIX"
            val insertValues = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, "[RikkaHub] ${job.name}")
                put(CalendarContract.Events.DESCRIPTION, description)
                put(CalendarContract.Events.DTSTART, nextRunAtMs)
                put(CalendarContract.Events.DTEND, nextRunAtMs + EVENT_DURATION_MS)
                put(CalendarContract.Events.EVENT_TIMEZONE, tz)
                put(CalendarContract.Events.HAS_ALARM, 1)
                put(Events.CUSTOM_APP_PACKAGE, context.packageName)
                put(Events.UID_2445, uid)
            }
            // The private URI is relative to this provider's event table. Back references bind
            // it to the newly inserted row ID in the same transaction as the reminder: a copy
            // that inherits all metadata will have a different row ID and is never ours.
            val results = resolver.applyBatch(CalendarContract.AUTHORITY, arrayListOf(
                ContentProviderOperation.newInsert(Events.CONTENT_URI)
                    .withValues(insertValues)
                    .build(),
                ContentProviderOperation.newUpdate(Events.CONTENT_URI)
                    .withSelection("${Events._ID}=?", arrayOf(""))
                    .withSelectionBackReference(0, 0)
                    .withValueBackReference(Events.CUSTOM_APP_URI, 0)
                    .withExpectedCount(1)
                    .build(),
                ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI)
                    .withValues(reminderValues(reminderMinutes))
                    .withValueBackReference(CalendarContract.Reminders.EVENT_ID, 0)
                    .build(),
            ))
            val eventUri = results.first().uri ?: return@runCatching "calendar:insert_failed"
            val newEvent = OwnedEvent(ContentUris.parseId(eventUri), uid, job.id)

            try {
                prefs.putCalendarEventId(job.id, newEvent.id)
            } catch (t: Throwable) {
                // Roll back the inserted event if DataStore commit failed so it cannot become unmapped.
                if (!deleteOwnedEvent(context, newEvent)) {
                    runCatching { prefs.addPendingCalendarDelete(newEvent.id) }
                }
                throw t
            }
            "calendar:synced"
        }.getOrElse { err ->
            Log.w(TAG, "syncJob(${job.id}) failed", err)
            "calendar:error:${err::class.simpleName}"
        }
    }

    suspend fun removeJob(
        context: Context,
        prefs: ScheduleDeliveryPreferences,
        jobId: String,
    ): Boolean = lockFor(jobId).withLock {
        removeJobLocked(context, prefs, jobId)
    }

    private suspend fun removeJobLocked(
        context: Context,
        prefs: ScheduleDeliveryPreferences,
        jobId: String,
    ): Boolean {
        val mappedId = runCatching { prefs.calendarEventIdFor(jobId) }.getOrNull()
        val hasPerm = hasCalendarPermissions(context)
        if (!hasPerm) {
            if (mappedId != null) {
                // Keep mapping intact AND record pending delete so cleanup retries once permission returns.
                prefs.addPendingCalendarDelete(mappedId)
                return false
            }
            return true
        }
        val targets = runCatching { findOwnedEvents(context).filter { it.jobId == jobId } }
            .getOrElse {
                Log.w(TAG, "Unable to verify mirror ownership for $jobId", it)
                if (mappedId != null) prefs.addPendingCalendarDelete(mappedId)
                return false
            }

        var allDeleted = true
        for (event in targets) {
            val deleted = deleteOwnedEvent(context, event)
            if (deleted) {
                prefs.removePendingCalendarDelete(event.id)
            } else {
                prefs.addPendingCalendarDelete(event.id)
                allDeleted = false
            }
        }
        if (allDeleted && mappedId != null) {
            prefs.removeCalendarEventId(jobId)
        }
        return allDeleted
    }

    private fun findOwnedEvents(context: Context, eventId: Long? = null): List<OwnedEvent> {
        val uri = if (eventId == null) Events.CONTENT_URI else ContentUris.withAppendedId(Events.CONTENT_URI, eventId)
        val cursor = checkNotNull(context.contentResolver.query(
            uri,
            arrayOf(Events._ID, Events.CUSTOM_APP_URI, Events.UID_2445),
            "${Events.CUSTOM_APP_PACKAGE}=? AND ${Events.DELETED}=0",
            arrayOf(context.packageName),
            "${Events._ID} ASC",
        )) { "Calendar ownership query unavailable" }
        return cursor.use {
            val owned = mutableListOf<OwnedEvent>()
            while (it.moveToNext()) {
                val id = it.getLong(0)
                val uid = it.getString(2) ?: continue
                if (it.getString(1) != id.toString() || !uid.endsWith(UID_SUFFIX)) continue
                val jobId = uid.removeSuffix(UID_SUFFIX).substringBeforeLast('.', missingDelimiterValue = "")
                if (jobId.isNotEmpty()) owned += OwnedEvent(id, uid, jobId)
            }
            owned
        }
    }

    suspend fun clearAll(
        context: Context,
        prefs: ScheduleDeliveryPreferences,
    ): Int {
        val all = runCatching { prefs.allCalendarEvents() }.getOrDefault(emptyMap())
        var uncleaned = 0
        val ownedJobIds = if (hasCalendarPermissions(context)) {
            runCatching { findOwnedEvents(context).map { it.jobId } }.getOrElse {
                Log.w(TAG, "Unable to verify unmapped mirror ownership", it)
                uncleaned++
                emptyList()
            }
        } else {
            emptyList()
        }
        for (jobId in all.keys + ownedJobIds) {
            if (!removeJob(context, prefs, jobId)) {
                uncleaned++
            }
        }
        uncleaned += retryPendingDeletes(context, prefs)
        return uncleaned
    }

    private fun ensureReminderExists(context: Context, event: OwnedEvent, minutes: Int): Boolean {
        val hasExisting = runCatching {
            context.contentResolver.query(
                CalendarContract.Reminders.CONTENT_URI,
                arrayOf(CalendarContract.Reminders._ID),
                "${CalendarContract.Reminders.EVENT_ID} = ?",
                arrayOf(event.id.toString()),
                null,
            )?.use { it.moveToFirst() } ?: false
        }.getOrDefault(false)
        if (hasExisting) return true
        return runCatching {
            context.contentResolver.applyBatch(CalendarContract.AUTHORITY, arrayListOf(
                ContentProviderOperation.newAssertQuery(Events.CONTENT_URI)
                    .withSelection(OWNED_ROW, event.selectionArgs(context))
                    .withExpectedCount(1)
                    .build(),
                ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI)
                    .withValues(reminderValues(minutes))
                    .withValue(CalendarContract.Reminders.EVENT_ID, event.id)
                    .build(),
            ))
            true
        }.getOrDefault(false)
    }

    private fun reminderValues(minutes: Int) = ContentValues().apply {
        put(CalendarContract.Reminders.MINUTES, minutes)
        put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
    }

    /**
     * Pending IDs from older versions are not proof of ownership. Release such entries without
     * touching the provider row; keep the entry only when verification/deletion itself failed.
     */
    private fun deleteCalendarEventConfirmed(context: Context, eventId: Long): Boolean =
        runCatching {
            val event = findOwnedEvents(context, eventId).firstOrNull() ?: return@runCatching true
            deleteOwnedEvent(context, event)
        }.getOrElse {
            Log.w(TAG, "deleteCalendarEventConfirmed($eventId) failed", it)
            false
        }

    private fun deleteOwnedEvent(context: Context, event: OwnedEvent): Boolean = runCatching {
        // Collection URI is required: Events item URIs do not accept a delete selection.
        val deleted = context.contentResolver.delete(Events.CONTENT_URI, OWNED_ROW, event.selectionArgs(context))
        deleted > 0 || findOwnedEvents(context, event.id).none { it == event }
    }.getOrElse {
        Log.w(TAG, "Unable to delete mirror ${event.id}", it)
        false
    }

    private fun findWritableCalendarId(context: Context): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
        )
        val selection =
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}"
        return runCatching {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                selection,
                null,
                "${CalendarContract.Calendars.IS_PRIMARY} DESC",
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else null
            }
        }.getOrNull()
    }
}
