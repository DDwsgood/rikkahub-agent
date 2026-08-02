package me.rerere.rikkahub.data.db.migrations

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_27_28_Test {

    private val testDb = "migration-27-28-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun existingScheduledJobsDefaultToFlexiblePrecision() {
        helper.createDatabase(testDb, 27).use { db ->
            db.insert(
                "scheduled_jobs",
                SQLiteDatabase.CONFLICT_NONE,
                ContentValues().apply {
                    put("id", "job-1")
                    put("name", "existing job")
                    put("assistantId", "assistant-1")
                    put("scheduleType", "cron")
                    put("enabled", 1)
                    put("createdAtMs", 1_000L)
                    put("mode", "llm")
                    put("runsSoFar", 0)
                    put("catchup", "fire_once")
                },
            )
        }

        val db = helper.runMigrationsAndValidate(testDb, 28, true)
        db.query("SELECT schedulePrecision FROM scheduled_jobs WHERE id = 'job-1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("flexible", cursor.getString(0))
        }
        db.close()
    }
}
