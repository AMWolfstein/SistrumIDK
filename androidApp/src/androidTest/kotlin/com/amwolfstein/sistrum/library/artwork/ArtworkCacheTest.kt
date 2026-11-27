package com.amwolfstein.sistrum.library.artwork

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.amwolfstein.sistrum.library.db.AlbumEntity
import com.amwolfstein.sistrum.library.db.LibraryDatabase
import com.amwolfstein.sistrum.library.db.LibraryItemEntity
import com.amwolfstein.sistrum.library.db.LibrarySourceEntity
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ArtworkCacheTest {
    private lateinit var context: Context
    private lateinit var database: LibraryDatabase
    private lateinit var cache: ArtworkCache

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        cache = ArtworkCache(context, database.libraryDao())
    }

    @After
    fun tearDown() {
        File(context.filesDir, "artwork").deleteRecursively()
        database.close()
    }

    @Test
    fun storesOriginalAndBothVariants() = runBlocking {
        val asset = cache.store(jpeg(1200, 600), kind = "EMBEDDED", origin = "TAG")
        assertNotNull(asset)
        val key = asset!!.key
        assertEquals(1200, asset.width)
        assertEquals(600, asset.height)
        assertEquals("image/jpeg", asset.mime)
        assertEquals("$key.jpg", asset.path.substringAfterLast('/'))

        val original = cache.file(key, ArtworkKeys.VARIANT_ORIGINAL)
        val medium = cache.file(key, ArtworkKeys.VARIANT_MEDIUM)
        val small = cache.file(key, ArtworkKeys.VARIANT_SMALL)
        assertTrue(original!!.isFile)
        assertTrue(medium!!.isFile)
        assertTrue(small!!.isFile)
        val mediumBitmap = cache.load(key, ArtworkKeys.VARIANT_MEDIUM)!!
        assertEquals(500, maxOf(mediumBitmap.width, mediumBitmap.height))
        val smallBitmap = cache.load(key, ArtworkKeys.VARIANT_SMALL)!!
        assertEquals(ArtworkKeys.SMALL_SIZE, maxOf(smallBitmap.width, smallBitmap.height))
    }

    @Test
    fun sameBytesReuseTheSameKeyAndTouchUsage() = runBlocking {
        val bytes = jpeg(320, 320)
        val first = cache.store(bytes, kind = "EMBEDDED", origin = "TAG")!!
        val second = cache.store(bytes, kind = "DEEZER", origin = "DEEZER")!!
        assertEquals(first.key, second.key)
        assertEquals(1, cache.assetCount())
    }

    @Test
    fun webpIsAcceptedAndInvalidDataIsRejected() = runBlocking {
        val webp = encoded(Bitmap.CompressFormat.WEBP_LOSSLESS, 200, 200)
        val asset = cache.store(webp, kind = "FOLDER", origin = "SAF")
        assertNotNull(asset)
        assertEquals("image/webp", asset!!.mime)
        assertTrue(cache.file(asset.key)!!.isFile)

        assertNull(cache.store("<html>nope".toByteArray(), kind = "EMBEDDED", origin = "TAG"))
        assertEquals(1, cache.assetCount())
    }

    @Test
    fun cleanupRemovesUnreferencedAssetsButKeepsUsedOnes() = runBlocking {
        val used = cache.store(jpeg(128, 128), kind = "EMBEDDED", origin = "TAG")!!
        val unused = cache.store(jpeg(130, 130), kind = "EMBEDDED", origin = "TAG")!!
        database.libraryDao().upsertSource(LibrarySourceEntity("s", "content://s", "S", createdAt = 1L))
        database.libraryDao().upsertAlbums(listOf(AlbumEntity("a", "A", "A", "a")))
        database.libraryDao().upsertItem(
            LibraryItemEntity(
                id = "i", documentKey = "d", fileUri = "content://d", sourceId = "s",
                title = "T", sortTitle = "T", artworkKey = used.key,
            ),
        )
        val removed = cache.clearUnreferenced()
        assertTrue(removed >= 1)
        assertNotNull(cache.file(used.key))
        assertNull(cache.file(unused.key))
        assertTrue(File(context.filesDir, "artwork").walkTopDown().any { it.isFile })
    }

    private fun jpeg(width: Int, height: Int): ByteArray =
        encoded(Bitmap.CompressFormat.JPEG, width, height)

    private fun encoded(format: Bitmap.CompressFormat, width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, (0xFF000000.toInt()) or ((x * 7 + y * 13) and 0xFFFFFF))
            }
        }
        val output = ByteArrayOutputStream()
        bitmap.compress(format, 90, output)
        bitmap.recycle()
        return output.toByteArray()
    }
}
