package com.amwolfstein.sistrum.library.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LibraryDatabase::class.java,
    )

    @Test
    fun migrate1To2KeepsScanRowsAndAddsDefaults() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                "INSERT INTO library_sources (id, treeUri, displayName, isRemovable, enabled, available, createdAt) " +
                    "VALUES ('s1', 'content://authority/tree/x', 'Music', 0, 1, 1, 1)",
            )
            execSQL(
                "INSERT INTO scan_runs (id, sourceId, treeUri, mode, generation, state, startedAt, totalFiles, scannedFiles, errorCount, cancelled) " +
                    "VALUES ('r1', 's1', 'content://authority/tree/x', 'FULL', 1, 'COMPLETED', 1, 1, 1, 0, 0)",
            )
            execSQL(
                "INSERT INTO scan_entries (runId, documentKey, fileUri, modTime, size, state) " +
                    "VALUES ('r1', 'a:b', 'content://authority/document/b', 10, 20, 'SEEN')",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 2, true, LibraryDatabase.MIGRATION_1_2)
        migrated.query("SELECT documentKey, relativePath, name, changed, cueKey FROM scan_entries").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("a:b", cursor.getString(0))
            assertEquals("", cursor.getString(1))
            assertEquals("", cursor.getString(2))
            assertEquals(0, cursor.getInt(3))
            assertEquals(null, cursor.getString(4))
        }
    }

    private companion object {
        const val TEST_DB = "library-migration-test.db"
    }
}
