package com.amwolfstein.sistrum.library.analysis

import com.amwolfstein.sistrum.library.db.TrackAnalysisEntity
import java.util.Locale

/**
 * Maps analysis results to standard ReplayGain/R128 tags plus the versioned
 * analysis tags required by FR-084/FR-086. Existing ReplayGain keys are never
 * overwritten; [existingKeys] is the set already present on the file.
 */
object AnalysisTagCodec {
    const val TRACK_GAIN = "REPLAYGAIN_TRACK_GAIN"
    const val TRACK_PEAK = "REPLAYGAIN_TRACK_PEAK"
    const val ALBUM_GAIN = "REPLAYGAIN_ALBUM_GAIN"
    const val ALBUM_PEAK = "REPLAYGAIN_ALBUM_PEAK"
    const val R128_TRACK_GAIN = "R128_TRACK_GAIN"
    const val R128_ALBUM_GAIN = "R128_ALBUM_GAIN"

    /** Standard ReplayGain reference (89 dB SPL) and its R128/Opus equivalent. */
    const val REPLAYGAIN_TARGET_LUFS = -18.0
    const val R128_TARGET_LUFS = -23.0

    // Versioned analysis tags; the set must be complete for a file to be importable.
    const val ANALYSIS_VERSION = "SISTRUM_ANALYSIS_VERSION"
    const val FFMPEG_VERSION = "SISTRUM_FFMPEG_VERSION"
    const val AUBIO_VERSION = "SISTRUM_AUBIO_VERSION"
    const val MOOD_VERSION = "SISTRUM_MOOD_VERSION"
    const val FINGERPRINT = "SISTRUM_AUDIO_FINGERPRINT"
    const val LUFS = "SISTRUM_LUFS"
    const val LOUDNESS_RANGE = "SISTRUM_LRA"
    const val TRUE_PEAK = "SISTRUM_TRUE_PEAK"
    const val RMS = "SISTRUM_RMS"
    const val CREST = "SISTRUM_CREST"
    const val CENTROID = "SISTRUM_SPECTRAL_CENTROID"
    const val ROLLOFF = "SISTRUM_SPECTRAL_ROLLOFF"
    const val FLATNESS = "SISTRUM_SPECTRAL_FLATNESS"
    const val FLUX = "SISTRUM_SPECTRAL_FLUX"
    const val ZCR = "SISTRUM_ZCR"
    const val TEMPO = "SISTRUM_TEMPO"
    const val ONSET_VARIANCE = "SISTRUM_ONSET_VARIANCE"
    const val ENERGY = "SISTRUM_ENERGY"
    const val DANCEABILITY = "SISTRUM_DANCEABILITY"
    const val BRIGHTNESS = "SISTRUM_BRIGHTNESS"

    /** Every key [embeddedAnalysis] writes; used to detect incomplete sets. */
    val ANALYSIS_KEYS = listOf(
        ANALYSIS_VERSION, FFMPEG_VERSION, AUBIO_VERSION, MOOD_VERSION, FINGERPRINT,
        LUFS, LOUDNESS_RANGE, TRUE_PEAK, RMS, CREST, CENTROID, ROLLOFF, FLATNESS, FLUX,
        ZCR, TEMPO, ONSET_VARIANCE, ENERGY, DANCEABILITY, BRIGHTNESS,
    )

    enum class Format { MP3, VORBIS, OPUS, M4A, OTHER }

    fun formatOf(extension: String?): Format = when (extension?.lowercase()) {
        "mp3" -> Format.MP3
        "flac", "ogg", "oga" -> Format.VORBIS
        "opus" -> Format.OPUS
        "m4a", "mp4", "aac", "alac" -> Format.M4A
        else -> Format.OTHER
    }

    /** Linear peak as ReplayGain expects it (0..1), from the analyzer's dBFS peak. */
    fun linearPeak(truePeakDb: Double?): String =
        String.format(Locale.ROOT, "%.6f", 10.0.pow((truePeakDb ?: -120.0) / 20.0).coerceAtMost(1.0))

    /** ReplayGain gain in dB: reference minus measured loudness. */
    fun gainTag(loudnessLufs: Double?, target: Double = REPLAYGAIN_TARGET_LUFS): String =
        formatGain(target - (loudnessLufs ?: target))

    /** Opus stores R128 gain as an integer in Q7.8 dB units. */
    fun r128Gain(loudnessLufs: Double?, target: Double = R128_TARGET_LUFS): String =
        r128FromGain(target - (loudnessLufs ?: target))

    fun formatGain(gainDb: Double?): String =
        String.format(Locale.ROOT, "%.2f dB", gainDb ?: 0.0)

    fun r128FromGain(gainDb: Double?): String =
        kotlin.math.round((gainDb ?: 0.0) * 256.0).toInt().toString()

    /** Versioned analysis tags; values are locale-independent so they round-trip as text. */
    fun embeddedAnalysis(analysis: TrackAnalysisEntity): Map<String, String> = linkedMapOf(
        ANALYSIS_VERSION to analysis.analyzerVersion.toString(),
        FFMPEG_VERSION to analysis.ffmpegVersion.toString(),
        AUBIO_VERSION to analysis.aubioVersion.toString(),
        MOOD_VERSION to analysis.moodVersion.toString(),
        FINGERPRINT to (analysis.audioFingerprint ?: ""),
        LUFS to number(analysis.loudnessLufs),
        LOUDNESS_RANGE to number(analysis.loudnessRange),
        TRUE_PEAK to number(analysis.truePeak),
        RMS to number(analysis.rms),
        CREST to number(analysis.crest),
        CENTROID to number(analysis.spectralCentroid),
        ROLLOFF to number(analysis.spectralRolloff),
        FLATNESS to number(analysis.spectralFlatness),
        FLUX to number(analysis.spectralFlux),
        ZCR to number(analysis.zcr),
        TEMPO to number(analysis.tempo),
        ONSET_VARIANCE to number(analysis.onsetVariance),
        ENERGY to number(analysis.energy),
        DANCEABILITY to number(analysis.danceability),
        BRIGHTNESS to number(analysis.brightness),
    )

    private fun number(value: Double?): String {
        val v = value ?: 0.0
        return when {
            v.isNaN() -> "nan"
            v == Double.POSITIVE_INFINITY -> "inf"
            v == Double.NEGATIVE_INFINITY -> "-inf"
            else -> String.format(Locale.ROOT, "%.9f", v)
        }
    }

    fun tagsFor(
        format: Format,
        analysis: TrackAnalysisEntity,
        albumLoudnessLufs: Double? = null,
        albumTruePeakDb: Double? = null,
        existingKeys: Set<String> = emptySet(),
    ): Map<String, String> {
        val tags = linkedMapOf<String, String>()
        // Analysis tags are ours: always refreshed so a stale set never survives.
        tags.putAll(embeddedAnalysis(analysis))
        fun putGain(key: String, value: String) {
            if (key !in existingKeys) tags[key] = value
        }
        val trackGain = REPLAYGAIN_TARGET_LUFS - (analysis.loudnessLufs ?: REPLAYGAIN_TARGET_LUFS)
        val albumGain = albumLoudnessLufs?.let { REPLAYGAIN_TARGET_LUFS - it }
        when (format) {
            Format.OPUS -> {
                putGain(R128_TRACK_GAIN, r128FromGain(R128_TARGET_LUFS - (analysis.loudnessLufs ?: R128_TARGET_LUFS)))
                albumLoudnessLufs?.let { putGain(R128_ALBUM_GAIN, r128FromGain(R128_TARGET_LUFS - it)) }
            }
            Format.MP3, Format.VORBIS -> {
                putGain(TRACK_GAIN, formatGain(trackGain))
                putGain(TRACK_PEAK, linearPeak(analysis.truePeak))
                albumGain?.let {
                    putGain(ALBUM_GAIN, formatGain(it))
                    putGain(ALBUM_PEAK, linearPeak(albumTruePeakDb ?: analysis.truePeak))
                }
            }
            Format.M4A -> {
                putGain("----:com.apple.iTunes:$TRACK_GAIN", formatGain(trackGain))
                putGain("----:com.apple.iTunes:$TRACK_PEAK", linearPeak(analysis.truePeak))
                albumGain?.let {
                    putGain("----:com.apple.iTunes:$ALBUM_GAIN", formatGain(it))
                    putGain(
                        "----:com.apple.iTunes:$ALBUM_PEAK",
                        linearPeak(albumTruePeakDb ?: analysis.truePeak),
                    )
                }
            }
            Format.OTHER -> Unit
        }
        return tags
    }

    /**
     * Restores a stored analysis from file tags, e.g. after a clean install with no Room
     * history. Returns null for an incomplete set, a foreign/stale version, a different
     * audio payload or any value that does not parse.
     */
    fun fromTags(tags: Map<String, List<String>>, audioFingerprint: String?): TrackAnalysisEntity? {
        fun tag(key: String): String? = tags[key]?.firstOrNull()
        if (ANALYSIS_KEYS.any { tag(it).isNullOrBlank() }) return null
        if (!audioFingerprint.isNullOrBlank() && tag(FINGERPRINT) != audioFingerprint) return null
        val analyzer = tag(ANALYSIS_VERSION)?.toIntOrNull() ?: return null
        if (analyzer != AnalysisVersion.ANALYZER) return null
        if (tag(FFMPEG_VERSION)?.toIntOrNull() != AnalysisVersion.FFMPEG) return null
        if (tag(AUBIO_VERSION)?.toIntOrNull() != AnalysisVersion.AUBIO) return null
        if (tag(MOOD_VERSION)?.toIntOrNull() != AnalysisVersion.MOOD) return null
        fun value(key: String): Double? = tag(key)?.let { raw ->
            when (raw) {
                "inf" -> Double.POSITIVE_INFINITY
                "-inf" -> Double.NEGATIVE_INFINITY
                "nan" -> Double.NaN
                else -> raw.toDoubleOrNull()?.takeIf { it.isNaN() || it.isFinite() || it.isInfinite() }
            }
        }
        val lufs = value(LUFS) ?: return null
        val peak = value(TRUE_PEAK) ?: return null
        return TrackAnalysisEntity(
            trackId = "",
            loudnessLufs = lufs,
            loudnessRange = value(LOUDNESS_RANGE) ?: return null,
            truePeak = peak,
            rms = value(RMS) ?: return null,
            crest = value(CREST) ?: return null,
            spectralCentroid = value(CENTROID) ?: return null,
            spectralRolloff = value(ROLLOFF) ?: return null,
            spectralFlatness = value(FLATNESS) ?: return null,
            spectralFlux = value(FLUX) ?: return null,
            zcr = value(ZCR) ?: return null,
            tempo = value(TEMPO) ?: return null,
            onsetVariance = value(ONSET_VARIANCE) ?: return null,
            energy = value(ENERGY) ?: return null,
            danceability = value(DANCEABILITY) ?: return null,
            brightness = value(BRIGHTNESS) ?: return null,
            ffmpegVersion = AnalysisVersion.FFMPEG,
            aubioVersion = AnalysisVersion.AUBIO,
            analyzerVersion = AnalysisVersion.ANALYZER,
            moodVersion = AnalysisVersion.MOOD,
            audioFingerprint = audioFingerprint ?: tag(FINGERPRINT),
            source = "embedded",
            status = "COMPLETED",
            analyzedAt = null,
        )
    }

    private fun Double.pow(exponent: Double): Double = Math.pow(this, exponent)
}
