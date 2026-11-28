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
) {
    private val context = context.applicationContext
    private val dao = database.libraryDao()

    suspend fun analyze(trackId: String): Result<TrackAnalysisEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val item = dao.itemById(trackId) ?: error("Unknown track $trackId")
            val descriptor = openDescriptor(item.fileUri)
                ?: error("Unable to open audio for $trackId")
            val values = descriptor.use { AnalysisBridge.nativeAnalyzeFd(it.fd) }
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
            if (analyze(item.id).isSuccess) done += 1
        }
        done
    }

    private fun openDescriptor(uriValue: String): ParcelFileDescriptor? = runCatching {
        if (uriValue.startsWith("content://")) {
            context.contentResolver.openFileDescriptor(Uri.parse(uriValue), "r")
        } else if (uriValue.startsWith("file://")) {
            val path = Uri.parse(uriValue).path ?: return@runCatching null
            ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
        } else {
            ParcelFileDescriptor.open(File(uriValue), ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }.getOrNull()
}
