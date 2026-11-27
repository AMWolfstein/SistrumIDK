package com.amwolfstein.sistrum.player

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decode/seek matrix for every fixture the device tests ship. Real audio route, focus and
 * crossfade timing need an audible session, so they stay documented as unverified in the task log.
 */
class PlaybackFormatMatrixTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val files = mutableListOf<File>()

    @After
    fun tearDown() {
        files.forEach(File::delete)
    }

    @Test
    fun everySupportedFixturePreparesSeeksAndReportsADuration() {
        val fixtures = listOf(
            "fixture.mp3", "fixture.flac", "fixture.m4a", "fixture.opus", "fixture.wav",
            "fixture.ape", "fixture.dsf", "fixture.dff", "fixture.tta", "fixture-unicode.mp3",
        )
        fixtures.forEach { name ->
            val target = File(context.cacheDir, "matrix-$name")
            files += target
            InstrumentationRegistry.getInstrumentation().context.assets.open("taglib/$name").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            val decoder = FfmpegDecoder()
            try {
                ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    decoder.prepare(descriptor)
                }
                val duration = decoder.durationMs()
                assertTrue("$name should report a duration", duration > 0)
                val target = (duration / 2).coerceAtLeast(1)
                decoder.seekTo(target)
                val position = decoder.positionMs()
                assertTrue("$name seek should land near $target but was $position", position in 0..duration)
            } finally {
                decoder.close()
            }
        }
    }

    @Test
    fun equalizerAndNormalizationConfigurationIsAcceptedBeforePlayback() {
        val target = File(context.cacheDir, "matrix-dsp.mp3")
        files += target
        InstrumentationRegistry.getInstrumentation().context.assets.open("taglib/fixture.mp3").use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        val decoder = FfmpegDecoder()
        try {
            decoder.setGlobalDspConfig(
                GlobalDspConfig(preampGainDb = 0f, stereoWidth = 1f, eqBandGainsDb = FloatArray(10)),
            )
            decoder.setFocusGain(0.5f)
            ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                decoder.prepare(descriptor, normalizationGainDb = -3f)
            }
            assertTrue(decoder.durationMs() > 0)
            decoder.setNormalizationGains(-3f, -2f)
            decoder.play()
            decoder.pause()
        } finally {
            decoder.close()
        }
    }
}
