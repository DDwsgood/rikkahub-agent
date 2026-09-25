package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private const val TAG = "Migration_28_29"

/**
 * v28 → v29 (fork↔upstream 2.4.5 merge).
 *
 * Both sides shipped a v28 schema with DIFFERENT contents:
 *  - fork v28: `scheduled_jobs.schedulePrecision` column, no query indices.
 *  - upstream v28: query indices, but no `schedulePrecision` column.
 *
 * A pure AutoMigration only covers the fork-v28 path (adds indices) and crashes on an
 * imported upstream-v28 database as soon as Room validates the missing column. This
 * hand-written migration handles both:
 *
 *  1. Probe `scheduled_jobs`; if `schedulePrecision` is missing, add it with the entity
 *     default `'flexible'` (see ScheduledJobEntity).
 *  2. `CREATE INDEX IF NOT EXISTS` for the six v29 indices. For a fork-v28 source this
 *     adds them; for an upstream-v28 source (which already has them) it is a no-op.
 */
val Migration_28_29 = object : Migration(28, 29) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 28 to 29")
        db.beginTransaction()
        try {
            if (!hasColumn(db, "scheduled_jobs", "schedulePrecision")) {
                db.execSQL(
                    "ALTER TABLE `scheduled_jobs` ADD COLUMN `schedulePrecision` " +
                        "TEXT NOT NULL DEFAULT 'flexible'"
                )
            }

            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_ConversationEntity_assistant_id_is_pinned_update_at` " +
                    "ON `ConversationEntity` (`assistant_id`, `is_pinned`, `update_at`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_ConversationEntity_is_pinned_update_at` " +
                    "ON `ConversationEntity` (`is_pinned`, `update_at`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_MemoryEntity_assistant_id` " +
                    "ON `MemoryEntity` (`assistant_id`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_scheduled_jobs_enabled` " +
                    "ON `scheduled_jobs` (`enabled`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_scheduled_job_runs_jobId_startedAtMs` " +
                    "ON `scheduled_job_runs` (`jobId`, `startedAtMs`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_scheduled_job_runs_jobId_outcome` " +
                    "ON `scheduled_job_runs` (`jobId`, `outcome`)"
            )

            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: migrate from 28 to 29 success")
        } finally {
            db.endTransaction()
        }
    }

    private fun hasColumn(db: SupportSQLiteDatabase, table: String, column: String): Boolean {
        return try {
            db.query("PRAGMA table_info(`$table`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                var found = false
                if (nameIndex >= 0) {
                    while (!found && cursor.moveToNext()) {
                        found = cursor.getString(nameIndex) == column
                    }
                }
                found
            }
        } catch (t: Throwable) {
            Log.w(TAG, "hasColumn: failed to inspect $table.$column", t)
            false
        }
    }
}
