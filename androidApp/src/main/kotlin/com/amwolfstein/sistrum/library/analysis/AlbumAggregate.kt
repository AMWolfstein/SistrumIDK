package com.amwolfstein.sistrum.library.analysis

import com.amwolfstein.sistrum.player.NormalizationMode
import com.amwolfstein.sistrum.player.NormalizationSettings
import com.amwolfstein.sistrum.player.TrackAnalysis
import kotlin.math.min

data class AlbumAnalysis(val loudnessLufs: Float, val truePeak: Float, val trackCount: Int)

/**
 * Album aggregation for normalization: the album loudness is the mean track loudness and the album
 * peak is the loudest track peak. Aggregates are computed from the current Room rows on demand, so
 * adding, removing or reanalyzing a track invalidates the value automatically.
 */
object AlbumAggregate {
    fun aggregate(analyses: List<TrackAnalysis>): AlbumAnalysis? {
        if (analyses.isEmpty()) return null
        return AlbumAnalysis(
            loudnessLufs = analyses.map { it.loudnessLufs }.average().toFloat(),
            truePeak = analyses.maxOf { it.truePeak },
            trackCount = analyses.size,
        )
    }

    /**
     * Album-mode gain for one track. Falls back to the track's own analysis when the album has no
     * aggregate yet, so playback is never left without a gain.
     */
    fun gainDb(
        settings: NormalizationSettings,
        track: TrackAnalysis?,
        albumAnalyses: List<TrackAnalysis>,
        continuousAlbum: Boolean = true,
    ): Float {
        if (!settings.enabled || track == null) return 0f
        // Airmedy (normalization/service.go, sameAlbumChain) applies the album mean only while the
        // queue stays on one album; at an album seam playback falls back to track gain.
        val albumLoudness = if (settings.mode == NormalizationMode.Album && continuousAlbum) {
            aggregate(albumAnalyses)?.loudnessLufs ?: track.loudnessLufs
        } else {
            track.loudnessLufs
        }
        val gain = settings.targetLufs - albumLoudness
        // Clipping guard always uses the playing track's own true peak, as upstream does.
        return if (settings.preventClip) min(gain, -track.truePeak) else gain
    }
}
