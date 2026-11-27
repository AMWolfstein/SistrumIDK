package com.amwolfstein.sistrum.library.artwork

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

internal val Context.artworkDataStore by preferencesDataStore("artwork")

/** Deezer artist artwork is opt-in; the cached images stay usable when it is off. */
internal data class ArtworkSettings(val deezerEnabled: Boolean = false)

internal class ArtworkPreferences(private val context: Context) {
    private val deezerKey = booleanPreferencesKey("deezer_artist_artwork")

    val settings = context.artworkDataStore.data.map { preferences ->
        ArtworkSettings(deezerEnabled = preferences[deezerKey] ?: false)
    }

    suspend fun setDeezerEnabled(enabled: Boolean) =
        context.artworkDataStore.edit { it[deezerKey] = enabled }
}
