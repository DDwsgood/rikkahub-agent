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
 * The fork's v28 schema (identity hash f16c87ce, no indices) and the upstream's v28 schema
 * (indices added) both carried version 28 with DIFFERENT schemas. The merged build bumps to
 * v29 so existing fork users get a real migration: the auto-generated diff between the
 * fork's 28.json and the merged v29 adds the missing query indices.
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
        // Create a database at v28 using the fork's ORIGINAL schema (no indices).
        helper.createDatabase(testDb, 28).use { db ->
            // No data needed — the migration is index-only.
        }

        // Run the auto-migration to v29 and validate against the merged schema.
        val db = helper.runMigrationsAndValidate(testDb, 29, true)

        // The 5 query indices from the upstream merge must exist.
        assertIndexExists(db, "index_ConversationEntity_assistant_id_is_pinned_update_at")
        assertIndexExists(db, "index_ConversationEntity_is_pinned_update_at")
        assertIndexExists(db, "index_MemoryEntity_assistant_id")
        assertIndexExists(db, "index_scheduled_jobs_enabled")
        assertIndexExists(db, "index_scheduled_job_runs_jobId_startedAtMs")
        assertIndexExists(db, "index_scheduled_job_runs_jobId_outcome")

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