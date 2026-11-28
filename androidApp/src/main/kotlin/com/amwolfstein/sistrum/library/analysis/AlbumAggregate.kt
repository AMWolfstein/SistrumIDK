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
    ): Float {
        if (!settings.enabled || track == null) return 0f
        val source = if (settings.mode == NormalizationMode.Album) {
            aggregate(albumAnalyses)?.loudnessLufs ?: track.loudnessLufs
        } else {
            track.loudnessLufs
        }
        val peak = if (settings.mode == NormalizationMode.Album) {
            aggregate(albumAnalyses)?.truePeak ?: track.truePeak
        } else {
            track.truePeak
        }
        val gain = settings.targetLufs - source
        return if (settings.preventClip) min(gain, -peak) else gain
    }
}
