package com.amwolfstein.sistrum.lyrics

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

internal val Context.lyricsDataStore by preferencesDataStore("lyrics")
private val LrclibKey = booleanPreferencesKey("enable_lrclib")
private val KugouKey = booleanPreferencesKey("enable_kugou")
private val RomanizationKey = booleanPreferencesKey("enable_romanization")
private val GlowKey = booleanPreferencesKey("lyrics_glow")
private val EmbeddedKey = booleanPreferencesKey("enable_embedded")
private val SidecarKey = booleanPreferencesKey("enable_sidecar")

internal data class LyricsSettings(
    val lrclib: Boolean = true,
    val kugou: Boolean = true,
    val romanizationEnabled: Boolean = false,
    val glowEnabled: Boolean = false,
    val embedded: Boolean = true,
    val sidecar: Boolean = true,
)

internal class LyricsPreferences(private val context: Context) {
    val settings = context.lyricsDataStore.data.map {
        LyricsSettings(
            lrclib = it[LrclibKey] ?: true,
            kugou = it[KugouKey] ?: true,
            romanizationEnabled = it[RomanizationKey] ?: false,
            glowEnabled = it[GlowKey] ?: false,
            embedded = it[EmbeddedKey] ?: true,
            sidecar = it[SidecarKey] ?: true,
        )
    }
    suspend fun setLrclib(enabled: Boolean) = context.lyricsDataStore.edit { it[LrclibKey] = enabled }
    suspend fun setKugou(enabled: Boolean) = context.lyricsDataStore.edit { it[KugouKey] = enabled }
    suspend fun setRomanizationEnabled(enabled: Boolean) = context.lyricsDataStore.edit { it[RomanizationKey] = enabled }
    suspend fun setGlowEnabled(enabled: Boolean) = context.lyricsDataStore.edit { it[GlowKey] = enabled }
    suspend fun setEmbedded(enabled: Boolean) = context.lyricsDataStore.edit { it[EmbeddedKey] = enabled }
    suspend fun setSidecar(enabled: Boolean) = context.lyricsDataStore.edit { it[SidecarKey] = enabled }
}
