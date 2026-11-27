package com.amwolfstein.sistrum.analysis

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pinned Airmedy FFmpeg 9 analyzer references for locally generated 1 kHz fixtures. */
class OfflineAudioDecoderTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun replayGainReportsLinearSamplePeakSeparatelyFromTruePeak() {
        val file = asset("rhythm.flac")
        try {
            OfflineAudioDecoder().use { decoder ->
                decoder.open(file)
                val values = decoder.replayGain()
                assertEquals(-18.62, values[0], .2)
                assertEquals(10.0.pow(-4.402936 / 20.0), values[1], .002)
                assertEquals(-4.394, values[2], .2)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun decodesSupportedFormatsWithExpectedLoudness() {
        val fixtures = listOf(
            Triple("mono.mp3", -27.16, -23.74),
            Triple("stereo.m4a", -27.1, -26.1),
            Triple("stereo-alac.m4a", -27.1, -27.1),
            Triple("stereo.opus", -27.1, -26.9),
            Triple("rhythm.flac", -18.623, -4.394),
            Triple("rhythm.wav", -18.658, -4.394),
        )
        fixtures.forEach { (name, expectedLufs, expectedPeak) ->
            val file = asset(name)
            try {
                OfflineAudioDecoder().use { decoder ->
                    decoder.open(file)
                    val metrics = decoder.analyze()
                    assertEquals(name, expectedLufs, metrics[0], .5)
                    assertEquals(name, expectedPeak, metrics[1], .7)
                }
            } finally {
                file.delete()
            }
        }
    }

    @Test
    fun silenceHasNoFiniteLoudnessOrTruePeak() {
        val file = asset("silence.wav")
        try {
            OfflineAudioDecoder().use { decoder ->
                decoder.open(file)
                val metrics = decoder.analyze()
                assertTrue(metrics[0] <= -70.0)
                assertTrue(!metrics[1].isFinite() || metrics[1] <= 0.0)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun cancellationAndRepeatedCloseReleaseDecoder() {
        val file = asset("stereo.opus")
        try {
            val decoder = OfflineAudioDecoder()
            decoder.open(file)
            decoder.cancel()
            org.junit.Assert.assertThrows(IllegalStateException::class.java) { decoder.analyze() }
            decoder.close()
            decoder.close()
        } finally {
            file.delete()
        }
    }

    private fun asset(name: String): File {
        val file = File.createTempFile("analysis-fixture-", "-$name", instrumentation.targetContext.cacheDir)
        instrumentation.context.assets.open("analysis/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }
}
