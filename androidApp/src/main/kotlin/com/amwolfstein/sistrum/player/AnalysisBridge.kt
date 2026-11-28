package com.amwolfstein.sistrum.player

import android.os.ParcelFileDescriptor

/** JNI boundary for the approved analysis stack (aubio 0.4.9 + static FFTW 3.3.11). */
internal object AnalysisBridge {
    init {
        System.loadLibrary("sistrum_player")
    }

    /** Creates and destroys an aubio tempo object; false means the static link is broken. */
    external fun nativeTempoProbe(): Boolean

    /** Kept for the decoder descriptor convention used by the rest of the native bridge. */
    @Suppress("unused")
    private fun unusedDescriptor(descriptor: ParcelFileDescriptor) = descriptor
}
