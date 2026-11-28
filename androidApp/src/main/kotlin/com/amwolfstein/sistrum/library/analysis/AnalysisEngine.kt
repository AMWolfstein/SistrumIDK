package com.amwolfstein.sistrum.library.analysis

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.amwolfstein.sistrum.library.db.LibraryDatabase
import com.amwolfstein.sistrum.library.db.LibraryItemEntity
import com.amwolfstein.sistrum.library.db.TrackAnalysisEntity
import com.amwolfstein.sistrum.player.AnalysisBridge
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Versions stamped on every result; bump when the corresponding layout changes. */
object AnalysisVersion {
    const val FFMPEG = 1
    const val AUBIO = 1

    /** Matches Airmedy d859940c `audio.AnalyzerVersion`. */
    const val ANALYZER = 4
    const val MOOD = 1
}

/**
 * Runs the native analyzer on one library item and persists a versioned result. The audio is
 * opened read-only by descriptor, so SAF documents work; analysis never modifies the file.
 */
class AnalysisEngine(
    context: Context,
    private val database: LibraryDatabase = LibraryDatabase.get(context),
    private val writeTags: suspend () -> Boolean = { false },
    private val reuseExisting: Boolean = true,
    private val preserveReplayGain: Boolean = true,
) {
    private val context = context.applicationContext
    private val dao = database.libraryDao()

    /** Reuses a completed result when the audio fingerprint and analyzer version still match. */
    suspend fun analyzeIfNeeded(trackId: String): Result<TrackAnalysisEntity> = withContext(Dispatchers.IO) {
        val item = dao.itemById(trackId) ?: return@withContext Result.failure(IllegalArgumentException("Unknown track $trackId"))
        val fingerprint = item.audioFingerprint ?: item.contentKey ?: item.documentKey
        val existing = dao.analysis(trackId)
        if (reuseExisting && existing != null && existing.status == "COMPLETED" &&
            existing.analyzerVersion == AnalysisVersion.ANALYZER &&
            existing.audioFingerprint == fingerprint
        ) {
            return@withContext Result.success(existing)
        }
        analyze(trackId).onSuccess { result ->
            if (writeTags()) runCatching { writeAnalysisTags(item.id, result) }
        }
    }

    suspend fun analyze(trackId: String): Result<TrackAnalysisEntity> = analyze(trackId, deriveMood = true)

    /** Batch entry point: skips the per-track mood backfill; callers run it once for the batch. */
    suspend fun analyze(trackId: String, deriveMood: Boolean): Result<TrackAnalysisEntity> = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        dao.insertAnalysisPlaceholder(
            TrackAnalysisEntity(trackId = trackId, status = "RUNNING", updatedAt = started),
        )
        dao.markAnalysisRunning(trackId, started)
        val previous = dao.analysis(trackId)
        try {
            val item = dao.itemById(trackId) ?: error("Unknown track $trackId")
            val descriptor = openDescriptor(item.fileUri)
                ?: error("Unable to open audio for $trackId")
            val values = descriptor.use {
                AnalysisBridge.nativeAnalyzeFd(it.fd, item.cueStartMs ?: 0, item.cueEndMs ?: -1)
            }
            if (values.size < 12) error("Analysis returned ${values.size} fields for $trackId")
            val completedAt = System.currentTimeMillis()
            val stored = TrackAnalysisEntity(
                trackId = trackId,
                loudnessLufs = values[0].toDouble(),
                loudnessRange = values[1].toDouble(),
                truePeak = values[2].toDouble(),
                rms = values[3].toDouble(),
                crest = values[4].toDouble(),
                spectralCentroid = values[5].toDouble(),
                spectralRolloff = values[6].toDouble(),
                spectralFlatness = values[7].toDouble(),
                spectralFlux = values[8].toDouble(),
                zcr = values[9].toDouble(),
                tempo = values[10].toDouble(),
                onsetVariance = values[11].toDouble(),
                ffmpegVersion = AnalysisVersion.FFMPEG,
                aubioVersion = AnalysisVersion.AUBIO,
                analyzerVersion = AnalysisVersion.ANALYZER,
                moodVersion = 0,
                audioFingerprint = item.audioFingerprint ?: item.contentKey ?: item.documentKey,
                source = "local",
                status = "COMPLETED",
                attempts = (previous?.attempts ?: 0) + 1,
                lastError = null,
                updatedAt = completedAt,
                analyzedAt = completedAt,
            )
            dao.upsertAnalysis(stored)
            if (deriveMood) {
                MoodDerivation.recompute(dao)
                // Return the stored row: the mood backfill updates energy/danceability/brightness.
                return@withContext Result.success(dao.analysis(trackId) ?: stored)
            }
            Result.success(stored)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // Cancellation is resumable: the stale analyzer version keeps the row in the queue.
            dao.markAnalysisCancelled(trackId, System.currentTimeMillis())
            throw cancelled
        } catch (failure: Throwable) {
            dao.markAnalysisFailed(
                trackId,
                (failure.message ?: failure::class.simpleName ?: "analysis failed").take(500),
                System.currentTimeMillis(),
            )
            Result.failure(failure)
        }
    }

    /**
     * Analyzes up to [limit] tracks through [AnalysisScheduler], then re-derives the corpus mood
     * features once (percentiles only change when the corpus changes).
     */
    suspend fun analyzeAll(limit: Int = 50): Int = withContext(Dispatchers.IO) {
        val scheduler = AnalysisScheduler(this@AnalysisEngine, dao)
        scheduler.recoverInterrupted()
        scheduler.run(limit).completed
    }

    /** Writes the full analysis set plus standard ReplayGain, keeping existing gain keys. */
    suspend fun writeAnalysisTags(
        trackId: String,
        analysis: TrackAnalysisEntity,
    ): com.amwolfstein.sistrum.library.tags.TagWriteResult? = withContext(Dispatchers.IO) {
        val item = dao.itemById(trackId) ?: return@withContext null
        val descriptor = openDescriptor(item.fileUri) ?: return@withContext null
        val existingKeys = if (preserveReplayGain) {
            descriptor.use { fd -> com.amwolfstein.sistrum.library.tags.readRawTags(fd.fd).keys }
        } else {
            emptySet()
        }
        descriptor.close()
        val album = analysesForAlbum(item)
        val tags = AnalysisTagCodec.tagsFor(
            format = AnalysisTagCodec.formatOf(item.format),
            analysis = analysis,
            albumLoudnessLufs = album?.loudnessLufs,
            albumTruePeakDb = album?.truePeak,
            existingKeys = existingKeys,
        )
        if (tags.isEmpty()) return@withContext null
        // Route analysis writes through the same staged/journaled writer as user tag edits.
        val writer = com.amwolfstein.sistrum.library.tags.TagWriter(context, dao)
        val result = writer.write(
            item,
            com.amwolfstein.sistrum.library.tags.TagEdit(properties = tags),
        )
        if (result !is com.amwolfstein.sistrum.library.tags.TagWriteResult.Success) {
            return@withContext result
        }
        // Our own write must not look like an external change on the next scan: refresh the
        // document's size/mtime/content key in place instead of letting incremental scan redo it.
        runCatching {
            republish(item)
            // The write can change the file's container framing; re-link the row to the freshly
            // published fingerprint so the next pass reuses this result instead of re-decoding.
            val refreshed = dao.itemById(trackId) ?: return@runCatching
            val current = dao.analysis(trackId) ?: return@runCatching
            if (current.audioFingerprint != refreshed.audioFingerprint) {
                dao.upsertAnalysis(current.copy(audioFingerprint = refreshed.audioFingerprint))
            }
        }
        result
    }

    /** Refreshes one document after an analysis write without triggering removals. */
    private suspend fun republish(item: LibraryItemEntity) {
        val source = dao.source(item.sourceId) ?: return
        val uri = Uri.parse(item.fileUri)
        var size = item.fileSize
        var modified = item.fileModTime
        if (item.fileUri.startsWith("content://")) {
            runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                        val modifiedIndex = cursor.getColumnIndex(
                            android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                        )
                        if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) {
                            modified = cursor.getLong(modifiedIndex)
                        }
                    }
                }
            }
        } else {
            runCatching {
                val file = File(uri.path ?: return@runCatching)
                size = file.length()
                modified = file.lastModified()
            }
        }
        val entry = com.amwolfstein.sistrum.library.db.ScanEntryEntity(
            runId = "",
            documentKey = item.documentKey,
            fileUri = item.fileUri,
            modTime = modified,
            size = size,
            state = "SEEN",
            relativePath = item.displayPath.orEmpty(),
            // Keep the real file name: the extension drives the tag format on republication.
            name = item.displayPath?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: uri.lastPathSegment?.takeIf { it.isNotBlank() }
                ?: item.documentKey.substringAfterLast(':'),
            cueKey = item.cueKey,
            cueTrackNumber = item.trackNumberInCue,
            cueStartMs = item.cueStartMs,
            cueEndMs = item.cueEndMs,
            changed = true,
        )
        com.amwolfstein.sistrum.library.LibraryRepository(context, database).publishEntry(source, entry)
    }

    /** Album aggregate for ReplayGain album tags, when the track belongs to a coherent album. */
    private suspend fun analysesForAlbum(
        item: com.amwolfstein.sistrum.library.db.LibraryItemEntity,
    ): TrackAnalysisEntity? {
        val albumId = item.albumId ?: return null
        val tracks = dao.analysesForAlbum(albumId)
        if (tracks.size < 2) return null
        val loudness = tracks.mapNotNull { it.loudnessLufs }
        if (loudness.isEmpty()) return null
        // Airmedy (normalization/service.go albumLoudness): arithmetic mean of the album's track LUFS.
        return TrackAnalysisEntity(
            trackId = "",
            loudnessLufs = loudness.average(),
            truePeak = tracks.mapNotNull { it.truePeak }.maxOrNull(),
        )
    }

    private fun openDescriptor(uriValue: String, write: Boolean = false): ParcelFileDescriptor? = runCatching {
        val mode = if (write) "rw" else "r"
        val parcelMode = if (write) ParcelFileDescriptor.MODE_READ_WRITE else ParcelFileDescriptor.MODE_READ_ONLY
        if (uriValue.startsWith("content://")) {
            context.contentResolver.openFileDescriptor(Uri.parse(uriValue), mode)
        } else if (uriValue.startsWith("file://")) {
            val path = Uri.parse(uriValue).path ?: return@runCatching null
            ParcelFileDescriptor.open(File(path), parcelMode)
        } else {
            ParcelFileDescriptor.open(File(uriValue), parcelMode)
        }
    }.getOrNull()
}
