package com.amwolfstein.sistrum.player

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.amwolfstein.sistrum.library.db.LibraryDatabase
import com.amwolfstein.sistrum.library.tags.TagLibBridge
import com.amwolfstein.sistrum.library.tags.TagMetadataJson
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Reads a real provider-backed document through the persisted SAF grant. Unlike the
 * fixture tests, this exercises the pread path that `/proc/self/fd` reopening broke.
 * Read-only: the source library and its tags are never modified.
 */
class SafNativeIoTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun readsMetadataPlaysAndAnalyzesOneSafDocument() {
        val items = kotlinx.coroutines.runBlocking {
            LibraryDatabase.get(context).libraryDao().allItems().filter { it.available }
        }
        assumeTrue("no scanned local library on this device", items.isNotEmpty())
        val item = items.first()
        val uri = Uri.parse(item.fileUri)

        context.contentResolver.openFileDescriptor(uri, "r")!!.use { descriptor ->
            val metadata = TagMetadataJson.parse(TagLibBridge.nativeReadMetadata(descriptor.fd))
            assertTrue("metadata was empty for ${item.fileUri}", metadata.durationMs > 0L)
            assertTrue("audio fingerprint missing", TagLibBridge.nativeAudioFingerprint(descriptor.fd, 0, -1)?.startsWith("pcm-sha256-v1:") == true)
            val analysis = AnalysisBridge.nativeAnalyzeFd(descriptor.fd, 0, -1)
            assertTrue("analysis returned ${analysis.size} fields", analysis.size == 15)
        }

        context.contentResolver.openFileDescriptor(uri, "r")!!.use { descriptor ->
            val decoder = FfmpegDecoder()
            try {
                decoder.prepare(descriptor)
                assertTrue("decoder reported no duration", decoder.durationMs() > 0)
            } finally {
                decoder.close()
            }
        }
    }

    @Test
    fun metadataSurvivesAFileCopyButNotAnAudioChange() {
        val items = kotlinx.coroutines.runBlocking {
            LibraryDatabase.get(context).libraryDao().allItems().filter { it.available }
        }
        assumeTrue("no scanned local library on this device", items.isNotEmpty())
        val uri = Uri.parse(items.first().fileUri)
        val first = context.contentResolver.openFileDescriptor(uri, "r")!!.use { descriptor ->
            TagLibBridge.nativeAudioFingerprint(descriptor.fd, 0, -1)
        }
        val second = context.contentResolver.openFileDescriptor(uri, "r")!!.use { descriptor ->
            TagLibBridge.nativeAudioFingerprint(descriptor.fd, 0, -1)
        }
        assertTrue("fingerprint is not stable across reads", first != null && first == second)
    }
}
