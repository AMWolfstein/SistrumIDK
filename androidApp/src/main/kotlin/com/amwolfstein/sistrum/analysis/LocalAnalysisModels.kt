package com.amwolfstein.sistrum.analysis

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.amwolfstein.sistrum.analysis.SignalFeatures
import com.amwolfstein.sistrum.sync.LibraryJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

internal const val LocalAnalyzerVersion = 2

@Entity(tableName = "local_track_analysis")
internal data class LocalTrackAnalysisEntity(
    @PrimaryKey val trackId: String,
    val sourceRevision: String,
    val analyzerVersion: Int,
    val status: String,
    val rawJson: String,
    val failure: String,
)

internal data class LocalAnalysisFailure(
    val trackId: String,
    val title: String,
    val failure: String,
)

internal data class LocalAnalysisCandidate(
    val trackId: String,
    val path: String,
    val sourceRevision: String,
    val syncOrder: Int,
)

internal fun localAnalysisObject(row: LocalTrackAnalysisEntity): JsonObject? =
    if (row.status != "success") null
    else runCatching { LibraryJson.json.parseToJsonElement(row.rawJson).jsonObject }.getOrNull()

internal fun JsonObject.finiteDouble(key: String): Double? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.takeIf(Double::isFinite)

internal fun localSignalFeatures(row: LocalTrackAnalysisEntity): SignalFeatures? {
    val value = localAnalysisObject(row) ?: return null
    return SignalFeatures(
        rmsDb = value.finiteDouble("rms") ?: return null,
        crest = value.finiteDouble("crest") ?: return null,
        spectralCentroid = value.finiteDouble("spectral_centroid") ?: return null,
        spectralFlux = value.finiteDouble("spectral_flux") ?: return null,
        tempo = value.finiteDouble("tempo") ?: return null,
        onsetVariance = value.finiteDouble("onset_variance") ?: return null,
        loudnessRange = value.finiteDouble("loudness_range") ?: return null,
    )
}
