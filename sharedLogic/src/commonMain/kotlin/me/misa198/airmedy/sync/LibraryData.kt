package me.misa198.airmedy.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

object LibraryJson {
    val json = Json { ignoreUnknownKeys = false; encodeDefaults = true; explicitNulls = false }
}

@Serializable
data class LibraryPlanAsset(
    val id: String,
    val kind: String,
    val sha256: String,
    val size: Long,
)

@Serializable
data class LibraryPlanManifest(
    val version: Int,
    @SerialName("plan_id") val planId: String,
    val revision: String,
    val scope: JsonObject,
    // Preserve the stored plan format, including older snapshots.
    val tracks: List<JsonObject>? = null,
    val playlists: List<JsonObject>? = null,
    val lyrics: JsonObject,
    val analysis: JsonObject,
    val assets: List<LibraryPlanAsset>? = null,
    @SerialName("library_analysis_enabled") val libraryAnalysisEnabled: Boolean = false,
)
