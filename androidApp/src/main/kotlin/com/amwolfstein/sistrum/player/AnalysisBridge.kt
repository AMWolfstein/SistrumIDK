package com.amwolfstein.sistrum.player

import android.os.ParcelFileDescriptor

/** JNI boundary for the approved analysis stack (aubio 0.4.9 + static FFTW 3.3.11). */
internal object AnalysisBridge {
    init {
        System.loadLibrary("sistrum_player")
    }

    /** Creates and destroys an aubio tempo object; false means the static link is broken. */
    external fun nativeTempoProbe(): Boolean

    /**
     * Decodes [fd] through the Airmedy `d859940c` avfilter graph and returns:
     * loudness LUFS, loudness range LU, true peak dBFS, RMS dBFS, crest factor,
     * spectral centroid, rolloff, flatness, flux, ZCR/s, tempo BPM, onset variance
     * (samples², as upstream). Energy/danceability/brightness are derived in Kotlin
     * from corpus percentiles, exactly like the desktop producer.
     * [startMs]/[endMs] bound one CUE segment; 0 and -1 analyse the whole file.
     * An empty array means the file could not be decoded.
     */
    external fun nativeAnalyzeFd(fd: Int, startMs: Long, endMs: Long): FloatArray

    /** Kept for the decoder descriptor convention used by the rest of the native bridge. */
    @Suppress("unused")
    private fun unusedDescriptor(descriptor: ParcelFileDescriptor) = descriptor
}
