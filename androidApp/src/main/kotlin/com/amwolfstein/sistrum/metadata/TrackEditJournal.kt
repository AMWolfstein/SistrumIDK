package com.amwolfstein.sistrum.metadata

import android.content.Context
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal data class TrackEdit(val trackId: String, val path: String, val oldHash: String, val newHash: String)

internal class TrackEditJournal(context: Context) {
    private val preferences = context.getSharedPreferences("track_edit_journal", Context.MODE_PRIVATE)
    private val current = MutableStateFlow(read())
    val edits: StateFlow<List<TrackEdit>> = current

    fun put(edit: TrackEdit) {
        val data = JSONObject().put("path", edit.path).put("oldHash", edit.oldHash).put("newHash", edit.newHash).toString()
        check(preferences.edit().putString(edit.trackId, data).commit())
        current.value = read()
    }

    fun pending(): List<TrackEdit> = current.value

    private fun read(): List<TrackEdit> = preferences.all.mapNotNull { (id, value) ->
        val obj = runCatching { JSONObject(value as String) }.getOrNull() ?: return@mapNotNull null
        TrackEdit(id, obj.optString("path"), obj.optString("oldHash"), obj.optString("newHash"))
    }

    fun remove(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val editor = preferences.edit()
        ids.forEach(editor::remove)
        check(editor.commit())
        current.value = read()
    }
}
