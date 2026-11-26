package com.amwolfstein.sistrum.playlist

import android.content.Context
import com.amwolfstein.sistrum.sync.LibraryPlaylist
import com.amwolfstein.sistrum.sync.LibraryTrack
import com.amwolfstein.sistrum.ui.screens.isFavorite
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import com.amwolfstein.sistrum.sync.LibraryJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal data class SmartRule(val field: String, val value: String)

internal class SmartPlaylistRules(context: Context) {
    private val preferences = context.getSharedPreferences("smart_playlists", Context.MODE_PRIVATE)
    private val current = MutableStateFlow(read())
    val rules: StateFlow<Map<String, SmartRule>> = current

    fun set(id: String, rule: SmartRule) {
        val value = JSONObject().put("field", rule.field).put("value", rule.value).toString()
        check(preferences.edit().putString(id, value).commit())
        current.value = current.value + (id to rule)
    }

    private fun read(): Map<String, SmartRule> = preferences.all.mapNotNull { (id, value) ->
        val json = runCatching { JSONObject(value as String) }.getOrNull() ?: return@mapNotNull null
        id to SmartRule(json.optString("field"), json.optString("value"))
    }.toMap()
}

internal fun applySmartRules(
    playlists: List<LibraryPlaylist>,
    tracks: List<LibraryTrack>,
    rules: Map<String, SmartRule>,
): List<LibraryPlaylist> = playlists.map { playlist ->
    val rule = rules[playlist.id] ?: return@map playlist
    val metadata = runCatching { LibraryJson.json.parseToJsonElement(playlist.metadataJson) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
    val inner = (metadata["playlist"] as? JsonObject) ?: JsonObject(emptyMap())
    val updated = JsonObject(metadata + ("playlist" to JsonObject(inner + ("is_smart" to JsonPrimitive(true)))))
    playlist.copy(metadataJson = updated.toString(), trackIds = tracks.filter { track ->
        when (rule.field) {
            "artist" -> track.artists.contains(rule.value, ignoreCase = true)
            "album" -> track.album.contains(rule.value, ignoreCase = true)
            "title" -> track.title.contains(rule.value, ignoreCase = true)
            "favorites" -> track.isFavorite()
            else -> false
        }
    }.map { it.id })
}
