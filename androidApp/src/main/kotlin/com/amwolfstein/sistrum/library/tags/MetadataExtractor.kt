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
}

data class ExtractedMetadata(
    val metadata: TagMetadata,
    val artwork: ByteArray? = null,
)

class MetadataExtractor(private val context: Context) {

    suspend fun read(uri: Uri, includeArtwork: Boolean = false): Result<ExtractedMetadata> =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                    if (isSeekable(descriptor)) {
                        readDescriptor(descriptor, includeArtwork)
                    } else {
                        // Cloud providers can hand out pipes; TagLib needs a seekable file, so stage it.
                        val staged = stage(uri)
                        try {
                            ParcelFileDescriptor.open(staged, ParcelFileDescriptor.MODE_READ_ONLY).use {
                                readDescriptor(it, includeArtwork)
                            }
                        } finally {
                            staged.delete()
                        }
                    }
                } ?: error("Cannot open $uri")
            }
        }

    private fun readDescriptor(descriptor: ParcelFileDescriptor, includeArtwork: Boolean): ExtractedMetadata {
        val metadata = TagMetadataJson.parse(TagLibBridge.nativeReadMetadata(descriptor.fd))
        val artwork = if (includeArtwork && metadata.hasArtwork) {
            TagLibBridge.nativeReadArtwork(descriptor.fd)
        } else {
            null
        }
        return ExtractedMetadata(metadata, artwork)
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
