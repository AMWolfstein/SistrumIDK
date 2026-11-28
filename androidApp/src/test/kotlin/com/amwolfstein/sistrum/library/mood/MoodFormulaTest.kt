package com.amwolfstein.sistrum.library.mood

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors the producer's own mood formula tests (mood/formulas_test.go, normalizer_test.go). */
class MoodFormulaTest {
    private val p = MoodFormula.Percentile(p1 = 0.0, p5 = 10.0, p50 = 50.0, p95 = 90.0, p99 = 100.0)
    private val set = MoodFormula.FEATURES.associateWith { p }

    @Test
    fun normalizeMedianIsHalfAndClamps() {
        assertEquals(0.5, MoodFormula.normalize(50.0, p), 1e-9)
        assertEquals(
            MoodFormula.normalize(100.0, p),
            MoodFormula.normalize(1000.0, p),
            0.0,
        )
        assertEquals(
            MoodFormula.normalize(0.0, p),
            MoodFormula.normalize(-1000.0, p),
            0.0,
        )
    }

    @Test
    fun degenerateSpreadIsNeutral() {
        val flat = MoodFormula.Percentile(5.0, 5.0, 5.0, 5.0, 5.0)
        assertEquals(0.5, MoodFormula.normalize(5.0, flat), 0.0)
        assertEquals(0.5, MoodFormula.normalize(5.0, null), 0.0)
    }

    @Test
    fun tempoScoreIsTriangular() {
        assertEquals(0.0, MoodFormula.tempoScore(60.0), 1e-9)
        assertEquals(1.0, MoodFormula.tempoScore(115.0), 1e-9)
        assertEquals(0.0, MoodFormula.tempoScore(180.0), 1e-9)
        assertEquals(0.5, MoodFormula.tempoScore(87.5), 1e-9)
        assertEquals(0.5, MoodFormula.tempoScore(147.5), 1e-9)
    }

    @Test
    fun energyAtEveryMedianIsHalf() {
        val raw = MoodFormula.Raw(50.0, 50.0, 50.0, 50.0, 50.0, 50.0, 50.0)
        assertEquals(0.5, MoodFormula.energy(raw, set), 1e-9)
    }

    @Test
    fun energyTempoCapMakesFastTracksAgree() {
        val tempo = MoodFormula.Percentile(60.0, 80.0, 115.0, 160.0, 180.0)
        val withCap = set + ("tempo" to tempo)
        val at180 = MoodFormula.Raw(50.0, 50.0, 50.0, 180.0, 50.0, 50.0, 50.0)
        val at200 = at180.copy(tempo = 200.0)
        assertEquals(MoodFormula.energy(at180, withCap), MoodFormula.energy(at200, withCap), 1e-9)
    }

    @Test
    fun percentilesInterpolateLikeNumpy() {
        val values = listOf(1.0, 2.0, 3.0, 4.0)
        val pctl = MoodFormula.percentile(values)
        // rank = p*(n-1): p50 -> 2.5, p95 -> 3.85
        assertEquals(2.5, pctl.p50, 1e-9)
        assertEquals(3.85, pctl.p95, 1e-9)
        assertEquals(1.03, pctl.p1, 1e-9)
    }

    @Test
    fun derivedValuesStayInUnitRange() {
        val corpus = (1..20).map { i ->
            MoodFormula.Raw(
                rms = -40.0 + i,
                spectralCentroid = 200.0 * i,
                spectralFlux = 0.001 * i,
                tempo = 60.0 + 5 * i,
                crest = 1.5 + 0.05 * i,
                onsetVariance = 10.0 * i,
                loudnessRange = 2.0 + 0.5 * i,
            )
        }
        val percentiles = MoodFormula.percentiles(corpus)
        corpus.forEach { raw ->
            val derived = MoodFormula.derive(raw, percentiles)
            assertTrue("energy ${derived.energy}", derived.energy in 0.0..1.0)
            assertTrue("danceability ${derived.danceability}", derived.danceability in 0.0..1.0)
            assertTrue("brightness ${derived.brightness}", derived.brightness in 0.0..1.0)
        }
    }
}
