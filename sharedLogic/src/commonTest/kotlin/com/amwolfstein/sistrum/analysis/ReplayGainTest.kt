package com.amwolfstein.sistrum.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReplayGainTest {
    @Test
    fun usesLufsReferenceAndKeepsPeaksInTheirOwnUnits() {
        val result = replayGainTrack(-22.0, 0.5, -5.8)!!
        assertEquals(4.0, result.gainDb)
        assertEquals(0.5, result.samplePeak)
        assertEquals(-5.8, result.truePeakDb)
    }

    @Test
    fun rejectsSilenceAndNonFiniteMeasurements() {
        assertNull(replayGainTrack(-70.0, 0.0, Double.NEGATIVE_INFINITY))
        assertNull(replayGainTrack(Double.NaN, 0.5, -6.0))
        assertNull(replayGainTrack(-20.0, Double.POSITIVE_INFINITY, -6.0))
    }
}
