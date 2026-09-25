package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the 29→30 auto-migration: the workspaces table gains the `sdcard_mode`
 * column (shared-storage mount mode) with the entity default 'NONE'.
 *
 * This is a pure additive column migration declared via @AutoMigration(29, 30) —
 * the entity carries @ColumnInfo(defaultValue = "NONE") so Room can auto-migrate
 * without a hand-written Migration class. The pre-migration row must keep its
 * data and read back as sdcard_mode = NONE.
 */
@RunWith(AndroidJUnit4::class)
class Migration_29_30_Test {

    private val testDb = "migration-29-30-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun existingWorkspaceKeepsDataAndGainsSdcardModeDefault() {
        helper.createDatabase(testDb, 29).use { db ->
            // A v29 workspace row exactly as the fork writes it (tool_approvals default included)
            db.execSQL(
                "INSERT INTO `workspaces` (`id`, `name`, `root`, `shell_status`, " +
                    "`created_at`, `updated_at`, `last_access_at`, `tool_approvals`) VALUES " +
                    "('ws-1', 'Main', 'ws-1', 'READY', 1000, 2000, NULL, '{}')"
            )
        }

        // Empty vararg: the 29→30 transition is a Room AutoMigration resolved from the
        // @Database annotation, so there is no hand-written Migration instance to pass.
        val db = helper.runMigrationsAndValidate(testDb, 30, true)

        // The column must exist with the entity default, and the existing row reads back NONE.
        val cursor = db.query("SELECT `sdcard_mode` FROM `workspaces` WHERE `id` = 'ws-1'")
        assertTrue("workspace row must survive the migration", cursor.moveToFirst())
        assertEquals("NONE", cursor.getString(0))
        cursor.close()

        // Rows inserted after the migration also carry the default.
        db.execSQL(
            "INSERT INTO `workspaces` (`id`, `name`, `root`, `shell_status`, " +
                "`created_at`, `updated_at`) VALUES ('ws-2', 'Second', 'ws-2', 'DISABLED', 3000, 3000)"
        )
        val fresh = db.query("SELECT `sdcard_mode` FROM `workspaces` WHERE `id` = 'ws-2'")
        assertTrue(fresh.moveToFirst())
        assertEquals("NONE", fresh.getString(0))
        fresh.close()

        // An explicit mode round-trips through the column.
        db.execSQL("UPDATE `workspaces` SET `sdcard_mode` = 'READ_ONLY' WHERE `id` = 'ws-2'")
        val updated = db.query("SELECT `sdcard_mode` FROM `workspaces` WHERE `id` = 'ws-2'")
        assertTrue(updated.moveToFirst())
        assertEquals("READ_ONLY", updated.getString(0))
        updated.close()

        db.close()
    }
}