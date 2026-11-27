package com.amwolfstein.sistrum.library.metadata

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

internal val Context.separatorDataStore by preferencesDataStore("separators")

/**
 * Persists the four delimiter lists. Stored as JSON arrays so a delimiter containing any character
 * (including the separator used to encode) round-trips exactly.
 */
internal class SeparatorPreferences(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lists = ListSerializer(String.serializer())
    private val artistKey = stringPreferencesKey("artist_delimiters")
    private val albumArtistKey = stringPreferencesKey("album_artist_delimiters")
    private val genreKey = stringPreferencesKey("genre_delimiters")
    private val composerKey = stringPreferencesKey("composer_delimiters")

    val settings = context.separatorDataStore.data.map { prefs ->
        SeparatorConfig(
            artist = decode(prefs[artistKey]),
            albumArtist = decode(prefs[albumArtistKey]),
            genre = decode(prefs[genreKey]),
            composer = decode(prefs[composerKey]),
        )
    }

    suspend fun setCategory(category: SeparatorCategory, delimiters: List<String>) {
        context.separatorDataStore.edit { prefs ->
            prefs[keyFor(category)] = json.encodeToString(lists, delimiters.map { it.trim() })
        }
    }

    private fun decode(value: String?): List<String> {
        if (value.isNullOrBlank()) return Separators.DEFAULT_DELIMITERS
        return runCatching { json.decodeFromString(lists, value) }.getOrElse { Separators.DEFAULT_DELIMITERS }
    }

    private fun keyFor(category: SeparatorCategory) = when (category) {
        SeparatorCategory.ARTIST -> artistKey
        SeparatorCategory.ALBUM_ARTIST -> albumArtistKey
        SeparatorCategory.GENRE -> genreKey
        SeparatorCategory.COMPOSER -> composerKey
    }
}
