package com.amwolfstein.sistrum.library.analysis

import com.amwolfstein.sistrum.player.NormalizationMode
import com.amwolfstein.sistrum.player.NormalizationSettings
import com.amwolfstein.sistrum.player.TrackAnalysis
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Album-mode normalization parity with Airmedy d859940c (normalization/service.go):
 * - album loudness = arithmetic mean of the album tracks' integrated LUFS
 * - album gain is used only while the queue continues on the same album
 * - clipping guard always uses the playing track's own true peak
 */
class AlbumAggregateTest {
    private val track = TrackAnalysis(loudnessLufs = -20f, truePeak = -0.5f)
    private val album = listOf(
        TrackAnalysis(loudnessLufs = -10f, truePeak = -1f),
        TrackAnalysis(loudnessLufs = -20f, truePeak = -0.5f),
        TrackAnalysis(loudnessLufs = -30f, truePeak = -2f),
    )
    private val settings = NormalizationSettings(
        enabled = true,
        mode = NormalizationMode.Album,
        targetLufs = -14f,
        preventClip = true,
    )

    @Test
    fun albumLoudnessIsTheArithmeticMeanNotAnEnergyMean() {
        val aggregate = AlbumAggregate.aggregate(album)!!
        assertEquals(-20f, aggregate.loudnessLufs, 1e-6f)
        assertEquals(-0.5f, aggregate.truePeak, 1e-6f)
        assertEquals(3, aggregate.trackCount)
    }

    @Test
    fun albumGainUsesTheMeanWhileTheAlbumContinues() {
        // -14 - (-20) = +6 dB, then clipped to the track's own headroom (+0.5 dB).
        assertEquals(0.5f, AlbumAggregate.gainDb(settings, track, album, continuousAlbum = true), 1e-6f)
    }

    @Test
    fun fallsBackToTrackGainAtAnAlbumSeam() {
        // -14 - (-20) = +6 dB, clipped to +0.5 dB by the same track peak.
        val noClip = settings.copy(preventClip = false)
        assertEquals(6f, AlbumAggregate.gainDb(noClip, track, album, continuousAlbum = false), 1e-6f)
        // With a different album mean (-10) the seam must not use +4 dB.
        assertEquals(6f, AlbumAggregate.gainDb(noClip, track, album.take(1), continuousAlbum = false), 1e-6f)
    }

    @Test
    fun clippingGuardUsesTheTrackPeakNotTheAlbumPeak() {
        // Album peak is -2 dB but this track peaks at -0.5 dB, so headroom is +0.5 dB.
        val loudAlbum = listOf(TrackAnalysis(-40f, -2f), TrackAnalysis(-40f, -2f))
        val noClip = AlbumAggregate.gainDb(settings, track, loudAlbum, continuousAlbum = true)
        assertEquals(0.5f, noClip, 1e-6f)
    }

    @Test
    fun missingAnalysisPlaysAtNormalVolume() {
        assertEquals(0f, AlbumAggregate.gainDb(settings, null, album), 0f)
        assertEquals(0f, AlbumAggregate.gainDb(settings.copy(enabled = false), track, album), 0f)
    }

    @Test
    fun emptyAlbumFallsBackToTheTrack() {
        val noClip = settings.copy(preventClip = false)
        assertEquals(6f, AlbumAggregate.gainDb(noClip, track, emptyList(), continuousAlbum = true), 1e-6f)
    }
}
