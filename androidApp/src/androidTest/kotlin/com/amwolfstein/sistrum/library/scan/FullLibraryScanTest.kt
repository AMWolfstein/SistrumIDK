package com.amwolfstein.sistrum.library.scan

import androidx.test.platform.app.InstrumentationRegistry
import com.amwolfstein.sistrum.library.LibraryRepository
import com.amwolfstein.sistrum.library.db.LibraryDatabase
import com.amwolfstein.sistrum.library.db.SearchIndexer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Full traversal of the owner-selected SAF root through the production scanner and
 * publisher, without the foreground service (its dataSync budget is OS-limited).
 * Skipped when no source is selected on the device.
 */
class FullLibraryScanTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun scansAndPublishesTheWholeSelectedRoot() = runBlocking {
        val database = LibraryDatabase.get(context)
        val dao = database.libraryDao()
        val source = dao.sources().firstOrNull { it.enabled && it.available }
        assumeTrue("no available local source on this device", source != null)
        val repository = ScanRunRepository(dao)
        val run = repository.startOrResume(source!!)
        val request = ScanRequest(
            runId = run.id,
            sourceId = source.id,
            treeUri = source.treeUri,
            rootDocumentId = android.provider.DocumentsContract.getTreeDocumentId(
                android.net.Uri.parse(source.treeUri),
            ),
            priorVersions = repository.priorVersions(source.id),
            alreadyScanned = 0,
            exclusions = repository.exclusions(source.id),
        )
        val result = SafScanner(AndroidScanTreeReader(context)).scan(request, repository.checkpoint(run))
        repository.finish(run, result, result.errors.size, result.entries.size)
        val published = LibraryRepository(context, database).publish(source, run.id, result.complete)
        SearchIndexer(database).rebuild()

        val items = dao.allItems()
        val withTags = items.count { it.otherMetadataJson != "{}" }
        val withFingerprint = items.count { it.audioFingerprint?.startsWith("pcm-sha256-v1:") == true }
        println(
            "FullLibraryScan: scanned=${result.entries.size} published=${published.added + published.updated} " +
                "items=${items.size} tags=$withTags fingerprints=$withFingerprint " +
                "failures=${published.metadataFailures} errors=${result.errors.size} complete=${result.complete}",
        )
        assertTrue("scanner returned no entries", result.entries.isNotEmpty())
        assertTrue("no items were published", items.isNotEmpty())
        assertTrue("most items still have empty metadata", withTags * 10 >= items.size * 9)
        assertTrue("fingerprints were not stored", withFingerprint == items.size)
        assertTrue("albums were not derived", dao.allAlbums().isNotEmpty())
        assertTrue("artists were not derived", dao.allArtists().isNotEmpty())
        assertTrue("search index was not rebuilt", dao.searchIndexCount() >= items.size)
    }
}
