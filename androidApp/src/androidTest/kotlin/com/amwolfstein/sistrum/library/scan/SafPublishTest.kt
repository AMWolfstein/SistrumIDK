package com.amwolfstein.sistrum.library.scan

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import com.amwolfstein.sistrum.library.LibraryRepository
import com.amwolfstein.sistrum.library.db.LibraryDatabase
import com.amwolfstein.sistrum.library.db.ScanEntryEntity
import com.amwolfstein.sistrum.library.db.SearchIndexer
import com.amwolfstein.sistrum.library.tags.MetadataExtractor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Publishes three real provider-backed files through the production scan path and
 * asserts the catalog row, the decoded-PCM identity and the FTS entry. Read-only
 * for audio; only the app's own library database changes.
 */
class SafPublishTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun publishesMetadataFingerprintAndSearchEntry() = runBlocking {
        val database = LibraryDatabase.get(context)
        val dao = database.libraryDao()
        val sourceId = "source_device_verification"
        val tree = "content://device.verification/tree/music"
        val source = com.amwolfstein.sistrum.library.db.LibrarySourceEntity(
            id = sourceId,
            treeUri = tree,
            displayName = "Verification",
            volumeId = "verification",
            enabled = true,
            available = true,
        )
        dao.upsertSource(source)

        val repository = LibraryRepository(context, database)
        val extractor = MetadataExtractor(context)
        val fixtures = listOf("fixture.mp3", "fixture.flac", "fixture.m4a")
        fixtures.forEach { name ->
            val target = java.io.File(context.cacheDir, "publish-$name")
            InstrumentationRegistry.getInstrumentation().context.assets.open("taglib/$name").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            val uri = Uri.fromFile(target)
            val extracted = extractor.read(uri).getOrThrow()
            val fingerprint = context.contentResolver.openFileDescriptor(uri, "r")!!.use {
                com.amwolfstein.sistrum.library.tags.TagLibBridge.nativeAudioFingerprint(it.fd, 0, -1)
            }
            assertTrue("empty tag payload for $name", extracted.metadata.durationMs > 0)
            assertTrue(
                "no PCM fingerprint for $name",
                fingerprint?.startsWith("pcm-sha256-v1:") == true,
            )
            val documentKey = "verification:$name"
            repository.publishEntry(
                source,
                ScanEntryEntity(
                    runId = "device-verification",
                    documentKey = documentKey,
                    fileUri = uri.toString(),
                    relativePath = name,
                    name = name,
                    modTime = target.lastModified(),
                    size = target.length(),
                    state = "SEEN",
                    changed = true,
                ),
            )
            val row = dao.itemByDocumentKey(documentKey)
            assertNotNull("row missing for $name", row)
            assertTrue("metadata still empty for $name", row!!.otherMetadataJson != "{}")
            assertTrue("fingerprint not stored for $name", row.audioFingerprint?.startsWith("pcm-sha256-v1:") == true)
            target.delete()
        }

        val indexed = SearchIndexer(database).rebuild()
        assertTrue("search index is empty", indexed > 0)
        val hits = SearchIndexer(database).search("fixture", limit = 10)
        assertTrue("FTS found nothing for the published fixtures", hits.isNotEmpty())
        dao.deleteSource(sourceId)
    }
}
