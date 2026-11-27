package com.amwolfstein.sistrum.player

import kotlin.math.min

enum class NormalizationMode { Track, Album }
enum class ReplayGainMode { Off, Track, Album }

data class NormalizationSettings(
    val enabled: Boolean = false,
    val mode: NormalizationMode = NormalizationMode.Track,
    val targetLufs: Float = -14f,
    val preventClip: Boolean = true,
    val replayGainMode: ReplayGainMode = ReplayGainMode.Off,
    val replayGainPreampDb: Float = 0f,
)

data class TrackAnalysis(val loudnessLufs: Float, val truePeak: Float)

/** Pure desktop-compatible loudness gain calculation. */
fun normalizationGainDb(
    settings: NormalizationSettings,
    analysis: TrackAnalysis?,
    albumAnalyses: List<TrackAnalysis> = emptyList(),
    continuousAlbum: Boolean = false,
): Float {
    if (!settings.enabled || analysis == null) return 0f
    val sourceLufs = if (settings.mode == NormalizationMode.Album && continuousAlbum && albumAnalyses.isNotEmpty()) {
        albumAnalyses.map { it.loudnessLufs }.average().toFloat()
    } else analysis.loudnessLufs
    val gain = settings.targetLufs - sourceLufs
    return if (settings.preventClip) min(gain, -analysis.truePeak) else gain
}

/** ReplayGain takes precedence over analysis normalization and never adds to it. */
fun replayGainPlaybackDb(properties: Map<String, List<String>>, mode: ReplayGainMode, preampDb: Float, preventClip: Boolean): Float {
    if (mode == ReplayGainMode.Off) return 0f
    fun number(key: String): Float? = properties[key]?.firstOrNull()?.trim()?.removeSuffix("dB")?.trim()?.toFloatOrNull()?.takeIf { it.isFinite() }
    val prefix = if (mode == ReplayGainMode.Album && number("REPLAYGAIN_ALBUM_GAIN") != null) "ALBUM" else "TRACK"
    val gain = number("REPLAYGAIN_${prefix}_GAIN") ?: return 0f
    val effective = (gain + preampDb.coerceIn(-12f, 12f)).coerceIn(-60f, 30f)
    val peak = number("REPLAYGAIN_${prefix}_PEAK")?.takeIf { it > 0f }
    return if (preventClip && peak != null) min(effective, (-20.0 * kotlin.math.log10(peak.toDouble())).toFloat()) else effective
}
