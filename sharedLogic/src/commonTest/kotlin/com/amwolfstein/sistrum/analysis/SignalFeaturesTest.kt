package com.amwolfstein.sistrum.analysis

import kotlin.test.Test
import kotlin.test.assertEquals

class SignalFeaturesTest {
    @Test
    fun reproducesPinnedAirmedyMedianFormulaVectors() {
        val f = SignalFeatures(50.0, 50.0, 50.0, 50.0, 115.0, 50.0, 50.0)
        val result = deriveMood(f, listOf(f))
        assertEquals(0.5, result.energy, 1e-9)
        assertEquals(0.725, result.danceability, 1e-9)
        assertEquals(0.5, result.brightness, 1e-9)
    }

    @Test
    fun matchesPinnedAirmedyGoOnNonMedianCorpus() {
        // Expected values were produced by Airmedy's pinned formulas.go and
        // normalizer.go using its linear percentile interpolation.
        val corpus = listOf(
            SignalFeatures(-22.0, 3.0, 800.0, .08, 90.0, 120.0, 2.0),
            SignalFeatures(-16.0, 5.0, 1500.0, .14, 115.0, 40.0, 4.0),
            SignalFeatures(-9.0, 9.0, 3200.0, .33, 160.0, 600.0, 10.0),
        )
        val expected = listOf(
            MoodValues(.207753658268975, .604039985048740, .169670686817667),
            MoodValues(.500000000000000, .780560292999595, .500000000000000),
            MoodValues(.873679606037991, .146789604697975, .979295482032244),
        )
        corpus.zip(expected).forEach { (features, reference) ->
            val actual = deriveMood(features, corpus)
            assertEquals(reference.energy, actual.energy, 1e-12)
            assertEquals(reference.danceability, actual.danceability, 1e-12)
            assertEquals(reference.brightness, actual.brightness, 1e-12)
        }
    }
}
