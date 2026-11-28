package com.amwolfstein.sistrum.library.scan

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

internal val Context.scanDataStore by preferencesDataStore("scan_settings")

/**
 * Library scan preferences. Automatic scans are opt-out and rate-limited so a
 * foreground launch cannot start a full traversal more than once per window.
 */
class ScanPreferences(context: Context) {
    private val context = context.applicationContext

    val autoScanEnabled: Flow<Boolean> =
        context.scanDataStore.data.map { it[autoScanKey] ?: true }

    val lastAutoScanAt: Flow<Long> =
        context.scanDataStore.data.map { it[lastAutoScanKey] ?: 0L }

    suspend fun setAutoScanEnabled(enabled: Boolean) {
        context.scanDataStore.edit { it[autoScanKey] = enabled }
    }

    suspend fun markAutoScan() {
        context.scanDataStore.edit { it[lastAutoScanKey] = System.currentTimeMillis() }
    }

    suspend fun shouldAutoScan(now: Long = System.currentTimeMillis()): Boolean =
        (autoScanEnabled.first()) && now - lastAutoScanAt.first() >= MIN_INTERVAL_MS

    private companion object {
        const val MIN_INTERVAL_MS = 6L * 60L * 60L * 1000L
        val autoScanKey = booleanPreferencesKey("auto_scan_enabled")
        val lastAutoScanKey = longPreferencesKey("last_auto_scan_at")
    }
}
