package com.amwolfstein.sistrum.library.analysis

/**
 * Maps analysis results to the standard per-format ReplayGain/R128 tags.
 * Existing keys are never overwritten; [existingKeys] is the set already present on the file.
 */
object AnalysisTagCodec {
    const val TRACK_GAIN = "REPLAYGAIN_TRACK_GAIN"
    const val TRACK_PEAK = "REPLAYGAIN_TRACK_PEAK"
    const val ALBUM_GAIN = "REPLAYGAIN_ALBUM_GAIN"
    const val ALBUM_PEAK = "REPLAYGAIN_ALBUM_PEAK"
    const val R128_TRACK_GAIN = "R128_TRACK_GAIN"
    const val R128_ALBUM_GAIN = "R128_ALBUM_GAIN"

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
        String.format(java.util.Locale.ROOT, "%.6f", 10.0.pow((truePeakDb ?: -120.0) / 20.0).coerceAtMost(1.0))

    fun gainTag(loudnessLufs: Double?, target: Double = -18.0): String =
        String.format(java.util.Locale.ROOT, "%.2f dB", (target - (loudnessLufs ?: target)))

    /** Opus stores R128 gain as an integer in Q7.8 dB units. */
    fun r128Gain(loudnessLufs: Double?, target: Double = -23.0): String =
        r128FromGain(target - (loudnessLufs ?: target))

    fun formatGain(gainDb: Double?): String =
        String.format(java.util.Locale.ROOT, "%.2f dB", gainDb ?: 0.0)

    fun r128FromGain(gainDb: Double?): String =
        kotlin.math.round((gainDb ?: 0.0) * 256.0).toInt().toString()

    fun tagsFor(
        format: Format,
        trackGainDb: Double?,
        truePeakDb: Double?,
        albumGainDb: Double? = null,
        existingKeys: Set<String> = emptySet(),
    ): Map<String, String> {
        val tags = linkedMapOf<String, String>()
        fun put(key: String, value: String) {
            if (key !in existingKeys) tags[key] = value
        }
        when (format) {
            Format.OPUS -> {
                put(R128_TRACK_GAIN, r128FromGain(trackGainDb))
                albumGainDb?.let { put(R128_ALBUM_GAIN, r128FromGain(it)) }
            }
            Format.MP3, Format.VORBIS -> {
                put(TRACK_GAIN, formatGain(trackGainDb))
                put(TRACK_PEAK, linearPeak(truePeakDb))
                albumGainDb?.let {
                    put(ALBUM_GAIN, formatGain(it))
                    put(ALBUM_PEAK, linearPeak(truePeakDb))
                }
            }
            Format.M4A -> {
                put("----:com.apple.iTunes:$TRACK_GAIN", formatGain(trackGainDb))
                put("----:com.apple.iTunes:$TRACK_PEAK", linearPeak(truePeakDb))
                albumGainDb?.let {
                    put("----:com.apple.iTunes:$ALBUM_GAIN", formatGain(it))
                    put("----:com.apple.iTunes:$ALBUM_PEAK", linearPeak(truePeakDb))
                }
            }
            Format.OTHER -> Unit
        }
        return tags
    }

    private fun Double.pow(exponent: Double): Double = Math.pow(this, exponent)
}
