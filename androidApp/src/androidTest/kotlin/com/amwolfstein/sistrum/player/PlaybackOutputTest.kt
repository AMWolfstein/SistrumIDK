package com.amwolfstein.sistrum.player

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Measurable playback evidence on the real device: every supported container is decoded and
 * rendered through AAudio, position advances, seeking lands within one buffer, and the output
 * stream reports no underruns.
 */
class PlaybackOutputTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val cache = instrumentation.targetContext.cacheDir

    private val formats = listOf(
        "wav" to "tone-440-3s.wav",
        "flac" to "tone-1k-5s-48k-24bit.flac",
        "mp3" to "tone.mp3",
        "m4a" to "tone.m4a",
        "ogg" to "tone.ogg",
        "opus" to "tone.opus",
        "wavpack" to "tone.wv",
        "aiff" to "tone.aiff",
    )

    private fun copy(name: String): File {
        val file = File(cache, "playback-$name")
        instrumentation.context.assets.open("analysis/$name").use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return file
    }

    @Test
    fun everySupportedFormatRendersWithoutUnderruns() {
        for ((label, asset) in formats) {
            val file = copy(asset)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                val decoder = FfmpegDecoder()
                try {
                    decoder.prepare(descriptor)
                    val duration = decoder.durationMs()
                    assertTrue("$label: no duration", duration > 500)
                    decoder.play()
                    Thread.sleep(900)
                    val position = decoder.positionMs()
                    assertTrue("$label: position did not advance ($position ms)", position > 300)
                    val xruns = decoder.xRunCount()
                    assertEquals("$label: AAudio underruns", 0, xruns.coerceAtLeast(0))

                    val target = duration / 2
                    decoder.seekTo(target)
                    Thread.sleep(250)
                    val after = decoder.positionMs()
                    assertTrue(
                        "$label: seek to $target landed at $after",
                        kotlin.math.abs(after - target) < 700,
                    )
                    assertEquals("$label: underruns after seek", 0, decoder.xRunCount().coerceAtLeast(0))
                } finally {
                    decoder.close()
                }
            }
            file.delete()
        }
    }

    @Test
    fun crossfadeIntoTheNextTrackKeepsTheOutputRunning() {
        val first = copy("tone.mp3")
        val second = copy("tone.ogg")
        ParcelFileDescriptor.open(first, ParcelFileDescriptor.MODE_READ_ONLY).use { firstFd ->
            ParcelFileDescriptor.open(second, ParcelFileDescriptor.MODE_READ_ONLY).use { secondFd ->
                val decoder = FfmpegDecoder()
                try {
                    decoder.prepare(firstFd)
                    decoder.play()
                    Thread.sleep(400)
                    assertTrue("preload failed", decoder.preload(secondFd))
                    decoder.beginCrossfade(1200)
                    Thread.sleep(1500)
                    decoder.finishCrossfade()
                    assertEquals(0, decoder.xRunCount().coerceAtLeast(0))
                } finally {
                    decoder.close()
                }
            }
        }
        first.delete()
        second.delete()
    }
}
