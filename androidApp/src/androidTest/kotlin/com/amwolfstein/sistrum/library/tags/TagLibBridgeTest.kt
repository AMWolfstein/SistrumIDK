package com.amwolfstein.sistrum.library.tags

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TagLibBridgeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext = InstrumentationRegistry.getInstrumentation().context

    @Test
    fun readsEveryFixtureFormat() {
        listOf("fixture.mp3", "fixture.flac", "fixture.opus", "fixture.m4a", "fixture.wav").forEach { name ->
            val metadata = read(name)
            assertEquals(name, "Tag Fixture", metadata.title)
            assertEquals(name, "Fixture Artist", metadata.artist)
            assertEquals(name, "Fixture Album", metadata.album)
            assertTrue("$name duration", metadata.durationMs > 0)
            assertTrue("$name raw tags", metadata.rawTags.isNotEmpty())
        }
    }

    @Test
    fun readsAdditionalTagLibFormats() {
        // Fixtures from the TagLib test corpus: APE, WavPack, DSF, DFF and TTA.
        listOf("fixture.ape", "fixture.wv", "fixture.dsf", "fixture.dff", "fixture.tta").forEach { name ->
            val metadata = read(name)
            assertTrue(
                "$name duration was ${metadata.durationMs}; TagLib returned ${metadata.rawTags}",
                metadata.durationMs > 0 || metadata.rawTags.isNotEmpty(),
            )
        }
        val wavPack = read("fixture.wv")
        assertEquals("TestTitle", wavPack.title)
        assertEquals("TestArtist", wavPack.artist)
        val tta = read("fixture.tta")
        assertEquals("TestTitle", tta.title)
    }

    @Test
    fun roundTripsAstralUnicodeThroughJni() {
        val metadata = read("fixture-unicode.mp3")
        assertEquals("Song 🎵 日本語", metadata.title)
        assertEquals("Artist 𝄞", metadata.artist)
    }

    @Test
    fun readsExtendedFieldsAndArtwork() {
        val metadata = read("fixture.mp3")
        assertEquals("1999", metadata.first("DATE"))
        assertEquals("4", metadata.first("TRACKNUMBER"))
        assertEquals("hello", metadata.comment)
        assertEquals("line one", metadata.lyrics)
        assertTrue(metadata.hasArtwork)
        assertNotNull(metadata.artworkMime)

        val file = fixture("fixture.mp3")
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            val artwork = TagLibBridge.nativeReadArtwork(descriptor.fd)
            assertNotNull(artwork)
            assertTrue(artwork!!.isNotEmpty())
        }
    }

    @Test
    fun writesTagsAndArtworkToACopy() {
        val source = fixture("fixture.flac")
        val target = File(context.cacheDir, "write-target.flac")
        source.copyTo(target, overwrite = true)

        ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_WRITE).use { descriptor ->
            val written = TagLibBridge.nativeWriteTags(
                descriptor.fd,
                "New Title 🎵",
                "New Artist",
                "New Album",
                "New Album Artist",
                "Jazz",
                "New Composer",
                "New Comment",
                2024,
                7,
                2,
                "ISRC999",
                "New Label",
                "(c) 2024",
            )
            assertTrue(written)
        }
        val metadata = readFile(target)
        assertEquals("New Title 🎵", metadata.title)
        assertEquals("New Artist", metadata.artist)
        assertEquals("New Album", metadata.album)
        assertEquals("New Album Artist", metadata.albumArtist)
        assertEquals("Jazz", metadata.genre)
        assertEquals("New Composer", metadata.composer)
        assertEquals(2024, metadata.year)
        assertEquals(7, metadata.track)
        assertEquals(2, metadata.disc)
        assertEquals("ISRC999", metadata.first("ISRC"))

        val artwork = fixture("fixture.mp3").let { mp3 ->
            ParcelFileDescriptor.open(mp3, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                TagLibBridge.nativeReadArtwork(descriptor.fd)
            }
        }
        assertNotNull(artwork)
        ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_WRITE).use { descriptor ->
            assertTrue(TagLibBridge.nativeWriteArtwork(descriptor.fd, artwork!!, "image/png", "Cover"))
        }
        val withArt = readFile(target)
        assertTrue(withArt.hasArtwork)
        assertNotNull(withArt.artworkMime)
    }

    @Test
    fun unreadableDescriptorReturnsEmptyMetadata() {
        val empty = File(context.cacheDir, "not-audio.bin").apply { writeText("no audio") }
        ParcelFileDescriptor.open(empty, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            val metadata = TagMetadataJson.parse(TagLibBridge.nativeReadMetadata(descriptor.fd))
            assertEquals(TagMetadata(), metadata)
        }
    }

    private fun read(name: String): TagMetadata = readFile(fixture(name))

    private fun readFile(file: File): TagMetadata {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            return TagMetadataJson.parse(TagLibBridge.nativeReadMetadata(descriptor.fd))
        }
    }

    private fun fixture(name: String): File {
        val target = File(context.cacheDir, name)
        testContext.assets.open("taglib/$name").use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }
}
