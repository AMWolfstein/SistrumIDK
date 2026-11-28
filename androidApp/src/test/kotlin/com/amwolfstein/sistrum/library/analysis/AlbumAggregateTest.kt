package com.amwolfstein.sistrum.library.analysis

import com.amwolfstein.sistrum.player.NormalizationMode
import com.amwolfstein.sistrum.player.NormalizationSettings
import com.amwolfstein.sistrum.player.TrackAnalysis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlbumAggregateTest {
    private val album = listOf(TrackAnalysis(-12f, -0.5f), TrackAnalysis(-16f, -0.2f))

    @Test
    fun aggregateUsesMeanLoudnessAndLoudestPeak() {
        val result = AlbumAggregate.aggregate(album)!!
        assertEquals(-14f, result.loudnessLufs, 0.0001f)
        assertEquals(-0.2f, result.truePeak, 0.0001f)
        assertEquals(2, result.trackCount)
        assertNull(AlbumAggregate.aggregate(emptyList()))
    }

    @Test
    fun trackModeUsesTheTrackAnalysisOnly() {
        val settings = NormalizationSettings(enabled = true, targetLufs = -14f, preventClip = false)
        assertEquals(2f, AlbumAggregate.gainDb(settings, TrackAnalysis(-16f, -1f), album), 0.0001f)
    }

    @Test
    fun albumModeUsesTheAggregate() {
        val settings = NormalizationSettings(
            enabled = true,
            mode = NormalizationMode.Album,
            targetLufs = -14f,
            preventClip = false,
        )
        assertEquals(0f, AlbumAggregate.gainDb(settings, TrackAnalysis(-12f, -0.5f), album), 0.0001f)
    }

    @Test
    fun clippingPreventionCapsGainAtNegativePeak() {
        val settings = NormalizationSettings(enabled = true, targetLufs = -10f, preventClip = true)
        assertEquals(0.5f, AlbumAggregate.gainDb(settings, TrackAnalysis(-30f, -0.5f), emptyList()), 0.0001f)
    }

    @Test
    fun albumModeFallsBackToTheTrackWhenAggregateIsMissing() {
        val settings = NormalizationSettings(enabled = true, mode = NormalizationMode.Album, targetLufs = -14f, preventClip = false)
        assertEquals(2f, AlbumAggregate.gainDb(settings, TrackAnalysis(-16f, -1f), emptyList()), 0.0001f)
    }

    @Test
    fun disabledOrMissingAnalysisProducesNoGain() {
        val disabled = NormalizationSettings(enabled = false)
        assertEquals(0f, AlbumAggregate.gainDb(disabled, TrackAnalysis(-16f, -1f), album), 0.0001f)
        val enabled = NormalizationSettings(enabled = true)
        assertEquals(0f, AlbumAggregate.gainDb(enabled, null, album), 0.0001f)
    }
}
