package com.amwolfstein.sistrum.player

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val NormalizationEnabledKey = booleanPreferencesKey("normalization_enabled")
private val NormalizationModeKey = stringPreferencesKey("normalization_mode")
private val NormalizationTargetKey = floatPreferencesKey("normalization_target_lufs")
private val ReplayGainModeKey = stringPreferencesKey("replaygain_mode")
private val ReplayGainPreampKey = floatPreferencesKey("replaygain_preamp_db")
private val NormalizationPreventClipKey = booleanPreferencesKey("normalization_prevent_clip")

internal class NormalizationPreferences(private val context: Context) {
    val settings: Flow<NormalizationSettings> = context.playbackPreferencesDataStore.data.map { value ->
        NormalizationSettings(
            enabled = value[NormalizationEnabledKey] ?: false,
            mode = if (value[NormalizationModeKey] == NormalizationMode.Album.name) NormalizationMode.Album else NormalizationMode.Track,
            targetLufs = (value[NormalizationTargetKey] ?: -14f).coerceIn(-30f, -5f),
            preventClip = value[NormalizationPreventClipKey] ?: true,
            replayGainMode = ReplayGainMode.entries.firstOrNull { it.name == value[ReplayGainModeKey] } ?: ReplayGainMode.Off,
            replayGainPreampDb = (value[ReplayGainPreampKey] ?: 0f).coerceIn(-12f, 12f),
        )
    }

    suspend fun update(transform: (NormalizationSettings) -> NormalizationSettings) {
        context.playbackPreferencesDataStore.edit { values ->
            val current = NormalizationSettings(
                values[NormalizationEnabledKey] ?: false,
                if (values[NormalizationModeKey] == NormalizationMode.Album.name) NormalizationMode.Album else NormalizationMode.Track,
                (values[NormalizationTargetKey] ?: -14f).coerceIn(-30f, -5f), values[NormalizationPreventClipKey] ?: true,
                ReplayGainMode.entries.firstOrNull { it.name == values[ReplayGainModeKey] } ?: ReplayGainMode.Off,
                (values[ReplayGainPreampKey] ?: 0f).coerceIn(-12f, 12f),
            )
            val next = transform(current)
            values[ReplayGainModeKey] = next.replayGainMode.name
            values[ReplayGainPreampKey] = next.replayGainPreampDb.coerceIn(-12f, 12f)
            values[NormalizationEnabledKey] = next.enabled
            values[NormalizationModeKey] = next.mode.name
            values[NormalizationTargetKey] = next.targetLufs.coerceIn(-30f, -5f)
            values[NormalizationPreventClipKey] = next.preventClip
        }
    }
    suspend fun disable() = update { it.copy(enabled = false) }
}
