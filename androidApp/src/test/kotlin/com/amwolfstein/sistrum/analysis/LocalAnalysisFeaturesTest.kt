package com.amwolfstein.sistrum.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalAnalysisFeaturesTest {
    @Test
    fun airmedyAcceptsZeroTempoWhenNoBeatWasFound() {
        val row = LocalTrackAnalysisEntity(
            trackId = "track", sourceRevision = "revision", analyzerVersion = LocalAnalyzerVersion,
            status = "success",
            rawJson = """{"rms":-15,"crest":4,"spectral_centroid":1000,"spectral_flux":0.1,"tempo":0,"onset_variance":0,"loudness_range":3}""",
            failure = "",
        )
        assertEquals(0.0, localSignalFeatures(row)!!.tempo, 0.0)
        assertNull(localSignalFeatures(row.copy(status = "failed")))
    }
}
