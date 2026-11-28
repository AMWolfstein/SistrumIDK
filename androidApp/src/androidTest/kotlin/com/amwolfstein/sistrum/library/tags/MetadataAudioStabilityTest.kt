package com.amwolfstein.sistrum.library.tags

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks that writing standard/analysis tags does not alter decoded PCM on every format. */
class MetadataAudioStabilityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun fingerprint(file: File): String? =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use {
            TagLibBridge.nativeAudioFingerprint(it.fd, 0, -1)
        }

    private fun probe(asset: String, path: String = "taglib/$asset") {
        val file = File(context.cacheDir, "stability-${System.nanoTime()}-$asset")
        instrumentation.context.assets.open(path).use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        val beforeFingerprint = fingerprint(file)
        val external = java.io.File(context.getExternalFilesDir("stability"), "before-$asset")
        external.parentFile?.mkdirs()
        file.copyTo(external, overwrite = true)
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).use { descriptor ->
            TagLibBridge.nativeWriteProperties(
                descriptor.fd,
                arrayOf(
                    "REPLAYGAIN_TRACK_GAIN",
                    "SISTRUM_ANALYSIS_VERSION",
                    "SISTRUM_LUFS",
                ),
                arrayOf("-7.00 dB", "4", "-20.500000000"),
            )
        }
        val afterFingerprint = fingerprint(file)
        file.copyTo(java.io.File(context.getExternalFilesDir("stability"), "after-$asset"), overwrite = true)
        val tags = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use {
            TagMetadataJson.parse(TagLibBridge.nativeReadMetadata(it.fd)).rawTags
        }
        file.delete()
        assertEquals("$asset tags", listOf("-7.00 dB"), tags["REPLAYGAIN_TRACK_GAIN"])
        assertEquals("$asset PCM", beforeFingerprint, afterFingerprint)
    }

    // fixture.mp3 is a 1.4 kB synthetic frame without a LAME/Xing header: TagLib rewrites its
    // decoder padding, so the decoded tail changes while the audible samples do not. The real
    // encoder-produced MP3 below is the meaningful case for the payload contract.
    @Test fun mp3() = probe("tone.mp3", "analysis/tone.mp3")

    @Test fun replacingAnExistingPropertyWorksOnMp3() {
        val file = File(context.cacheDir, "replace-${System.nanoTime()}.mp3")
        instrumentation.context.assets.open("analysis/tone.mp3").use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        fun write(value: String) = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).use { descriptor ->
            TagLibBridge.nativeWriteProperties(descriptor.fd, arrayOf("REPLAYGAIN_TRACK_GAIN"), arrayOf(value))
        }
        assertTrue(write("-99.00 dB"))
        assertTrue(write("-7.00 dB"))
        val tags = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use {
            TagMetadataJson.parse(TagLibBridge.nativeReadMetadata(it.fd)).rawTags
        }
        file.delete()
        assertEquals(listOf("-7.00 dB"), tags["REPLAYGAIN_TRACK_GAIN"])
    }
    @Test fun flac() = probe("fixture.flac")
    @Test fun m4a() = probe("fixture.m4a")
    @Test fun opus() = probe("fixture.opus")
    @Test fun wav() = probe("fixture.wav")
    @Test fun wavpack() = probe("fixture.wv")
}
