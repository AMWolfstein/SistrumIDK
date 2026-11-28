package com.amwolfstein.sistrum.library.analysis

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

internal val Context.analysisDataStore by preferencesDataStore("analysis")

internal data class AnalysisSettings(
    /** `Save analysis to audio files`: write analysis + standard gain tags via TagLib. */
    val writeAnalysisToFiles: Boolean = false,
    /** `Reuse existing analysis`: skip a re-decode when fingerprint and versions still match. */
    val reuseExisting: Boolean = true,
    /** `Preserve existing ReplayGain tags` (default ON, per FR-085). */
    val preserveReplayGain: Boolean = true,
    val analysisEnabled: Boolean = true,
)

internal class AnalysisPreferences(private val context: Context) {
    private val writeTagsKey = booleanPreferencesKey("write_replaygain_tags")
    private val reuseKey = booleanPreferencesKey("reuse_existing_analysis")
    private val preserveKey = booleanPreferencesKey("preserve_existing_replaygain")
    private val enabledKey = booleanPreferencesKey("analysis_enabled")

    val settings = context.analysisDataStore.data.map { preferences ->
        AnalysisSettings(
            writeAnalysisToFiles = preferences[writeTagsKey] ?: false,
            reuseExisting = preferences[reuseKey] ?: true,
            preserveReplayGain = preferences[preserveKey] ?: true,
            analysisEnabled = preferences[enabledKey] ?: true,
        )
    }

    suspend fun setWriteAnalysisToFiles(enabled: Boolean) =
        context.analysisDataStore.edit { it[writeTagsKey] = enabled }

    suspend fun setReuseExisting(enabled: Boolean) =
        context.analysisDataStore.edit { it[reuseKey] = enabled }

    suspend fun setPreserveReplayGain(enabled: Boolean) =
        context.analysisDataStore.edit { it[preserveKey] = enabled }

    suspend fun setAnalysisEnabled(enabled: Boolean) =
        context.analysisDataStore.edit { it[enabledKey] = enabled }
}
