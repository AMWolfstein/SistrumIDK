package com.amwolfstein.sistrum.library.artwork

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.amwolfstein.sistrum.library.db.LibraryDatabase
import com.amwolfstein.sistrum.library.db.LibraryItemEntity
import com.amwolfstein.sistrum.library.db.LibrarySourceEntity
import com.amwolfstein.sistrum.library.scan.FolderCover
import com.amwolfstein.sistrum.library.tags.TagLibBridge
import com.amwolfstein.sistrum.library.tags.TagMetadataJson
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Per-format artwork matrix: the scanner reads embedded pictures at scan time and folder covers
 * are applied to the items below them. Deezer is artist-only and is not part of this path.
 */
class ArtworkMatrixTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var database: LibraryDatabase

    private val formats = listOf(
        "mp3" to "analysis/tone.mp3",
        "flac" to "analysis/tone-1k-5s-48k-24bit.flac",
        "m4a" to "analysis/tone.m4a",
        "ogg" to "analysis/tone.ogg",
        "opus" to "analysis/tone.opus",
        "wv" to "analysis/tone.wv",
        "wav" to "analysis/tone-440-3s.wav",
        "aiff" to "analysis/tone.aiff",
    )

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = database.close()

    private fun coverBytes(): ByteArray =
        instrumentation.context.assets.open("artwork/cover.png").use { it.readBytes() }

    @Test
    fun embeddedArtworkRoundTripsForEveryFormat() {
        val cover = coverBytes()
        formats.forEach { (format, asset) ->
            val file = File(context.cacheDir, "art-matrix-$format.$format")
            instrumentation.context.assets.open(asset).use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            var wrote = false
            var readBack = 0
            var hasFlag = false
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).use { descriptor ->
                wrote = TagLibBridge.nativeWriteArtwork(descriptor.fd, cover, "image/png", "Cover")
            }
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                val metadata = TagMetadataJson.parse(TagLibBridge.nativeReadMetadata(descriptor.fd))
                hasFlag = metadata.hasArtwork
                readBack = TagLibBridge.nativeReadArtwork(descriptor.fd)?.size ?: 0
            }
            val picture = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                TagLibBridge.nativeReadArtwork(descriptor.fd)
            }
            val decoded = picture?.let { bytes ->
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                bitmap?.let { it.recycle(); true } ?: false
            } ?: false
            file.delete()
            println("ARTWORK_MATRIX $format write=$wrote has=$hasFlag bytes=$readBack decoded=$decoded")
            if (wrote) {
                assertTrue("$format: hasArtwork flag not set", hasFlag)
                assertTrue("$format: no picture bytes", readBack > 0)
                assertTrue("$format: picture bytes do not decode", decoded)
            }
        }
    }

    @Test
    fun folderCoverIsAppliedToTheItemsBelowIt() = runBlocking {
        val dao = database.libraryDao()
        dao.upsertSource(LibrarySourceEntity("s", "content://authority/tree/s", "Music", createdAt = 1L))
        dao.upsertItem(
            LibraryItemEntity(
                id = "track-1", documentKey = "doc-1", fileUri = "content://authority/document/1",
                sourceId = "s", displayPath = "Album/track.flac", title = "Track",
                sortTitle = "Track", format = "flac", available = true,
            ),
        )
        val cover = File(context.cacheDir, "folder-cover.png")
        cover.writeBytes(coverBytes())
        val folder = FolderCover(
            folderPath = "Album",
            documentKey = "authority:cover",
            fileUri = Uri.fromFile(cover).toString(),
            name = "cover.png",
        )
        val service = ArtworkLibraryService(context, database)
        val applied = service.applyFolderCovers("s", listOf(folder))
        cover.delete()

        assertEquals(1, applied)
        val key = dao.itemById("track-1")?.artworkKey
        assertNotNull("folder cover did not reach the item", key)
        val file = ArtworkCache(context, dao).file(key!!, ArtworkKeys.VARIANT_ORIGINAL)
        assertTrue("folder cover file missing", file != null && file.length() > 0)
    }

    @Test
    fun embeddedArtworkPopulatesDuringThePostScanPass() = runBlocking {
        val dao = database.libraryDao()
        dao.upsertSource(LibrarySourceEntity("s", "content://authority/tree/s", "Music", createdAt = 1L))
        val tags = File(context.cacheDir, "scan-art.mp3")
        instrumentation.context.assets.open("analysis/tone.mp3").use { input ->
            tags.outputStream().use { output -> input.copyTo(output) }
        }
        ParcelFileDescriptor.open(tags, ParcelFileDescriptor.MODE_READ_WRITE).use { descriptor ->
            TagLibBridge.nativeWriteArtwork(descriptor.fd, coverBytes(), "image/png", "Cover")
        }
        dao.upsertItem(
            LibraryItemEntity(
                id = "track-1", documentKey = "doc-1", fileUri = Uri.fromFile(tags).toString(),
                sourceId = "s", displayPath = "Album/track.mp3", title = "Track",
                sortTitle = "Track", format = "mp3", available = true,
            ),
        )
        val imported = ArtworkLibraryService(context, database).populateAllMissingArtwork(budget = 10, page = 10)
        tags.delete()
        assertEquals(1, imported)
        val key = dao.itemById("track-1")?.artworkKey
        assertNotNull("scan-time artwork population did not import the embedded picture", key)
        val file = ArtworkCache(context, dao).file(key!!, ArtworkKeys.VARIANT_ORIGINAL)
        assertTrue(file != null && file.length() > 0)
    }
}
