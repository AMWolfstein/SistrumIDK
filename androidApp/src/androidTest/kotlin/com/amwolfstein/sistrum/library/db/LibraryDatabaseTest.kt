package com.amwolfstein.sistrum.library.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LibraryDatabaseTest {
    private lateinit var context: Context
    private lateinit var database: LibraryDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .addCallback(LibraryDatabase.ratingCheck())
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test(expected = android.database.sqlite.SQLiteConstraintException::class)
    fun ratingOutsideRangeIsRejected() = runBlocking {
        database.libraryDao().upsertSource(
            LibrarySourceEntity("s", "content://s", "S", createdAt = 1L),
        )
        database.libraryDao().upsertItem(
            LibraryItemEntity(
                id = "i", documentKey = "d", fileUri = "content://d", sourceId = "s",
                title = "T", sortTitle = "T", rating = 6,
            ),
        )
    }

    @Test
    fun insertsAndReadsCoreEntities() = runBlocking {
        val dao = database.libraryDao()
        val now = 1_700_000_000_000L
        dao.upsertSource(
            LibrarySourceEntity(
                id = "source-1", treeUri = "content://tree/1", displayName = "Music",
                volumeId = "primary", createdAt = now,
            ),
        )
        dao.upsertAlbums(listOf(AlbumEntity("album-1", "Album", "Album", "album", 2020)))
        dao.upsertItems(
            listOf(
                LibraryItemEntity(
                    id = "item-1", documentKey = "saf:doc:1", fileUri = "content://doc/1",
                    sourceId = "source-1", albumId = "album-1", title = "Song",
                    sortTitle = "Song", albumTitle = "Album", rating = 4,
                    durationSeconds = 180, addedAt = now, updatedAt = now,
                ),
            ),
        )
        dao.upsertItemSources(listOf(ItemSourceEntity("item-1", "source-1", "content://doc/1")))
        dao.upsertPlaylist(PlaylistEntity("playlist-1", "Mix", createdAt = now, updatedAt = now))
        dao.upsertPlaylistTracks(listOf(PlaylistTrackEntity("playlist-1", "item-1", "a0")))
        dao.upsertLyrics(LyricsEntity("item-1", "[00:01.00]hi", "local-lrc", synced = true, fingerprint = "f"))
        dao.upsertAnalysis(TrackAnalysisEntity("item-1", loudnessLufs = -14.0, status = "COMPLETE"))

        assertEquals(1, dao.sources().size)
        assertEquals(1, dao.itemCount())
        assertEquals("Song", dao.itemByDocumentKey("saf:doc:1")?.title)
        assertEquals(4, dao.itemByDocumentKey("saf:doc:1")?.rating)
        assertEquals("album-1", dao.itemByDocumentKey("saf:doc:1")?.albumId)
        assertEquals(1, dao.playlistTracks("playlist-1").size)
        assertNotNull(dao.lyrics("item-1"))
        assertEquals(-14.0, dao.analysis("item-1")?.loudnessLufs)
    }
}

class LegacyMirrorImporterTest {
    private lateinit var context: Context
    private lateinit var database: LibraryDatabase
    private val legacyName = "library-sync-importer-test.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(legacyName)
        createLegacyFixture()
        database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .addCallback(LibraryDatabase.ratingCheck())
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(legacyName)
    }

    private fun createLegacyFixture() {
        val file = context.getDatabasePath(legacyName)
        file.parentFile?.mkdirs()
        val legacy = SQLiteDatabase.openOrCreateDatabase(file, null)
        legacy.execSQL("CREATE TABLE local_playlists (playlistId TEXT PRIMARY KEY, name TEXT NOT NULL)")
        legacy.execSQL("CREATE TABLE sync_tracks (trackId TEXT, title TEXT, artists TEXT, album TEXT, rawJson TEXT)")
        legacy.execSQL("CREATE TABLE sync_plans (planId TEXT, active INTEGER)")
        legacy.execSQL("CREATE TABLE sync_assets (planId TEXT, assetId TEXT, relativePath TEXT)")
        legacy.execSQL("CREATE TABLE daily_track_listening_stats (localDate TEXT, trackId TEXT, listenedSeconds INTEGER, playCount INTEGER)")
        legacy.execSQL("CREATE TABLE playlist_mutations (mutationId TEXT, playlistId TEXT, operation TEXT, updatedAt INTEGER, payloadJson TEXT)")
        legacy.execSQL("CREATE TABLE listening_sessions (id TEXT, sourceDeviceId TEXT, trackId TEXT, startedAt INTEGER, endedAt INTEGER, listenedSeconds INTEGER, qualifiedPlay INTEGER)")
        legacy.execSQL("CREATE TABLE playback_attempts (id TEXT, sourceDeviceId TEXT, trackId TEXT, startedAt INTEGER, endedAt INTEGER, startPositionMs INTEGER, listenedSeconds INTEGER, endReason TEXT)")
        legacy.execSQL("CREATE TABLE provider_lyrics (trackId TEXT, content TEXT, source TEXT)")
        legacy.execSQL("INSERT INTO local_playlists VALUES ('p1','Road trip')")
        legacy.execSQL("INSERT INTO sync_plans VALUES ('plan-1',1)")
        legacy.execSQL("INSERT INTO sync_assets VALUES ('plan-1','audio:t1','Music/t1.flac')")
        legacy.execSQL("INSERT INTO sync_tracks VALUES ('t1','Song','Artist','Album','{\"is_favorite\":true,\"duration\":180}')")
        legacy.execSQL("INSERT INTO sync_tracks VALUES ('t2','Other','Artist','Album','{\"is_favorite\":false,\"duration\":90}')")
        legacy.execSQL("INSERT INTO daily_track_listening_stats VALUES ('2026-01-01','t3',10,1)")
        legacy.execSQL("INSERT INTO playlist_mutations VALUES ('m1','p1','ADD_TRACK',5,'{\"track_id\":\"t1\"}')")
        legacy.execSQL("INSERT INTO listening_sessions VALUES ('s1','desktop','t1',1,2,3,1)")
        legacy.execSQL("INSERT INTO playback_attempts VALUES ('a1','desktop','t1',1,2,0,3,'completed')")
        legacy.execSQL("INSERT INTO provider_lyrics VALUES ('t1','[00:01.00]hi','lrclib-synced')")
        legacy.close()
    }

    @Test
    fun importsOwnedDataOnceAndKeepsUnresolvedRefs() = runBlocking {
        val importer = LegacyMirrorImporter(context, database, legacyName)
        val first = importer.importIfNeeded()
        assertTrue(first.imported)
        assertEquals(1, first.playlists)
        assertEquals(2, first.legacyTracks)
        assertEquals(2, first.unresolved)
        assertEquals(1, database.libraryDao().playlists().size)
        assertEquals(1, database.libraryDao().listeningSessionCount())
        assertNotNull(database.libraryDao().legacyRef("t1"))
        assertEquals("Music/t1.flac", database.libraryDao().legacyRef("t1")?.legacyPath)
        assertEquals(180L, database.libraryDao().legacyRef("t1")?.durationSeconds)
        assertNotNull(database.libraryDao().legacyRef("t3"))
        assertEquals(true, database.libraryDao().legacyRef("t1")?.legacyFavorite)
        assertTrue(database.libraryDao().legacyRef("t1")?.playlistRefsJson?.contains("p1") == true)
        assertNotNull(database.libraryDao().legacyLyrics("t1"))

        val second = importer.importIfNeeded()
        assertFalse(second.imported)
        assertEquals(1, database.libraryDao().playlists().size)
        assertEquals(1, database.libraryDao().listeningSessionCount())
    }
}
