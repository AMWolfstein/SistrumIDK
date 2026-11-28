package com.amwolfstein.sistrum.library.analysis

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.amwolfstein.sistrum.library.db.LibraryDatabase
import com.amwolfstein.sistrum.library.db.TrackAnalysisEntity
import com.amwolfstein.sistrum.player.AnalysisBridge
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Version of the native field layout; bump when the C++ output changes. */
object AnalysisVersion {
    const val FFMPEG = 1
    const val AUBIO = 1
    const val ANALYZER = 1
    const val MOOD = 1
}

/**
 * Runs the native analyzer on one library item and persists a versioned result. The audio is
 * opened read-only by descriptor, so SAF documents work; analysis never modifies the file.
 */
class AnalysisEngine(
    context: Context,
    database: LibraryDatabase = LibraryDatabase.get(context),
    private val writeTags: suspend () -> Boolean = { false },
) {
    private val context = context.applicationContext
    private val dao = database.libraryDao()

    /** Reuses a completed result when the audio fingerprint and analyzer version still match. */
    suspend fun analyzeIfNeeded(trackId: String): Result<TrackAnalysisEntity> = withContext(Dispatchers.IO) {
        val item = dao.itemById(trackId) ?: return@withContext Result.failure(IllegalArgumentException("Unknown track $trackId"))
        val fingerprint = item.audioFingerprint ?: item.contentKey ?: item.documentKey
        val existing = dao.analysis(trackId)
        if (existing != null && existing.status == "COMPLETED" &&
            existing.analyzerVersion == AnalysisVersion.ANALYZER &&
            existing.audioFingerprint == fingerprint
        ) {
            return@withContext Result.success(existing)
        }
        analyze(trackId).onSuccess { result ->
            if (writeTags()) runCatching { writeAnalysisTags(item.id, result) }
        }
    }

    suspend fun analyze(trackId: String): Result<TrackAnalysisEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val item = dao.itemById(trackId) ?: error("Unknown track $trackId")
            val descriptor = openDescriptor(item.fileUri)
                ?: error("Unable to open audio for $trackId")
            val values = descriptor.use {
                AnalysisBridge.nativeAnalyzeFd(it.fd, item.cueStartMs ?: 0, item.cueEndMs ?: -1)
            }
            if (values.size < 15) error("Analysis returned ${values.size} fields for $trackId")
            val result = TrackAnalysisEntity(
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
                energy = values[12].toDouble(),
                danceability = values[13].toDouble(),
                brightness = values[14].toDouble(),
                ffmpegVersion = AnalysisVersion.FFMPEG,
                aubioVersion = AnalysisVersion.AUBIO,
                analyzerVersion = AnalysisVersion.ANALYZER,
                moodVersion = AnalysisVersion.MOOD,
                audioFingerprint = item.audioFingerprint ?: item.contentKey ?: item.documentKey,
                source = "local",
                status = "COMPLETED",
                analyzedAt = System.currentTimeMillis(),
            )
            dao.upsertAnalysis(result)
            result
        }
    }

    suspend fun analyzeAll(limit: Int = 50): Int = withContext(Dispatchers.IO) {
        var done = 0
        dao.itemsNeedingAnalysis(limit, AnalysisVersion.ANALYZER).forEach { item ->
            if (analyzeIfNeeded(item.id).isSuccess) done += 1
        }
        done
    }

    /** Writes ReplayGain/R128 tags, keeping any gain keys the file already had. */
    suspend fun writeAnalysisTags(trackId: String, analysis: TrackAnalysisEntity) = withContext(Dispatchers.IO) {
        val item = dao.itemById(trackId) ?: return@withContext
        val descriptor = openDescriptor(item.fileUri) ?: return@withContext
        val existingKeys = descriptor.use { fd ->
            com.amwolfstein.sistrum.library.tags.readRawTags(fd.fd).keys
        }
        descriptor.close()
        val tags = AnalysisTagCodec.tagsFor(
            AnalysisTagCodec.formatOf(item.format),
            analysis.loudnessLufs,
            analysis.truePeak,
            existingKeys = existingKeys,
        )
        if (tags.isEmpty()) return@withContext
        val write = openDescriptor(item.fileUri, write = true) ?: return@withContext
        write.use { fd ->
            com.amwolfstein.sistrum.library.tags.TagLibBridge.nativeWriteProperties(
                fd.fd,
                tags.keys.toTypedArray(),
                tags.values.toTypedArray(),
            )
        }
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
