package com.amwolfstein.sistrum.sync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.scanFilterPreferencesDataStore by preferencesDataStore(name = "scan_filter")
internal enum class ScanSchedule(val intervalMillis: Long?) {
    Manual(null),
    OnLaunch(null),
    FifteenMinutes(15 * 60_000L),
    ThirtyMinutes(30 * 60_000L),
    Hourly(60 * 60_000L),
}

private val ScanScheduleKey = stringPreferencesKey("schedule")
private val ScanModeKey = stringPreferencesKey("mode")
private val WhitelistedFoldersKey = stringSetPreferencesKey("whitelisted_folders")
private val BlacklistedFoldersKey = stringSetPreferencesKey("blacklisted_folders")

/** Persists the folder whitelist/blacklist scan filter independently from sync data. */
internal class ScanFilterPreferences(private val context: Context) {
    val filter: Flow<MediaScanFilter> = context.scanFilterPreferencesDataStore.data.map { preferences ->
        MediaScanFilter(
            mode = preferences[ScanModeKey]?.let { raw -> runCatching { MediaScanMode.valueOf(raw) }.getOrNull() }
                ?: MediaScanMode.Blacklist,
            whitelistedFolders = preferences[WhitelistedFoldersKey].orEmpty(),
            blacklistedFolders = preferences[BlacklistedFoldersKey].orEmpty(),
        )
    }

    val schedule: Flow<ScanSchedule> = context.scanFilterPreferencesDataStore.data.map { preferences ->
        preferences[ScanScheduleKey]?.let { raw -> runCatching { ScanSchedule.valueOf(raw) }.getOrNull() }
            ?: ScanSchedule.Manual
    }

    suspend fun setSchedule(value: ScanSchedule) {
        scheduleLibraryScan(context, value)
        context.scanFilterPreferencesDataStore.edit { it[ScanScheduleKey] = value.name }
    }

    suspend fun currentFilter(): MediaScanFilter = filter.first()

    suspend fun setMode(mode: MediaScanMode) {
        context.scanFilterPreferencesDataStore.edit { preferences -> preferences[ScanModeKey] = mode.name }
    }

    suspend fun setWhitelistedFolders(folders: Set<String>) {
        context.scanFilterPreferencesDataStore.edit { preferences -> preferences[WhitelistedFoldersKey] = folders }
    }

    suspend fun setBlacklistedFolders(folders: Set<String>) {
        context.scanFilterPreferencesDataStore.edit { preferences -> preferences[BlacklistedFoldersKey] = folders }
    }
}
