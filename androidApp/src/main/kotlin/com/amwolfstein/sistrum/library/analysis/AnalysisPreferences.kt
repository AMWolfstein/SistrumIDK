package com.amwolfstein.sistrum.library.analysis

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

internal val Context.analysisDataStore by preferencesDataStore("analysis")

internal data class AnalysisSettings(
    val writeReplayGainTags: Boolean = false,
    val analysisEnabled: Boolean = true,
)

internal class AnalysisPreferences(private val context: Context) {
    private val writeTagsKey = booleanPreferencesKey("write_replaygain_tags")
    private val enabledKey = booleanPreferencesKey("analysis_enabled")

    val settings = context.analysisDataStore.data.map { preferences ->
        AnalysisSettings(
            writeReplayGainTags = preferences[writeTagsKey] ?: false,
            analysisEnabled = preferences[enabledKey] ?: true,
        )
    }

    suspend fun setWriteReplayGainTags(enabled: Boolean) =
        context.analysisDataStore.edit { it[writeTagsKey] = enabled }

    suspend fun setAnalysisEnabled(enabled: Boolean) =
        context.analysisDataStore.edit { it[enabledKey] = enabled }
}
