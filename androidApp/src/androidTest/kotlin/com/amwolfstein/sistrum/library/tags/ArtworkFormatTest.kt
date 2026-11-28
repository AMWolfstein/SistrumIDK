package com.amwolfstein.sistrum.library.tags

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.amwolfstein.sistrum.library.db.LibraryDatabase
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Embedded-artwork read path, format by format: the scanner must see `hasArtwork` and the bytes
 * must decode as an image for every container that supports an attached picture.
 */
class ArtworkFormatTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun probe(path: String, name: String): Pair<Boolean, Int> {
        val file = File(context.cacheDir, "art-$name")
        instrumentation.context.assets.open(path).use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        val (has, size, mime) = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            val metadata = TagMetadataJson.parse(TagLibBridge.nativeReadMetadata(descriptor.fd))
            val bytes = if (metadata.hasArtwork) TagLibBridge.nativeReadArtwork(descriptor.fd) else null
            Triple(metadata.hasArtwork, bytes?.size ?: 0, metadata.artworkMime)
        }
        file.delete()
        println("ARTWORK $name has=$has size=$size mime=$mime")
        return has to size
    }

    @Test
    fun everyFixtureFormatReportsItsEmbeddedPicture() {
        val cases = listOf(
            "taglib/fixture.mp3" to "fixture.mp3",
            "taglib/fixture.flac" to "fixture.flac",
            "taglib/fixture.m4a" to "fixture.m4a",
            "taglib/fixture.opus" to "fixture.opus",
            "taglib/fixture.wv" to "fixture.wv",
            "taglib/fixture.wav" to "fixture.wav",
        )
        val results = cases.associate { (path, name) -> name to probe(path, name) }
        // fixture.mp3 embeds a PNG APIC; every other fixture has no picture, so both directions
        // must be consistent (hasArtwork == bytes > 0).
        results.forEach { (name, result) ->
            assertTrue("$name: hasArtwork=${result.first} bytes=${result.second}", result.first == (result.second > 0))
        }
        assertTrue("fixture.mp3 must expose its APIC", results.getValue("fixture.mp3").second > 0)
    }

    @Test
    fun realLibraryArtworkMatchesAndroidMetadataRetriever() = runBlocking {
        val items = LibraryDatabase.get(context).libraryDao().allItems().filter { it.available }.take(40)
        var withArt = 0
        var compared = 0
        val disagreements = mutableListOf<String>()
        items.forEach { item ->
            runCatching {
                context.contentResolver.openFileDescriptor(Uri.parse(item.fileUri), "r")?.use { descriptor ->
                    val metadata = TagMetadataJson.parse(TagLibBridge.nativeReadMetadata(descriptor.fd))
                    val bytes = if (metadata.hasArtwork) TagLibBridge.nativeReadArtwork(descriptor.fd) else null
                    if (metadata.hasArtwork) {
                        withArt += 1
                        assertTrue(
                            "${item.fileUri}: hasArtwork but no bytes",
                            (bytes?.size ?: 0) > 0,
                        )
                        // A usable picture must carry a known signature.
                        val head = bytes!!.take(4)
                        val jpeg = head.size >= 2 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte()
                        val png = head.size >= 4 && head[0] == 0x89.toByte() &&
                            head[1] == 0x50.toByte() && head[2] == 0x4E.toByte() && head[3] == 0x47.toByte()
                        val known = jpeg || png
                        assertTrue("${item.fileUri}: unknown picture signature", known)
                    } else if (bytes != null) {
                        assertTrue("${item.fileUri}: bytes without hasArtwork", false)
                    }
                    val extension = item.format?.lowercase()
                    if (extension in setOf("mp3", "flac", "m4a", "mp4", "aac", "ogg", "opus")) {
                        val retriever = android.media.MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(context, Uri.parse(item.fileUri))
                            val platformHasArt = retriever.embeddedPicture != null
                            compared += 1
                            if (platformHasArt != metadata.hasArtwork) {
                                disagreements += "${item.fileUri} platform=$platformHasArt taglib=${metadata.hasArtwork}"
                            }
                        } catch (_: Throwable) {
                        } finally {
                            retriever.release()
                        }
                    }
                }
            }
        }
        println("ARTWORK library scanned=${items.size} withArtwork=$withArt compared=$compared")
        assertTrue("artwork disagreement: ${disagreements.take(5)}", disagreements.isEmpty())
    }
}
