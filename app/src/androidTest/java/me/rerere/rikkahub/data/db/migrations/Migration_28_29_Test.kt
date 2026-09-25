package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the fork↔upstream 2.4.5 merge migration 28→29.
 *
 * The fork's v28 schema (schedulePrecision column, no query indices) and the upstream's v28
 * schema (query indices, no schedulePrecision) both carried version 28 with DIFFERENT
 * schemas. The merged build bumps to v29; Migration_28_29 heals both sources: indices for
 * fork v28 and the missing schedulePrecision column for upstream v28.
 */
@RunWith(AndroidJUnit4::class)
class Migration_28_29_Test {

    private val testDb = "migration-28-29-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun existingForkV28GetsQueryIndicesAfterMigration() {
        // Create a database at v28 using the fork's ORIGINAL schema (schedulePrecision
        // column present, no query indices).
        helper.createDatabase(testDb, 28).use { db ->
            // No data needed — the migration is index-only for this source.
        }

        val db = helper.runMigrationsAndValidate(testDb, 29, true, Migration_28_29)

        // The 6 query indices from the upstream merge must exist.
        assertIndexExists(db, "index_ConversationEntity_assistant_id_is_pinned_update_at")
        assertIndexExists(db, "index_ConversationEntity_is_pinned_update_at")
        assertIndexExists(db, "index_MemoryEntity_assistant_id")
        assertIndexExists(db, "index_scheduled_jobs_enabled")
        assertIndexExists(db, "index_scheduled_job_runs_jobId_startedAtMs")
        assertIndexExists(db, "index_scheduled_job_runs_jobId_outcome")

        db.close()
    }

    /**
     * Upstream ExTV v28 fixture: carries the six query indices but its `scheduled_jobs`
     * table is MISSING the fork's `schedulePrecision` column. The hand-written migration
     * must add the column (with the entity default) before Room validates the schema.
     */
    @Test
    fun upstreamV28WithoutSchedulePrecisionGetsColumnAndValidates() {
        helper.createDatabase(testDb, 28).use { db ->
            // Replace the fork-shaped scheduled_jobs table with the upstream-v28 shape
            // (same columns minus schedulePrecision), then create the upstream indices.
            db.execSQL("DROP TABLE `scheduled_jobs`")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `scheduled_jobs` (" +
                    "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `prompt` TEXT, " +
                    "`assistantId` TEXT NOT NULL, `scheduleType` TEXT NOT NULL, " +
                    "`atUnixMs` INTEGER, `intervalSeconds` INTEGER, `enabled` INTEGER NOT NULL, " +
                    "`createdAtMs` INTEGER NOT NULL, `lastRunAtMs` INTEGER, " +
                    "`nextRunAtMs` INTEGER, `mode` TEXT NOT NULL DEFAULT 'llm', " +
                    "`actionsJson` TEXT, `cronExpression` TEXT, `timezone` TEXT, " +
                    "`startAtUnixMs` INTEGER, `endAtUnixMs` INTEGER, `maxRuns` INTEGER, " +
                    "`runsSoFar` INTEGER NOT NULL DEFAULT 0, " +
                    "`catchup` TEXT NOT NULL DEFAULT 'fire_once', " +
                    "`description` TEXT, `tags` TEXT, PRIMARY KEY(`id`))"
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
        }

        val db = helper.runMigrationsAndValidate(testDb, 29, true, Migration_28_29)

        // The migration must add the missing column with the entity default.
        val cursor = db.query("PRAGMA table_info(`scheduled_jobs`)")
        var hasSchedulePrecision = false
        while (cursor.moveToNext()) {
            if (cursor.getString(cursor.getColumnIndex("name")) == "schedulePrecision") {
                hasSchedulePrecision = true
                break
            }
        }
        cursor.close()
        assertTrue("schedulePrecision should be added by the migration", hasSchedulePrecision)

        db.close()
    }

    private fun assertIndexExists(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        indexName: String,
    ) {
        val found = db.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND name = ?",
            arrayOf(indexName),
        ).use { cursor ->
            cursor.moveToFirst()
        }
        assertTrue("index $indexName should exist after migration", found)
    }
}