package com.amwolfstein.sistrum.analysis

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** FFmpeg host ebur128 references for locally generated 1 kHz fixtures. */
class OfflineAudioDecoderTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun decodesMp3AacAlacAndOpusWithExpectedLoudness() {
        val fixtures = listOf(
            Triple("mono.mp3", -27.2, -26.8),
            Triple("stereo.m4a", -27.1, -26.1),
            Triple("stereo-alac.m4a", -27.1, -27.1),
            Triple("stereo.opus", -27.1, -26.9),
        )
        fixtures.forEach { (name, expectedLufs, expectedPeak) ->
            val file = asset(name)
            try {
                OfflineAudioDecoder().use { decoder ->
                    decoder.open(file)
                    val buffer = FloatArray(4096)
                    var samples = 0
                    while (true) {
                        val count = decoder.read(buffer)
                        if (count == 0) break
                        assertTrue(name, count > 0 && count % 2 == 0)
                        samples += count
                    }
                    assertTrue(name, samples in 280_000..300_000)
                    val metrics = decoder.metrics()
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
                val buffer = FloatArray(4096)
                while (decoder.read(buffer) > 0) Unit
                val metrics = decoder.metrics()
                assertTrue(!metrics[0].isFinite())
                assertTrue(!metrics[1].isFinite())
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
            org.junit.Assert.assertThrows(IllegalStateException::class.java) { decoder.read(FloatArray(4096)) }
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
