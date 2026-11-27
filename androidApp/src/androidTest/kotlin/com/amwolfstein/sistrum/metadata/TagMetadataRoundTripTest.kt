package com.amwolfstein.sistrum.metadata

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TagMetadataRoundTripTest {
    @Test
    fun readsAvailableSupportedAudioFormatsWithoutLoadingPictures() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        listOf("mono.mp3", "stereo.m4a", "stereo-alac.m4a", "rhythm.flac", "stereo.opus", "rhythm.wav").forEach { name ->
            val file = File.createTempFile("tag-format", ".$name", context.cacheDir)
            try {
                assets.open("analysis/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
                val metadata = TagMetadata.read(file, readPictures = false)
                assertTrue(metadata.pictures.isEmpty())
            } finally {
                file.delete()
            }
        }
    }

    @Test
    fun writesUnicodeAndPreservesOtherFieldsInMp3AndFlac() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        listOf("mono.mp3", "rhythm.flac").forEach { name ->
            val source = File.createTempFile("tag-original", ".$name", context.cacheDir)
            val target = File.createTempFile("tag-edited", ".$name", context.cacheDir)
            try {
                assets.open("analysis/$name").use { input ->
                    source.outputStream().use { output -> input.copyTo(output) }
                }
                source.copyTo(target, overwrite = true)
                val original = TagMetadata.read(source, readPictures = true)
                val title = "أغنية 😀"
                val expected = mergeTagProperties(original.properties, mapOf("TITLE" to listOf(title)))
                TagMetadata.write(target, expected, null)
                val result = TagMetadata.read(target, readPictures = true)
                assertEquals(expected, result.properties)
                assertEquals(original.pictures, result.pictures)
                val update = Mp3TagUpdate(title = title)
                if (name.endsWith(".mp3")) verifyMp3Preservation(source, target, update)
                else verifyFlacPreservation(source, target, update)
            } finally {
                source.delete()
                target.delete()
            }
        }
    }
}
