package com.amwolfstein.sistrum.sync

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.amwolfstein.sistrum.library.LocalAlbumRef
import com.amwolfstein.sistrum.library.LocalLibrarySnapshot
import com.amwolfstein.sistrum.library.LocalTrack
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PreservedLegacyTrackTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: SyncDatabase
    private lateinit var filesDir: File
    private lateinit var store: AndroidLibrarySyncStore

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(context, SyncDatabase::class.java).build()
        filesDir = File(context.cacheDir, "legacy-track-test-${System.nanoTime()}").apply { mkdirs() }
        store = AndroidLibrarySyncStore(database, filesDir)
    }

    @After
    fun cleanup() {
        database.close()
        filesDir.deleteRecursively()
    }

    @Test
    fun carriedLegacyTrackKeepsAssetsAndLyricsAcrossRepeatedScans() = runBlocking {
        val old = track("local:1", "album-old")
        val cover = File(filesDir, "artwork/old.jpg").apply { parentFile?.mkdirs(); writeText("old") }
        val oldAudio = LocalScanAudio("local:1", "/storage/emulated/0/Music/old.mp3", "old-hash", 100)
        store.writeLocalLibrary(
            LocalLibrarySnapshot(1, listOf(old)),
            mapOf(old.id to oldAudio),
            listOf(LocalScanArtwork("album-old", "artwork/old.jpg", "cover-hash", 3)),
        )
        store.saveProviderLyrics(old.id, "Lyrics", "test")
        val replacement = track("local:2", "album-new")
        repeat(2) {
            assertTrue(store.writeLocalLibrary(
                LocalLibrarySnapshot(2, listOf(replacement)),
                mapOf(replacement.id to LocalScanAudio(replacement.id, "/new.mp3", "new-hash", 100)),
                emptyList(),
                preservedTrackIds = setOf(old.id),
            ))
            assertEquals(setOf("local:1", "local:2"), database.syncDao().activeTrackEntities().map { it.trackId }.toSet())
            assertTrue(database.syncDao().activeAssets().any { it.assetId == "audio:local:1" })
            assertTrue(database.syncDao().activeAssets().any { it.assetId == "artwork:album-old" })
            assertTrue(cover.exists())
            assertEquals("Lyrics", store.providerLyrics(old.id).first())
        }
    }

    private fun track(id: String, artwork: String) = LocalTrack(
        id = id,
        title = id,
        artists = emptyList(),
        album = LocalAlbumRef(id = "album", title = "Album", artworkKey = artwork),
        artworkKey = artwork,
    )
}
