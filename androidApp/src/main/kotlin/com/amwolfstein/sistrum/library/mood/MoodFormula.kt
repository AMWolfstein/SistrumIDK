package com.amwolfstein.sistrum.library.mood

import kotlin.math.exp

/**
 * Corpora-relative mood formulas ported from Airmedy `d859940c`
 * (internal/app/analysis/mood/normalizer.go, formulas.go). Pure Kotlin so the
 * same code runs on host tests and on device.
 */
object MoodFormula {
    /** Sigmoid steepness, locked upstream at 2.5. */
    const val STEEPNESS = 2.5

    /** Upstream energy clamps tempo at 180 BPM before normalizing. */
    const val ENERGY_TEMPO_CAP = 180.0

    private const val TEMPO_SCORE_LOW = 60.0
    private const val TEMPO_SCORE_PEAK = 115.0
    private const val TEMPO_SCORE_HIGH = 180.0

    /** One track's raw DSP features; the only inputs the formulas consume. */
    data class Raw(
        val rms: Double,
        val spectralCentroid: Double,
        val spectralFlux: Double,
        val tempo: Double,
        val crest: Double,
        val onsetVariance: Double,
        val loudnessRange: Double,
    )

    data class Percentile(val p1: Double, val p5: Double, val p50: Double, val p95: Double, val p99: Double)

    /** The seven features upstream recomputes percentiles for. */
    val FEATURES = listOf("rms", "spectral_centroid", "spectral_flux", "tempo", "crest", "onset_variance", "loudness_range")

    /** Linear-interpolated percentiles over a corpus, matching numpy's default. */
    fun percentiles(corpus: List<Raw>): Map<String, Percentile> {
        if (corpus.isEmpty()) return emptyMap()
        return mapOf(
            "rms" to percentile(corpus.map { it.rms }),
            "spectral_centroid" to percentile(corpus.map { it.spectralCentroid }),
            "spectral_flux" to percentile(corpus.map { it.spectralFlux }),
            "tempo" to percentile(corpus.map { it.tempo }),
            "crest" to percentile(corpus.map { it.crest }),
            "onset_variance" to percentile(corpus.map { it.onsetVariance }),
            "loudness_range" to percentile(corpus.map { it.loudnessRange }),
        )
    }

    fun percentile(values: List<Double>): Percentile {
        val sorted = values.sorted()
        return Percentile(
            p1 = interpolate(sorted, 0.01),
            p5 = interpolate(sorted, 0.05),
            p50 = interpolate(sorted, 0.50),
            p95 = interpolate(sorted, 0.95),
            p99 = interpolate(sorted, 0.99),
        )
    }

    private fun interpolate(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return 0.0
        if (sorted.size == 1) return sorted[0]
        val rank = p * (sorted.size - 1)
        val lo = rank.toInt()
        val hi = lo + 1
        if (hi >= sorted.size) return sorted.last()
        val frac = rank - lo
        return sorted[lo] + frac * (sorted[hi] - sorted[lo])
    }

    /** Clamp to [p1, p99], then sigmoid over half the P5–P95 spread; 0.5 when degenerate. */
    fun normalize(value: Double, p: Percentile?): Double {
        if (p == null) return 0.5
        val clamped = value.coerceIn(minOf(p.p1, p.p99), maxOf(p.p1, p.p99))
        val spread = (p.p95 - p.p5) / 2.0
        if (spread == 0.0) return 0.5
        val z = (clamped - p.p50) / spread
        return 1.0 / (1.0 + exp(-STEEPNESS * z))
    }

    fun energy(raw: Raw, set: Map<String, Percentile>): Double {
        val tempo = minOf(raw.tempo, ENERGY_TEMPO_CAP)
        return 0.32 * normalize(raw.rms, set["rms"]) +
            0.23 * normalize(raw.spectralCentroid, set["spectral_centroid"]) +
            0.17 * normalize(raw.spectralFlux, set["spectral_flux"]) +
            0.18 * normalize(tempo, set["tempo"]) +
            0.10 * (1.0 - normalize(raw.crest, set["crest"]))
    }

    fun danceability(raw: Raw, set: Map<String, Percentile>): Double =
        0.45 * tempoScore(raw.tempo) +
            0.30 * (1.0 - normalize(raw.onsetVariance, set["onset_variance"])) +
            0.15 * (1.0 - normalize(raw.crest, set["crest"])) +
            0.10 * (1.0 - normalize(raw.loudnessRange, set["loudness_range"]))

    fun brightness(raw: Raw, set: Map<String, Percentile>): Double =
        normalize(raw.spectralCentroid, set["spectral_centroid"])

    /** Triangular tempo score: 0 at 60 and 180 BPM, 1 at 115 BPM. */
    fun tempoScore(bpm: Double): Double = when {
        bpm <= TEMPO_SCORE_LOW || bpm >= TEMPO_SCORE_HIGH -> 0.0
        bpm <= TEMPO_SCORE_PEAK -> (bpm - TEMPO_SCORE_LOW) / (TEMPO_SCORE_PEAK - TEMPO_SCORE_LOW)
        else -> (TEMPO_SCORE_HIGH - bpm) / (TEMPO_SCORE_HIGH - TEMPO_SCORE_PEAK)
    }

    data class Derived(val energy: Double, val danceability: Double, val brightness: Double)

    fun derive(raw: Raw, set: Map<String, Percentile>): Derived = Derived(
        energy = energy(raw, set),
        danceability = danceability(raw, set),
        brightness = brightness(raw, set),
    )
}
