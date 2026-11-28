package com.amwolfstein.sistrum.library.tags

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import com.amwolfstein.sistrum.library.db.LibraryDao
import com.amwolfstein.sistrum.library.db.LibraryItemEntity
import com.amwolfstein.sistrum.library.db.TagWriteJournalEntity
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class TagEdit(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val genre: String? = null,
    val composer: String? = null,
    val comment: String? = null,
    val year: Int? = null,
    val track: Int? = null,
    val disc: Int? = null,
    val isrc: String? = null,
    val label: String? = null,
    val copyright: String? = null,
    val artwork: ByteArray? = null,
    val artworkMime: String? = "image/jpeg",
    /** Embedded lyrics; written through the same staged/journaled path as other tags. */
    val lyrics: String? = null,
)

sealed class TagWriteResult {
    data class Success(val metadata: TagMetadata) : TagWriteResult()
    data class Failure(val reason: String, val recovered: Boolean) : TagWriteResult()
}

/**
 * Crash-safe tag writing:
 * 1. copy the original and a staged working copy into app cache
 * 2. write tags/artwork to the staged copy and verify metadata + audio properties
 * 3. journal the operation, overwrite the real document, verify, then clear the journal
 *
 * If the process dies mid-write, [recoverIncompleteWrites] restores the original bytes from the
 * cache before the next attempt, so a document is never left half-written.
 */
class TagWriter(
    private val context: Context,
    private val dao: LibraryDao,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val extractor = MetadataExtractor(context)

    suspend fun write(item: LibraryItemEntity, edit: TagEdit): TagWriteResult = writeLock.withLock {
        withContext(Dispatchers.IO) { writeLocked(item, edit) }
    }

    private suspend fun writeLocked(item: LibraryItemEntity, edit: TagEdit): TagWriteResult {
        val uri = runCatching { Uri.parse(item.fileUri) }.getOrNull()
            ?: return TagWriteResult.Failure("Invalid file URI", recovered = true)
        val workDir = File(context.cacheDir, "tag-writes/${item.id}-${now()}")
        val original = File(workDir, "original.bin")
        val staged = File(workDir, "staged.bin")
        val journalId = "tw_${item.id}_${now()}"
        var journalState = "STAGING"
        var originalReady = false
        var journalCreated = false
        return try {
            workDir.mkdirs()
            copyFromUri(uri, original)
            originalReady = true
            original.copyTo(staged, overwrite = true)
            val before = readFileMetadata(original)
            val beforeFingerprint = audioFingerprint(original)
            val written = applyEdits(staged, edit)
            if (!written) {
                cleanup(workDir)
                return TagWriteResult.Failure("The file format is not writable", recovered = true)
            }
            val after = readFileMetadata(staged)
            val afterFingerprint = audioFingerprint(staged)
            if (beforeFingerprint != null && afterFingerprint != beforeFingerprint) {
                cleanup(workDir)
                return TagWriteResult.Failure("Audio payload changed", recovered = true)
            }
            verifyEdit(before, after, edit)?.let { reason ->
                cleanup(workDir)
                return TagWriteResult.Failure(reason, recovered = true)
            }
            upsertJournal(
                TagWriteJournalEntity(
                    id = journalId,
                    trackId = item.id,
                    operation = "TAGS",
                    stagedPath = "$original\u0000$staged",
                    originalFingerprint = sha256(original),
                    state = journalState,
                    createdAt = now(),
                    updatedAt = now(),
                ),
            )
            journalCreated = true
            journalState = "WRITING"
            upsertJournal(currentJournal(journalId, item.id, original, staged, journalState))
            writeToUri(uri, staged)
            val confirmed = extractor.read(uri).getOrNull()?.metadata
                ?: return failAndRestore(journalId, item.id, uri, original, workDir, "Could not verify the written file")
            verifyEdit(after, confirmed, edit)?.let { reason ->
                return failAndRestore(journalId, item.id, uri, original, workDir, reason)
            }
            upsertJournal(currentJournal(journalId, item.id, original, staged, "DONE"))
            cleanup(workDir)
            TagWriteResult.Success(confirmed)
        } catch (cancelled: CancellationException) {
            if (journalState == "WRITING" && originalReady) restore(uri, original)
            throw cancelled
        } catch (failure: Exception) {
            val destinationTouched = journalState == "WRITING" && originalReady
            val recovered = when {
                destinationTouched -> restore(uri, original)
                else -> true // the destination was never opened for writing
            }
            if (journalCreated) {
                upsertJournal(
                    currentJournal(journalId, item.id, original, staged, if (recovered) "RECOVERED" else "FAILED")
                        .copy(error = failure.message),
                )
            }
            // Keep the verified backup on disk when recovery failed so the next launch can retry.
            if (recovered) cleanup(workDir)
            TagWriteResult.Failure(failure.message ?: "Tag write failed", recovered)
        }
    }

    /** Applies one edit to several tracks; a failure is isolated to that track. */
    suspend fun writeBatch(items: List<LibraryItemEntity>, edit: TagEdit): Map<String, TagWriteResult> =
        items.associate { item -> item.id to write(item, edit) }

    /** Called at app start; restores any document whose write did not reach DONE. */
    suspend fun recoverIncompleteWrites() = writeLock.withLock {
        withContext(Dispatchers.IO) {
            dao.incompleteTagWrites().forEach { journal ->
                val parts = journal.stagedPath.split('\u0000')
                val original = parts.getOrNull(0)?.let(::File)
                val staged = parts.getOrNull(1)?.let(::File)
                val uri = dao.itemById(journal.trackId)?.let { runCatching { Uri.parse(it.fileUri) }.getOrNull() }
                // A staged-only journal never touched the destination; there is nothing to restore.
                val needsRestore = journal.state == "WRITING" || journal.state == "FAILED"
                val restored = if (needsRestore && original != null && original.isFile && uri != null) {
                    restore(uri, original)
                } else {
                    !needsRestore
                }
                dao.upsertTagWriteJournal(
                    journal.copy(
                        state = when {
                            restored -> "RECOVERED"
                            else -> "FAILED"
                        },
                        updatedAt = now(),
                        error = if (restored) null else "Original copy unavailable or restore failed",
                    ),
                )
                if (restored) {
                    listOfNotNull(original, staged).mapNotNull { it.parentFile }.distinct().forEach {
                        runCatching { it.deleteRecursively() }
                    }
                }
            }
        }
    }

    private fun applyEdits(file: File, edit: TagEdit): Boolean {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).use { descriptor ->
            val tags = TagLibBridge.nativeWriteTags(
                descriptor.fd,
                edit.title,
                edit.artist,
                edit.album,
                edit.albumArtist,
                edit.genre,
                edit.composer,
                edit.comment,
                edit.year ?: 0,
                edit.track ?: 0,
                edit.disc ?: 0,
                edit.isrc,
                edit.label,
                edit.copyright,
            )
            if (!tags) return false
            val artwork = edit.artwork
            if (artwork != null) {
                if (!TagLibBridge.nativeWriteArtwork(descriptor.fd, artwork, edit.artworkMime, "Cover")) {
                    return false
                }
            }
            if (edit.lyrics != null) {
                if (!TagLibBridge.nativeWriteProperties(
                        descriptor.fd,
                        arrayOf("LYRICS"),
                        arrayOf(edit.lyrics),
                    )
                ) {
                    return false
                }
            }
            return true
        }
    }

    private suspend fun readFileMetadata(file: File): TagMetadata =
        extractor.read(Uri.fromFile(file)).getOrNull()?.metadata ?: TagMetadata()

    private fun audioFingerprint(file: File): String? =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            TagLibBridge.nativeAudioFingerprint(descriptor.fd, 0, -1)
        }

    private fun verifyEdit(before: TagMetadata, after: TagMetadata, edit: TagEdit): String? {
        if (before.durationMs != after.durationMs) return "Audio duration changed"
        if (before.sampleRate != null && after.sampleRate != before.sampleRate) return "Sample rate changed"
        fun check(expected: String?, actual: String?, name: String) {
            if (expected == null) return
            // An empty string deliberately clears the field; TagLib then reports no value.
            if (expected.isBlank()) {
                if (!actual.isNullOrBlank()) error("$name was not cleared")
            } else if (actual != expected) {
                error("$name was not written")
            }
        }
        return runCatching {
            check(edit.title, after.title, "Title")
            check(edit.artist, after.artist, "Artist")
            check(edit.album, after.album, "Album")
            check(edit.albumArtist, after.albumArtist, "Album artist")
            check(edit.genre, after.genre, "Genre")
            check(edit.composer, after.composer, "Composer")
            check(edit.comment, after.comment, "Comment")
            if (edit.year != null && after.year != edit.year) error("Year was not written")
            if (edit.track != null && after.track != edit.track) error("Track number was not written")
            if (edit.disc != null && after.disc != edit.disc) error("Disc number was not written")
            if (edit.isrc != null && after.first("ISRC") != edit.isrc) error("ISRC was not written")
            if (edit.artwork != null && !after.hasArtwork) error("Artwork was not written")
            if (edit.lyrics != null && after.lyrics != edit.lyrics) error("Lyrics were not written")
            null
        }.getOrElse { it.message ?: "Verification failed" }
    }

    private fun copyFromUri(uri: Uri, target: File) {
        val partial = File(target.parentFile, target.name + ".part")
        context.contentResolver.openInputStream(uri)?.use { input ->
            partial.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Cannot open $uri")
        if (!partial.renameTo(target)) {
            partial.delete()
            error("Could not stage the original file")
        }
    }

    private fun writeToUri(uri: Uri, source: File) {
        val descriptor = runCatching { context.contentResolver.openFileDescriptor(uri, "rwt") }
            .recoverCatching { context.contentResolver.openFileDescriptor(uri, "rw") }
            .getOrThrow() ?: error("Cannot open $uri for writing")
        descriptor.use { pfd ->
            Os.ftruncate(pfd.fileDescriptor, 0)
            source.inputStream().use { input ->
                java.io.FileOutputStream(pfd.fileDescriptor).use { output -> input.copyTo(output) }
            }
            Os.fsync(pfd.fileDescriptor)
        }
    }

    private fun restore(uri: Uri?, original: File): Boolean {
        if (uri == null || !original.isFile) return false
        return runCatching {
            writeToUri(uri, original)
            true
        }.getOrDefault(false)
    }

    private suspend fun failAndRestore(
        journalId: String,
        itemId: String,
        uri: Uri,
        original: File,
        workDir: File,
        reason: String,
    ): TagWriteResult {
        val recovered = restore(uri, original)
        upsertJournal(
            currentJournal(journalId, itemId, original, File(workDir, "staged.bin"), if (recovered) "RECOVERED" else "FAILED")
                .copy(error = reason),
        )
        // Keep the verified backup when the destination could not be restored, so startup retries it.
        if (recovered) cleanup(workDir)
        return TagWriteResult.Failure(reason, recovered)
    }

    private suspend fun upsertJournal(value: TagWriteJournalEntity) = dao.upsertTagWriteJournal(value)

    private fun currentJournal(
        id: String,
        trackId: String,
        original: File,
        staged: File,
        state: String,
    ) = TagWriteJournalEntity(
        id = id,
        trackId = trackId,
        operation = "TAGS",
        stagedPath = "$original\u0000$staged",
        originalFingerprint = if (original.isFile) sha256(original) else null,
        state = state,
        createdAt = now(),
        updatedAt = now(),
    )

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun cleanup(dir: File) {
        runCatching { dir.deleteRecursively() }
    }

    private companion object {
        /** Serializes writes and startup recovery across ViewModels/activities in this process. */
        val writeLock = Mutex()
    }
}
