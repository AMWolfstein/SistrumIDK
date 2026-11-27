package com.amwolfstein.sistrum.library.artwork

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.amwolfstein.sistrum.library.db.ArtistEntity
import com.amwolfstein.sistrum.library.db.LibraryDatabase
import com.amwolfstein.sistrum.library.db.LibraryItemEntity
import com.amwolfstein.sistrum.library.db.LibrarySourceEntity
import com.amwolfstein.sistrum.library.scan.FolderCover
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ArtworkLibraryServiceTest {
    private lateinit var context: Context
    private lateinit var database: LibraryDatabase
    private var deezerRequests = 0
    private var onlineEnabled = false

    private val imageBytes: ByteArray by lazy { jpeg(600, 600) }
    private val deezerJson =
        """{"data":[{"id":9,"name":"Portishead","picture_xl":"https://img/xl/portishead.jpg"}]}"""

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            database.libraryDao().upsertSource(
                LibrarySourceEntity("s", "content://tree/s", "Music", createdAt = 1L),
            )
            database.libraryDao().upsertArtists(
                listOf(
                    ArtistEntity(
                        id = "artist_portishead",
                        name = "Portishead",
                        sortName = "Portishead",
                        normalizationKey = "portishead",
                    ),
                ),
            )
        }
    }

    @After
    fun tearDown() {
        File(context.filesDir, "artwork").deleteRecursively()
        database.close()
    }

    private fun service(online: Boolean = onlineEnabled): ArtworkLibraryService {
        onlineEnabled = online
        val deezer = DeezerArtworkClient { url ->
            deezerRequests += 1
            if (!online) null else if (url.startsWith("https://api.deezer.com")) {
                deezerJson.toByteArray()
            } else {
                imageBytes
            }
        }
        return ArtworkLibraryService(
            context = context,
            database = database,
            deezer = deezer,
            settingsProvider = { ArtworkSettings(deezerEnabled = online) },
        )
    }

    @Test
    fun disabledToggleNeverCallsDeezerAndLeavesPlaceholder() = runBlocking {
        val key = service(online = false).resolveArtist("artist_portishead")
        assertNull(key)
        assertEquals(0, deezerRequests)
        assertNull(database.libraryDao().artist("artist_portishead")?.artworkKey)
    }

    @Test
    fun toggleOffReturnsExistingDeezerArtWithoutRequesting() = runBlocking {
        val asset = ArtworkCache(context, database.libraryDao()).store(imageBytes, "ARTIST", "DEEZER")!!
        database.libraryDao().updateArtistArtwork("artist_portishead", asset.key, "DEEZER", 1L)

        val key = service(online = false).resolveArtist("artist_portishead")
        assertEquals(asset.key, key)
        assertEquals(0, deezerRequests)
        assertEquals("DEEZER", database.libraryDao().artist("artist_portishead")?.artworkSource)
    }

    @Test
    fun offlineFailureKeepsDurableDeezerImage() = runBlocking {
        val asset = ArtworkCache(context, database.libraryDao()).store(imageBytes, "ARTIST", "DEEZER")!!
        database.libraryDao().updateArtistArtwork("artist_portishead", asset.key, "DEEZER", 1L)
        val failing = ArtworkLibraryService(
            context = context,
            database = database,
            deezer = DeezerArtworkClient { null },
            settingsProvider = { ArtworkSettings(deezerEnabled = true) },
        )

        assertEquals(asset.key, failing.resolveArtist("artist_portishead"))
        assertEquals("DEEZER", database.libraryDao().artist("artist_portishead")?.artworkSource)
    }

    @Test
    fun deezerImportWinsAndFallbackNeverReplacesIt() = runBlocking {
        val fallback = ArtworkCache(context, database.libraryDao()).store(jpeg(120, 120), "EMBEDDED", "TAG")!!
        database.libraryDao().upsertItem(
            LibraryItemEntity(
                id = "track_1",
                documentKey = "doc_1",
                fileUri = "content://track/1",
                sourceId = "s",
                title = "Roads",
                sortTitle = "Roads",
                releaseDate = "1994-08-22",
                artworkKey = fallback.key,
            ),
        )
        database.libraryDao().upsertTrackArtists(
            listOf(com.amwolfstein.sistrum.library.db.TrackArtistEntity("track_1", "artist_portishead", 0)),
        )

        val service = service(online = true)
        val imported = service.resolveArtist("artist_portishead")
        assertNotNull(imported)
        assertNotEquals(fallback.key, imported)
        assertEquals("DEEZER", database.libraryDao().artist("artist_portishead")?.artworkSource)
        assertTrue(deezerRequests >= 2)

        // A later library recompute must not replace the durable import with track artwork.
        service.refreshArtistFallbacks(limit = 10)
        assertEquals(imported, database.libraryDao().artist("artist_portishead")?.artworkKey)
        assertEquals("DEEZER", database.libraryDao().artist("artist_portishead")?.artworkSource)
    }

    @Test
    fun folderCoverAppliesOnlyToThatFolder() = runBlocking {
        database.libraryDao().upsertItem(
            LibraryItemEntity(
                id = "track_a", documentKey = "doc_a", fileUri = "content://a/1.mp3", sourceId = "s",
                displayPath = "Album A/1.mp3", title = "One", sortTitle = "One",
            ),
        )
        database.libraryDao().upsertItem(
            LibraryItemEntity(
                id = "track_b", documentKey = "doc_b", fileUri = "content://b/1.mp3", sourceId = "s",
                displayPath = "Album B/1.mp3", title = "Two", sortTitle = "Two",
            ),
        )
        val coverFile = File(context.cacheDir, "folder-cover.jpg")
        coverFile.writeBytes(imageBytes)

        val applied = service().applyFolderCovers(
            "s",
            listOf(FolderCover("Album A", "cover", Uri.fromFile(coverFile).toString(), "cover.jpg")),
        )

        assertEquals(1, applied)
        assertNotNull(database.libraryDao().itemById("track_a")?.artworkKey)
        assertNull(database.libraryDao().itemById("track_b")?.artworkKey)
        coverFile.delete()
        Unit
    }

    private fun jpeg(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, (0xFF000000.toInt()) or ((x * 11 + y * 5) and 0xFFFFFF))
            }
        }
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
        bitmap.recycle()
        return output.toByteArray()
    }
}
