package com.amwolfstein.sistrum.analysis

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignalFeaturesTest {
    @Test
    fun rhythmEstimatorFindsRegular120BpmClicks() {
        val analyzer = SignalFeatureExtractor()
        val frames = 48_000 * 12
        val samples = FloatArray(frames * 2)
        for (i in 0 until frames) {
            val click = if (i % 24_000 < 120) 0.8 * sin(2 * PI * 1000 * i / 48_000) else 0.0
            samples[i * 2] = click.toFloat()
            samples[i * 2 + 1] = click.toFloat()
        }
        analyzer.add(samples, samples.size)
        val result = assertNotNull(analyzer.finish(0.0))
        assertTrue(result.tempo in 115.0..125.0, "tempo=${result.tempo}")
        assertTrue(result.spectralCentroid in 600.0..1800.0, "centroid=${result.spectralCentroid}")
        assertTrue(result.onsetVariance.isFinite())
    }

    @Test
    fun rhythmEstimatorMeasuresDistinctPulseRates() {
        for (bpm in listOf(90, 150)) {
            val analyzer = SignalFeatureExtractor()
            val frames = 48_000 * 12
            val period = 48_000 * 60 / bpm
            val samples = FloatArray(frames * 2)
            for (i in 0 until frames) {
                val click = if (i % period < 120) 0.8 * sin(2 * PI * 1000 * i / 48_000) else 0.0
                samples[i * 2] = click.toFloat()
                samples[i * 2 + 1] = click.toFloat()
            }
            analyzer.add(samples, samples.size)
            val result = assertNotNull(analyzer.finish(0.0))
            assertTrue(result.tempo in bpm - 5.0..bpm + 5.0, "expected=$bpm actual=${result.tempo}")
        }
    }

    @Test
    fun silenceAndUnrhythmicSineHaveNoMoodFeatures() {
        val silent = SignalFeatureExtractor()
        val constant = SignalFeatureExtractor()
        val samples = FloatArray(48_000 * 12 * 2)
        val silence = FloatArray(samples.size)
        for (i in 0 until samples.size / 2) {
            val sample = (0.1 * sin(2 * PI * 1000 * i / 48_000)).toFloat()
            samples[i * 2] = sample
            samples[i * 2 + 1] = sample
        }
        silent.add(silence, silence.size)
        constant.add(samples, samples.size)
        assertNull(silent.finish(0.0))
        assertNull(constant.finish(0.0))
    }

    @Test
    fun reproducesPinnedAirmedyMedianFormulaVectors() {
        val f = SignalFeatures(50.0, 50.0, 50.0, 50.0, 115.0, 50.0, 50.0)
        val result = deriveMood(f, listOf(f))
        assertEquals(0.5, result.energy, 1e-9)
        assertEquals(0.725, result.danceability, 1e-9)
        assertEquals(0.5, result.brightness, 1e-9)
    }
}
