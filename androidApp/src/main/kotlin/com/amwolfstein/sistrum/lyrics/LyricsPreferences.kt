package com.amwolfstein.sistrum.lyrics

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.map

internal val Context.lyricsDataStore by preferencesDataStore("lyrics")
private val LrclibKey = booleanPreferencesKey("enable_lrclib")
private val KugouKey = booleanPreferencesKey("enable_kugou")
private val RomanizationKey = booleanPreferencesKey("enable_romanization")
private val GlowKey = booleanPreferencesKey("lyrics_glow")
private val PreferredSourceKey = stringPreferencesKey("preferred_source")
private val PreferLocalKey = booleanPreferencesKey("prefer_local_lyrics")
private val SubfolderEnabledKey = booleanPreferencesKey("lyrics_subfolder_enabled")
private val SubfolderNameKey = stringPreferencesKey("lyrics_subfolder_name")
private val DedicatedFolderKey = stringPreferencesKey("lyrics_folder_uri")

internal enum class LyricsSource(val storageValue: String) {
    Desktop("desktop"),
    AutoFetch("auto_fetch"),
    ;

    companion object {
        fun fromStorage(value: String?) = entries.firstOrNull { it.storageValue == value } ?: Desktop
    }
}

internal data class LyricsSettings(
    val preferredSource: LyricsSource = LyricsSource.Desktop,
    val lrclib: Boolean = true,
    val kugou: Boolean = true,
    val romanizationEnabled: Boolean = false,
    val glowEnabled: Boolean = false,
    val preferLocalLyrics: Boolean = true,
    val subfolderEnabled: Boolean = false,
    val subfolderName: String = "Lyrics",
    val dedicatedFolderUri: String? = null,
)

internal fun preferredLyrics(source: LyricsSource, desktop: String?, provider: String?): String? = when (source) {
    LyricsSource.Desktop -> desktop ?: provider
    LyricsSource.AutoFetch -> provider ?: desktop
}

internal class LyricsPreferences(private val context: Context) {
    val settings = context.lyricsDataStore.data.map {
        LyricsSettings(
            preferredSource = LyricsSource.fromStorage(it[PreferredSourceKey]),
            lrclib = it[LrclibKey] ?: true,
            kugou = it[KugouKey] ?: true,
            romanizationEnabled = it[RomanizationKey] ?: false,
            glowEnabled = it[GlowKey] ?: false,
            preferLocalLyrics = it[PreferLocalKey] ?: true,
            subfolderEnabled = it[SubfolderEnabledKey] ?: false,
            subfolderName = it[SubfolderNameKey] ?: "Lyrics",
            dedicatedFolderUri = it[DedicatedFolderKey],
        )
    }
    suspend fun setPreferredSource(source: LyricsSource) = context.lyricsDataStore.edit { it[PreferredSourceKey] = source.storageValue }
    suspend fun setLrclib(enabled: Boolean) = context.lyricsDataStore.edit { it[LrclibKey] = enabled }
    suspend fun setKugou(enabled: Boolean) = context.lyricsDataStore.edit { it[KugouKey] = enabled }
    suspend fun setRomanizationEnabled(enabled: Boolean) = context.lyricsDataStore.edit { it[RomanizationKey] = enabled }
    suspend fun setGlowEnabled(enabled: Boolean) = context.lyricsDataStore.edit { it[GlowKey] = enabled }
    suspend fun setPreferLocalLyrics(enabled: Boolean) = context.lyricsDataStore.edit { it[PreferLocalKey] = enabled }
    suspend fun setSubfolderEnabled(enabled: Boolean) = context.lyricsDataStore.edit { it[SubfolderEnabledKey] = enabled }
    suspend fun setSubfolderName(name: String) = context.lyricsDataStore.edit { it[SubfolderNameKey] = name.trim().ifBlank { "Lyrics" } }
    suspend fun setDedicatedFolder(uri: String?) = context.lyricsDataStore.edit {
        if (uri.isNullOrBlank()) it.remove(DedicatedFolderKey) else it[DedicatedFolderKey] = uri
    }
}
