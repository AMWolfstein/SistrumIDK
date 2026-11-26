package com.amwolfstein.sistrum.analysis

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.analysisDataStore by preferencesDataStore(name = "local_library_analysis")
private val EnabledKey = booleanPreferencesKey("enabled")
private val WorkerCountKey = intPreferencesKey("worker_count")

internal data class LocalAnalysisSettings(val enabled: Boolean = false, val workers: Int = 1)

internal class LocalAnalysisPreferences(private val context: Context) {
    val settings: Flow<LocalAnalysisSettings> = context.analysisDataStore.data.map { preferences ->
        LocalAnalysisSettings(
            enabled = preferences[EnabledKey] ?: false,
            workers = (preferences[WorkerCountKey] ?: 1).coerceIn(1, 2),
        )
    }

    suspend fun setEnabled(enabled: Boolean) {
        scheduleLocalAnalysis(context, enabled)
        context.analysisDataStore.edit { it[EnabledKey] = enabled }
    }

    suspend fun setWorkers(workers: Int) {
        context.analysisDataStore.edit { it[WorkerCountKey] = workers.coerceIn(1, 2) }
    }
}
