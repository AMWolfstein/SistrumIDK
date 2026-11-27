package com.amwolfstein.sistrum.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryBatchMetadataTest {
    @Test
    fun existingTrackGainRequiresFiniteGainAndPositiveLinearPeak() {
        fun tags(gain: String, peak: String) = mapOf(
            "REPLAYGAIN_TRACK_GAIN" to listOf(gain),
            "REPLAYGAIN_TRACK_PEAK" to listOf(peak),
        )
        assertTrue(hasValidTrackGain(tags("-3.4 dB", "0.98")))
        assertFalse(hasValidTrackGain(tags("nan dB", "0.98")))
        assertFalse(hasValidTrackGain(tags("-3 dB", "0")))
        assertFalse(hasValidTrackGain(mapOf("REPLAYGAIN_TRACK_GAIN" to listOf("-3 dB"))))
    }
}
