package com.amwolfstein.sistrum.analysis

import kotlin.math.min

data class SignalFeatures(
    val rmsDb: Double,
    val crest: Double,
    val spectralCentroid: Double,
    val spectralFlux: Double,
    val tempo: Double,
    val onsetVariance: Double,
    val loudnessRange: Double,
)


data class MoodPercentile(val p1: Double, val p5: Double, val p50: Double, val p95: Double, val p99: Double)
data class MoodValues(val energy: Double, val danceability: Double, val brightness: Double)

/** Implements Airmedy's pinned corpus-percentile formulas for measured features. */
class MoodModel(corpus: List<SignalFeatures>) {
    private val rms = percentile(corpus.map { it.rmsDb })
    private val centroid = percentile(corpus.map { it.spectralCentroid })
    private val flux = percentile(corpus.map { it.spectralFlux })
    private val tempo = percentile(corpus.map { it.tempo })
    private val crest = percentile(corpus.map { it.crest })
    private val onset = percentile(corpus.map { it.onsetVariance })
    private val range = percentile(corpus.map { it.loudnessRange })

    fun derive(features: SignalFeatures): MoodValues {
        val rmsValue = normal(features.rmsDb, rms)
        val centroidValue = normal(features.spectralCentroid, centroid)
        val fluxValue = normal(features.spectralFlux, flux)
        val tempoValue = normal(min(features.tempo, 180.0), tempo)
        val crestValue = normal(features.crest, crest)
        val onsetValue = normal(features.onsetVariance, onset)
        val rangeValue = normal(features.loudnessRange, range)
        val tempoScore = when {
            features.tempo <= 60 || features.tempo >= 180 -> 0.0
            features.tempo <= 115 -> (features.tempo - 60) / 55
            else -> (180 - features.tempo) / 65
        }
        return MoodValues(
            energy = .32 * rmsValue + .23 * centroidValue + .17 * fluxValue + .18 * tempoValue + .10 * (1 - crestValue),
            danceability = .45 * tempoScore + .30 * (1 - onsetValue) + .15 * (1 - crestValue) + .10 * (1 - rangeValue),
            brightness = centroidValue,
        )
    }

    private fun percentile(values: List<Double>): MoodPercentile {
        require(values.isNotEmpty())
        val sorted = values.sorted()
        fun at(p: Double): Double {
            val rank = p * (sorted.size - 1)
            val lo = rank.toInt()
            val hi = min(lo + 1, sorted.lastIndex)
            return sorted[lo] + (rank - lo) * (sorted[hi] - sorted[lo])
        }
        return MoodPercentile(at(.01), at(.05), at(.50), at(.95), at(.99))
    }

    private fun normal(value: Double, p: MoodPercentile): Double {
        val spread = (p.p95 - p.p5) / 2
        if (spread == 0.0) return .5
        val z = (value.coerceIn(p.p1, p.p99) - p.p50) / spread
        return 1 / (1 + kotlin.math.exp(-2.5 * z))
    }
}

fun deriveMood(features: SignalFeatures, corpus: List<SignalFeatures>): MoodValues =
    MoodModel(corpus).derive(features)
