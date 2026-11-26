package com.amwolfstein.sistrum.sync

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalAnalysisMigrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun migrationKeepsExistingPlaylistAndCreatesAnalysisTable() = runBlocking {
        val name = "analysis-migration-${UUID.randomUUID()}.db"
        try {
            val initial = Room.databaseBuilder(context, SyncDatabase::class.java, name).build()
            initial.syncDao().insertLocalPlaylist(LocalPlaylistEntity("p", "Playlist", "mutation", "ready"))
            initial.close()
            val raw = SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE)
            raw.execSQL("DROP TABLE local_track_analysis")
            raw.execSQL("DROP INDEX index_sync_tracks_planId_syncOrder")
            raw.version = 13
            raw.close()

            val migrated = Room.databaseBuilder(context, SyncDatabase::class.java, name)
                .addMigrations(SyncDatabase.Migration13To14)
                .build()
            try {
                assertEquals("Playlist", migrated.syncDao().observeLocalPlaylists().first().single().name)
                assertEquals(emptyList<Any>(), migrated.syncDao().activeLocalAnalyses())
            } finally {
                migrated.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
