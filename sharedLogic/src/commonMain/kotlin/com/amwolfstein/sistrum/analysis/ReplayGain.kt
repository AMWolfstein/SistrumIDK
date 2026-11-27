package com.amwolfstein.sistrum.analysis

/** ReplayGain 2.0 track result with separate linear sample peak and dBTP. */
data class ReplayGainTrack(
    val loudnessLufs: Double,
    val gainDb: Double,
    val samplePeak: Double,
    val truePeakDb: Double,
)

fun replayGainTrack(loudnessLufs: Double, samplePeak: Double, truePeakDb: Double): ReplayGainTrack? {
    if (!loudnessLufs.isFinite() || !samplePeak.isFinite() || samplePeak <= 0 || !truePeakDb.isFinite()) {
        return null
    }
    return ReplayGainTrack(loudnessLufs, -18.0 - loudnessLufs, samplePeak, truePeakDb)
}
