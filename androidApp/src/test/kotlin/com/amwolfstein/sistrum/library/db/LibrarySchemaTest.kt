package com.amwolfstein.sistrum.library.db

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySchemaTest {
    @Test
    fun everyIndexedColumnExistsAndCoreColumnsArePresent() {
        val file = File("schemas/com.amwolfstein.sistrum.library.db.LibraryDatabase/1.json")
        assertTrue("Room schema not generated: $file", file.isFile)
        val database = Json.parseToJsonElement(file.readText()).jsonObject["database"]!!.jsonObject
        val entities = database["entities"]!!.jsonArray.map { it.jsonObject }
        assertEquals(30, entities.size)
        val entityByName = entities.associateBy { it["tableName"]!!.jsonPrimitive.content }
        entities.forEach { entity ->
            val fields = entity["fields"]!!.jsonArray.map { it.jsonObject["columnName"]!!.jsonPrimitive.content }.toSet()
            entity["indices"]?.jsonArray.orEmpty().forEach { index ->
                index.jsonObject["columnNames"]?.jsonArray.orEmpty().forEach { column ->
                    assertTrue(
                        "Index column ${column.jsonPrimitive.content} missing in ${entity["tableName"]}",
                        column.jsonPrimitive.content in fields,
                    )
                }
            }
        }
        val item = entityByName.getValue("library_items")["fields"]!!.jsonArray
            .map { it.jsonObject["columnName"]!!.jsonPrimitive.content }.toSet()
        assertTrue(
            item.containsAll(
                setOf(
                    "albumId", "cueStartMs", "cueEndMs", "rating", "contentKey",
                    "audioFingerprint", "documentKey", "sourceId",
                ),
            ),
        )
        assertEquals(
            setOf(
                "library_sources", "artists", "albums", "genres", "composers", "library_items",
                "item_sources", "track_artists", "track_album_artists", "track_genres",
                "track_composers", "album_artists", "artwork_assets", "playlists",
                "playlist_tracks", "lyrics", "track_analysis", "listening_sessions",
                "playback_attempts", "daily_track_listening_stats", "daily_playback_attempt_stats",
                "scan_runs", "scan_entries", "scan_errors", "library_exclusions",
                "legacy_track_refs", "tag_write_journal", "legacy_lyrics",
                "legacy_playlist_tracks", "legacy_import_state",
            ),
            entityByName.keys,
        )
    }

    @Test
    fun version2AddsScanEntryMetadataForResume() {
        val file = File("schemas/com.amwolfstein.sistrum.library.db.LibraryDatabase/2.json")
        assertTrue("Room schema not generated: $file", file.isFile)
        val database = Json.parseToJsonElement(file.readText()).jsonObject["database"]!!.jsonObject
        assertEquals(2, database["version"]!!.jsonPrimitive.content.toInt())
        val entities = database["entities"]!!.jsonArray.map { it.jsonObject }
        val scanEntries = entities.single { it["tableName"]!!.jsonPrimitive.content == "scan_entries" }
        val fields = scanEntries["fields"]!!.jsonArray.map { it.jsonObject["columnName"]!!.jsonPrimitive.content }.toSet()
        assertTrue(
            fields.containsAll(
                setOf(
                    "relativePath", "name", "cueKey", "cueTrackNumber", "cueStartMs",
                    "cueEndMs", "cueTitle", "cuePerformer", "changed",
                ),
            ),
        )
        assertEquals(30, entities.size)
    }
}
