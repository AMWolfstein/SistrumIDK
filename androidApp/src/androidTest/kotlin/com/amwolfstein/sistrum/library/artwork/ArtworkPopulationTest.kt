package com.amwolfstein.sistrum.library.artwork

import androidx.test.core.app.ApplicationProvider
import com.amwolfstein.sistrum.library.db.LibraryDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Post-scan embedded-artwork population: a bounded pass must import every readable picture it
 * walks past and leave a resolvable cache file behind, without needing the Online Artwork screen.
 */
class ArtworkPopulationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun boundedPassAdvancesAcrossTheLibraryAndStoresRealFiles() = runBlocking {
        val database = LibraryDatabase.get(context)
        val dao = database.libraryDao()
        val before = dao.itemsMissingArtwork(10_000).size
        val service = ArtworkLibraryService(context)

        val imported = service.populateAllMissingArtwork(budget = 200, page = 32)
        val after = dao.itemsMissingArtwork(10_000).size

        println("ARTWORK population before=$before imported=$imported after=$after")
        assertEquals("every imported item must leave the missing set", before - imported, after)

        // Every item that gained artwork must resolve to a non-empty cache file.
        var checked = 0
        dao.allItems().filter { it.artworkKey != null }.take(20).forEach { item ->
            val cache = ArtworkCache(context, dao)
            val file = cache.file(item.artworkKey!!, ArtworkKeys.VARIANT_ORIGINAL)
            assertTrue("missing cache file for ${item.artworkKey}", file != null && file.length() > 0)
            checked += 1
        }
        assertTrue("no artwork to verify", checked > 0)
        // A second pass must not loop on pictures-less files.
        val second = service.populateAllMissingArtwork(budget = 200, page = 32)
        assertTrue("second pass imported $second", second <= imported)
    }
}
