package com.amwolfstein.sistrum.metadata

import android.content.Context
import android.content.ContentUris
import android.provider.MediaStore
import android.media.MediaScannerConnection
import android.system.Os
import com.kyant.taglib.Picture
import com.amwolfstein.sistrum.sync.EmbeddedTagReader
import com.amwolfstein.sistrum.sync.LocalLibraryRuntime
import com.amwolfstein.sistrum.sync.fileSha256
import com.amwolfstein.sistrum.ui.screens.libraryScanMutex
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class EditTarget(val trackId: String, val path: String, val mediaId: Long, val contentHash: String)

internal suspend fun prepareTrackEdit(context: Context, trackId: String): EditTarget = withContext(Dispatchers.IO) {
    val prior = LocalLibraryRuntime.libraryStore().priorTrackScanState(trackId)
        ?: error("Track no longer in the library")
    require(prior.contentHash.isNotBlank() && prior.absolutePath.isNotBlank() && prior.sourceMediaId > 0)
    require(fileSha256(File(prior.absolutePath)) == prior.contentHash)
    EditTarget(trackId, prior.absolutePath, prior.sourceMediaId, prior.contentHash).also { verifyMediaUri(context, it) }
}

/** The approved MediaStore row must still name the exact scanned source. */
internal fun verifyMediaUri(context: Context, target: EditTarget) {
    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, target.mediaId)
    val path = context.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
    requireMediaPath(target.path, path)
}

internal fun requireMediaPath(expected: String, actual: String?) {
    require(actual != null && File(actual).canonicalPath == File(expected).canonicalPath) {
        "MediaStore no longer identifies the approved track"
    }
}

/**
 * The source remains intact until a same-directory atomic rename. A journal ties
 * the two validated content hashes to the existing library ID for the next scan.
 */
internal suspend fun editTrack(context: Context, target: EditTarget, update: Mp3TagUpdate) =
    withContext(Dispatchers.IO) {
        libraryScanMutex.withLock {
            val trackId = target.trackId
            val prior = LocalLibraryRuntime.libraryStore().priorTrackScanState(trackId)
                ?: error("Track no longer in the library")
            verifyMediaUri(context, target)
            require(prior.contentHash == target.contentHash && prior.absolutePath == target.path && prior.sourceMediaId == target.mediaId) {
                "The approved track changed"
            }
            require(LocalLibraryRuntime.trackEditJournal.pending().none { it.trackId == trackId }) {
                "A previous edit is waiting for a library scan"
            }
            val source = File(prior.absolutePath)
            val parent = source.parentFile ?: error("File has no parent folder")
            require(source.isFile && source.extension.lowercase() in setOf("mp3", "flac") && parent.canWrite()) {
                "This file or location does not support safe replacement"
            }
            require(fileSha256(source) == prior.contentHash) { "File changed since the last scan" }
            val temporary = File.createTempFile(".sistrum-edit-", ".${source.extension}", parent)
            var installed = false
            var journalWritten = false
            try {
                source.inputStream().use { input ->
                    temporary.outputStream().use { output -> input.copyTo(output) }
                }
                val original = TagMetadata.read(source, readPictures = true)
                val changes = buildMap<String, List<String>?> {
                    update.title?.let { put("TITLE", listOf(it)) }
                    update.artist?.let { put("ARTIST", listOf(it)) }
                    update.album?.let { put("ALBUM", listOf(it)) }
                    update.lyrics?.let { put("LYRICS", listOf(it)) }
                    putAll(update.additionalProperties)
                }
                val properties = mergeTagProperties(original.properties, changes)
                val pictures = update.pictures ?: update.artwork?.let { artwork ->
                    listOf(Picture(artwork, "", "Front Cover", requireNotNull(update.artworkMime))) +
                        original.pictures.filterNot { it.pictureType == "Front Cover" }
                }
                TagMetadata.write(temporary, properties.takeIf { changes.isNotEmpty() }, pictures)
                val written = TagMetadata.read(temporary, readPictures = true)
                check(written.properties == properties) { "Metadata verification failed" }
                check(written.pictures == (pictures ?: original.pictures)) { "Artwork verification failed" }
                if (source.extension.equals("mp3", true)) verifyMp3Preservation(source, temporary, update)
                else verifyFlacPreservation(source, temporary, update)
                if (update.title != null || update.artist != null || update.album != null) {
                    val reader = android.media.MediaMetadataRetriever()
                    try {
                        reader.setDataSource(temporary.path)
                        update.title?.let { require(reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE) == it) }
                        update.artist?.let { require(reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST) == it) }
                        update.album?.let { require(reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM) == it) }
                    } finally {
                        reader.release()
                    }
                }
                update.lyrics?.let { require(EmbeddedTagReader.embeddedLyricsText(temporary.path) == it.trim()) }
                update.artwork?.let { require(EmbeddedTagReader.embeddedArtworkBytes(temporary.path)?.contentEquals(it) == true) }
                val newHash = fileSha256(temporary)
                require(fileSha256(source) == prior.contentHash) { "File changed during the edit" }
                verifyMediaUri(context, target)
                val mode = Os.stat(source.path).st_mode and 0x1ff
                Os.chmod(temporary.path, mode)
                val edit = TrackEdit(trackId, source.absolutePath, prior.contentHash, newHash, audioEquivalent = true)
                LocalLibraryRuntime.trackEditJournal.put(edit)
                journalWritten = true
                Files.move(temporary.toPath(), source.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                installed = true
                val scanned = CompletableDeferred<Unit>()
                MediaScannerConnection.scanFile(context.applicationContext, arrayOf(source.path), null) { _, _ -> scanned.complete(Unit) }
                withTimeoutOrNull(10_000) { scanned.await() }
            } finally {
                temporary.delete()
                if (journalWritten && !installed) LocalLibraryRuntime.trackEditJournal.remove(listOf(trackId))
            }
        }
    }
