package com.amwolfstein.sistrum.library.tags

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Thin JNI surface over TagLib 2.3.2. Both calls accept an already-open file descriptor. */
object TagLibBridge {
    init {
        System.loadLibrary("sistrum_player")
    }

    external fun nativeReadMetadata(fd: Int): String

    external fun nativeReadArtwork(fd: Int): ByteArray?

    external fun nativeWriteTags(
        fd: Int,
        title: String?,
        artist: String?,
        album: String?,
        albumArtist: String?,
        genre: String?,
        composer: String?,
        comment: String?,
        year: Int,
        track: Int,
        disc: Int,
        isrc: String?,
        label: String?,
        copyright: String?,
    ): Boolean

    external fun nativeWriteArtwork(fd: Int, data: ByteArray, mimeType: String?, description: String?): Boolean

    /** Demuxer-header fields TagLib does not expose: codec name and bits per sample. */
    external fun nativeProbeAudio(fd: Int): String

    /** Replaces arbitrary text properties (ReplayGain/R128), preserving unrelated tags. */
    external fun nativeWriteProperties(fd: Int, keys: Array<String>, values: Array<String>): Boolean

    /**
     * Versioned fingerprint of decoded PCM (`pcm-sha256-v1:…`) for one file or CUE segment.
     * Tag and container edits keep it stable; re-encoded audio changes it.
     */
    external fun nativeAudioFingerprint(fd: Int, startMs: Long, endMs: Long): String?
}

fun readRawTags(fd: Int): Map<String, List<String>> =
    TagMetadataJson.parse(TagLibBridge.nativeReadMetadata(fd)).rawTags

data class ExtractedMetadata(
    val metadata: TagMetadata,
    val artwork: ByteArray? = null,
    val fingerprint: String? = null,
)

class MetadataExtractor(private val context: Context) {

    suspend fun read(
        uri: Uri,
        includeArtwork: Boolean = false,
        includeFingerprint: Boolean = false,
        startMs: Long = 0,
        endMs: Long = -1,
    ): Result<ExtractedMetadata> =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                    if (isSeekable(descriptor)) {
                        readDescriptor(descriptor, includeArtwork, includeFingerprint, startMs, endMs)
                    } else {
                        // Cloud providers can hand out pipes; TagLib needs a seekable file, so stage it.
                        val staged = stage(uri)
                        try {
                            ParcelFileDescriptor.open(staged, ParcelFileDescriptor.MODE_READ_ONLY).use {
                                readDescriptor(it, includeArtwork, includeFingerprint, startMs, endMs)
                            }
                        } finally {
                            staged.delete()
                        }
                    }
                } ?: error("Cannot open $uri")
            }
        }

    private fun readDescriptor(
        descriptor: ParcelFileDescriptor,
        includeArtwork: Boolean,
        includeFingerprint: Boolean,
        startMs: Long,
        endMs: Long,
    ): ExtractedMetadata {
        val parsed = TagMetadataJson.parse(TagLibBridge.nativeReadMetadata(descriptor.fd))
        // The probe runs after the TagLib stream is closed; both share the same descriptor.
        val probe = TagMetadataJson.parse(runCatching { TagLibBridge.nativeProbeAudio(descriptor.fd) }.getOrDefault("{}"))
        val metadata = parsed.copy(
            codec = parsed.codec ?: probe.codec,
            bitsPerSample = parsed.bitsPerSample ?: probe.bitsPerSample,
        )
        val artwork = if (includeArtwork && metadata.hasArtwork) {
            TagLibBridge.nativeReadArtwork(descriptor.fd)
        } else {
            null
        }
        val fingerprint = if (includeFingerprint) {
            TagLibBridge.nativeAudioFingerprint(descriptor.fd, startMs, endMs)
        } else {
            null
        }
        return ExtractedMetadata(metadata, artwork, fingerprint)
    }

    private fun isSeekable(descriptor: ParcelFileDescriptor): Boolean =
        runCatching { Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_CUR) }.isSuccess

    private fun stage(uri: Uri): File {
        val file = File.createTempFile("taglib-", ".bin", context.cacheDir)
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Cannot open $uri")
        return file
    }
}
