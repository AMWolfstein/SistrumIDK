package com.amwolfstein.sistrum.analysis

import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File

/** A separately owned FFmpeg demuxer and decoder, with no playback or AAudio state. */
internal class OfflineAudioDecoder : Closeable {
    private val lock = Any()
    private var handle: Long = nativeCreate()

    fun open(file: File) {
        val current = synchronized(lock) { checkNotNull(handle.takeIf { it != 0L }) }
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            nativeOpen(current, descriptor.detachFd())
        }
    }

    /** Airmedy filter and aubio results in the native bridge's documented order. */
    fun analyze(): DoubleArray {
        val current = synchronized(lock) { checkNotNull(handle.takeIf { it != 0L }) }
        return nativeAnalyze(current)
    }

    /** Returns integrated LUFS, linear sample peak, and true peak dBTP. */
    fun replayGain(): DoubleArray {
        val current = synchronized(lock) { checkNotNull(handle.takeIf { it != 0L }) }
        return nativeReplayGain(current)
    }

    fun cancel() = synchronized(lock) {
        if (handle != 0L) nativeCancel(handle)
    }

    override fun close() = synchronized(lock) {
        val current = handle
        handle = 0L
        if (current != 0L) nativeClose(current)
    }

    private companion object {
        init { System.loadLibrary("sistrum_player") }

        @JvmStatic private external fun nativeCreate(): Long
        @JvmStatic private external fun nativeOpen(handle: Long, fd: Int)
        @JvmStatic private external fun nativeAnalyze(handle: Long): DoubleArray
        @JvmStatic private external fun nativeReplayGain(handle: Long): DoubleArray
        @JvmStatic private external fun nativeCancel(handle: Long)
        @JvmStatic private external fun nativeClose(handle: Long)
    }
}
