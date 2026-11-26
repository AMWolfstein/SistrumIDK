package com.amwolfstein.sistrum.analysis

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Raw values used by Airmedy's corpus-relative mood formulas. */
data class SignalFeatures(
    val rmsDb: Double,
    val crest: Double,
    val spectralCentroid: Double,
    val spectralFlux: Double,
    val tempo: Double,
    val onsetVariance: Double,
    val loudnessRange: Double,
)

/**
 * Streaming 48 kHz stereo feature extractor. Tempo/onsets use a deterministic
 * spectral-flux estimator; unlike Airmedy Desktop they do not use aubio.
 */
class SignalFeatureExtractor {
    private val window = DoubleArray(WindowSize)
    private val previous = DoubleArray(WindowSize / 2 + 1)
    private val real = DoubleArray(WindowSize)
    private val imaginary = DoubleArray(WindowSize)
    private val onset = mutableListOf<Double>()
    private var fill = 0
    private var frameCount = 0
    private var audibleFrames = 0
    private var sampleCount = 0L
    private var squareSum = 0.0
    private var peak = 0.0
    private var centroidSum = 0.0
    private var fluxSum = 0.0

    /** Buffer contains interleaved stereo samples; count is in floats. */
    fun add(samples: FloatArray, count: Int) {
        require(count in 0..samples.size && count % 2 == 0)
        var index = 0
        while (index < count) {
            val value = (samples[index].toDouble() + samples[index + 1].toDouble()) * 0.5
            require(value.isFinite())
            squareSum += value * value
            peak = max(peak, kotlin.math.abs(value))
            sampleCount++
            window[fill++] = value
            if (fill == WindowSize) {
                analyzeWindow()
                window.copyInto(window, 0, HopSize, WindowSize)
                fill = WindowSize - HopSize
            }
            index += 2
        }
    }

    /** Null means there is insufficient reliable rhythm information for Mood Radio. */
    fun finish(loudnessRange: Double): SignalFeatures? {
        if (sampleCount < SampleRate * 8L || frameCount < 3 || audibleFrames == 0 || squareSum <= 0 || !loudnessRange.isFinite()) return null
        val rhythm = rhythm() ?: return null
        val rms = sqrt(squareSum / sampleCount)
        if (rms <= 0) return null
        return SignalFeatures(
            rmsDb = 20 * log10(rms),
            crest = peak / rms,
            spectralCentroid = centroidSum / audibleFrames,
            spectralFlux = fluxSum / frameCount,
            tempo = rhythm.first,
            onsetVariance = rhythm.second,
            loudnessRange = loudnessRange,
        ).takeIf { features ->
            listOf(features.rmsDb, features.crest, features.spectralCentroid, features.spectralFlux,
                features.tempo, features.onsetVariance, features.loudnessRange).all(Double::isFinite)
        }
    }

    private fun analyzeWindow() {
        for (i in 0 until WindowSize) {
            real[i] = window[i] * (0.5 - 0.5 * cos(2 * PI * i / (WindowSize - 1)))
            imaginary[i] = 0.0
        }
        fft(real, imaginary)
        var magnitudeSum = 0.0
        var frequencySum = 0.0
        var flux = 0.0
        for (i in previous.indices) {
            val magnitude = sqrt(real[i] * real[i] + imaginary[i] * imaginary[i])
            magnitudeSum += magnitude
            frequencySum += magnitude * i * SampleRate / WindowSize
            flux += max(0.0, magnitude - previous[i])
            previous[i] = magnitude
        }
        if (magnitudeSum > 1e-8) {
            centroidSum += frequencySum / magnitudeSum
            audibleFrames++
        }
        // Normalize by window length so sample-count and FFT size do not scale the score.
        val normalizedFlux = flux / WindowSize
        fluxSum += normalizedFlux
        if (onset.size < MaxOnsetFrames) onset.add(normalizedFlux)
        frameCount++
    }

    private fun rhythm(): Pair<Double, Double>? {
        // A bounded amount of audio prevents a pathological file from retaining unbounded memory.
        if (onset.size < 8 * SampleRate / HopSize) return null
        val mean = onset.average()
        if (mean <= 1e-8) return null
        val centered = onset.map { max(0.0, it - mean) }
        val energy = centered.sumOf { it * it }
        if (energy <= 1e-12) return null
        val fps = SampleRate.toDouble() / HopSize
        val minLag = (fps * 60 / 200).toInt().coerceAtLeast(1)
        val maxLag = (fps * 60 / 60).toInt().coerceAtMost(centered.size / 2)
        var bestLag = 0
        var best = 0.0
        for (lag in minLag..maxLag) {
            var correlation = 0.0
            for (i in lag until centered.size) correlation += centered[i] * centered[i - lag]
            val score = correlation / energy
            if (score > best) {
                best = score
                bestLag = lag
            }
        }
        if (bestLag == 0 || best < 0.15) return null
        val peaks = mutableListOf<Int>()
        val threshold = mean * 1.5
        val minimumGap = (fps * 0.12).toInt().coerceAtLeast(1)
        for (i in 1 until onset.lastIndex) {
            if (onset[i] <= threshold || onset[i] < onset[i - 1] || onset[i] < onset[i + 1]) continue
            if (peaks.isEmpty() || i - peaks.last() >= minimumGap) peaks.add(i)
        }
        if (peaks.size < 4) return null
        val intervals = peaks.zipWithNext { a, b -> (b - a) / fps * 44_100.0 }
        val intervalMean = intervals.average()
        val variance = intervals.sumOf { (it - intervalMean) * (it - intervalMean) } / intervals.size
        return (60 * fps / bestLag) to variance
    }

    private companion object {
        const val SampleRate = 48_000
        const val WindowSize = 2048
        const val HopSize = 1024
        const val MaxOnsetFrames = 30 * 60 * SampleRate / HopSize

        fun fft(real: DoubleArray, imaginary: DoubleArray) {
            val size = real.size
            var target = 0
            for (i in 1 until size) {
                var bit = size shr 1
                while (target and bit != 0) {
                    target = target xor bit
                    bit = bit shr 1
                }
                target = target xor bit
                if (i < target) {
                    val r = real[i]; real[i] = real[target]; real[target] = r
                    val im = imaginary[i]; imaginary[i] = imaginary[target]; imaginary[target] = im
                }
            }
            var length = 2
            while (length <= size) {
                val angle = -2 * PI / length
                val stepReal = cos(angle)
                val stepImaginary = sin(angle)
                for (start in 0 until size step length) {
                    var wr = 1.0
                    var wi = 0.0
                    for (offset in 0 until length / 2) {
                        val even = start + offset
                        val odd = even + length / 2
                        val tr = wr * real[odd] - wi * imaginary[odd]
                        val ti = wr * imaginary[odd] + wi * real[odd]
                        real[odd] = real[even] - tr
                        imaginary[odd] = imaginary[even] - ti
                        real[even] += tr
                        imaginary[even] += ti
                        val next = wr * stepReal - wi * stepImaginary
                        wi = wr * stepImaginary + wi * stepReal
                        wr = next
                    }
                }
                length *= 2
            }
        }
    }
}

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
