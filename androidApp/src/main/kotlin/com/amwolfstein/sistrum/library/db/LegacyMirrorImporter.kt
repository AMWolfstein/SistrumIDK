package com.amwolfstein.sistrum.library.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class LegacyImportResult(
    val imported: Boolean,
    val playlists: Int = 0,
    val legacyTracks: Int = 0,
    val resolved: Int = 0,
    val unresolved: Int = 0,
)

/**
 * One-time, read-only import of user-owned rows from the retired desktop mirror.
 * The legacy database is never modified; unresolved track references and playlist
 * memberships are kept in the legacy_* tables for relinking after the first scan.
 */
class LegacyMirrorImporter(
    private val context: Context,
    private val database: LibraryDatabase,
    private val legacyName: String = "library-sync.db",
) {
    private val dao = database.libraryDao()
    private val json = Json { ignoreUnknownKeys = true }

    private val playlists = linkedMapOf<String, LegacyPlaylist>()
    private val refs = linkedMapOf<String, LegacyTrack>()
    private val sessions = mutableListOf<LocalListeningSessionEntity>()
    private val attempts = mutableListOf<LocalPlaybackAttemptEntity>()
    private val dailyTrackStats = mutableListOf<LocalDailyTrackStatEntity>()
    private val dailyAttemptStats = mutableListOf<LocalDailyAttemptStatEntity>()
    private val legacyLyrics = mutableListOf<LegacyLyricsEntity>()

    suspend fun importIfNeeded(): LegacyImportResult = database.withTransaction {
        val file = context.getDatabasePath(legacyName)
        if (!file.isFile) return@withTransaction LegacyImportResult(imported = false)
        val fingerprint = fingerprint(file)
        if (dao.legacyImportState()?.sourceFingerprint == fingerprint) {
            return@withTransaction LegacyImportResult(imported = false)
        }
        clearPending()
        val legacy = SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        )
        try {
            val metadata = readTrackMetadata(legacy)
            importLocalPlaylists(legacy)
            importSyncPlaylists(legacy)
            importFavorites(metadata)
            importMutations(legacy, metadata)
            importListening(legacy, metadata)
            importProviderLyrics(legacy, metadata)
            flush(metadata)
            val unresolved = refs.size
            dao.upsertLegacyImportState(
                LegacyImportStateEntity(
                    importedAt = System.currentTimeMillis(),
                    sourceFingerprint = fingerprint,
                    resolved = 0,
                    unresolved = unresolved,
                ),
            )
            LegacyImportResult(
                imported = true,
                playlists = playlists.size,
                legacyTracks = unresolved,
                unresolved = unresolved,
            )
        } finally {
            legacy.close()
        }
    }

    private fun clearPending() {
        playlists.clear(); refs.clear(); sessions.clear(); attempts.clear()
        dailyTrackStats.clear(); dailyAttemptStats.clear(); legacyLyrics.clear()
    }

    private fun readTrackMetadata(db: SQLiteDatabase): Map<String, TrackMeta> {
        if (!hasTable(db, "sync_tracks")) return emptyMap()
        val columns = columns(db, "sync_tracks")
        if ("trackId" !in columns) return emptyMap()
        val title = if ("title" in columns) "title" else "''"
        val artists = if ("artists" in columns) "artists" else "''"
        val album = if ("album" in columns) "album" else "''"
        val raw = if ("rawJson" in columns) "rawJson" else "NULL"
        val from = if (hasTable(db, "sync_plans") && "planId" in columns) {
            "(SELECT t.* FROM sync_tracks t INNER JOIN sync_plans p ON p.planId = t.planId WHERE p.active = 1)"
        } else {
            "sync_tracks"
        }
        val result = linkedMapOf<String, TrackMeta>()
        db.rawQuery("SELECT trackId, $title, $artists, $album, $raw FROM $from", null).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                result[id] = TrackMeta(
                    title = c.getString(1).orEmpty(),
                    artist = c.getString(2).orEmpty(),
                    album = c.getString(3).orEmpty(),
                    favorite = parseFavorite(c.getString(4)),
                    path = rawString(c.getString(4), "path", "file_path", "relativePath"),
                    durationSeconds = rawLong(c.getString(4), "duration", "duration_seconds", "durationSeconds"),
                )
            }
        }
        val paths = readAssetPaths(db)
        paths.forEach { (trackId, path) ->
            result[trackId] = (result[trackId] ?: TrackMeta("", "", "", false)).copy(path = path)
        }
        return result
    }

    private fun readAssetPaths(db: SQLiteDatabase): Map<String, String> {
        if (!hasTable(db, "sync_assets")) return emptyMap()
        val columns = columns(db, "sync_assets")
        if (!listOf("assetId", "relativePath").all { it in columns }) return emptyMap()
        val from = if (hasTable(db, "sync_plans") && "planId" in columns) {
            "(SELECT a.assetId, a.relativePath FROM sync_assets a INNER JOIN sync_plans p ON p.planId = a.planId WHERE p.active = 1)"
        } else {
            "sync_assets"
        }
        val result = linkedMapOf<String, String>()
        db.rawQuery("SELECT assetId, relativePath FROM $from", null).use { c ->
            while (c.moveToNext()) {
                val assetId = c.getString(0) ?: continue
                val path = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                if (assetId.startsWith("audio:")) result[assetId.removePrefix("audio:")] = path
            }
        }
        return result
    }

    private fun importLocalPlaylists(db: SQLiteDatabase) {
        if (!hasTable(db, "local_playlists")) return
        val columns = columns(db, "local_playlists")
        if ("playlistId" !in columns) return
        val name = if ("name" in columns) "name" else "playlistId"
        val artwork = if ("artworkSha256" in columns) "artworkSha256" else "NULL"
        db.rawQuery("SELECT playlistId, $name, $artwork FROM local_playlists", null).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val playlist = playlists.getOrPut(id) { LegacyPlaylist(id, c.getString(1).orEmpty().ifBlank { id }) }
                playlist.artworkSha = c.getString(2)
            }
        }
    }

    private fun importSyncPlaylists(db: SQLiteDatabase) {
        if (!hasTable(db, "sync_playlists")) return
        val columns = columns(db, "sync_playlists")
        if (!listOf("playlistId", "name", "trackIdsJson").all { it in columns }) return
        val from = if (hasTable(db, "sync_plans") && "planId" in columns) {
            "(SELECT s.playlistId, s.name, s.trackIdsJson FROM sync_playlists s " +
                "INNER JOIN sync_plans p ON p.planId = s.planId WHERE p.active = 1)"
        } else {
            "sync_playlists"
        }
        db.rawQuery("SELECT playlistId, name, trackIdsJson FROM $from", null).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1).orEmpty().ifBlank { id }
                val playlist = playlists.getOrPut(id) { LegacyPlaylist(id, name) }
                playlist.name = name
                parseTrackIds(c.getString(2)).forEach { if (it !in playlist.tracks) playlist.tracks += it }
            }
        }
    }

    private fun importMutations(db: SQLiteDatabase, metadata: Map<String, TrackMeta>) {
        if (!hasTable(db, "playlist_mutations")) return
        val columns = columns(db, "playlist_mutations")
        if (!listOf("playlistId", "operation", "payloadJson").all { it in columns }) return
        val updated = if ("updatedAt" in columns) "updatedAt" else "0"
        val mutation = if ("mutationId" in columns) "mutationId" else "playlistId"
        val state = if ("state" in columns) "state" else "'pending'"
        val rows = mutableListOf<Pair<Long, Pair<String, MutationRow>>>()
        db.rawQuery(
            "SELECT playlistId, operation, payloadJson, $updated, $mutation FROM playlist_mutations " +
                "WHERE $state IN ('pending','awaiting_sync')",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val playlistId = c.getString(0) ?: continue
                val operation = c.getString(1).orEmpty().uppercase()
                val payload = runCatching { json.parseToJsonElement(c.getString(2).orEmpty()) as? JsonObject }.getOrNull()
                    ?: continue
                rows += (c.getLong(3) to (c.getString(4).orEmpty() to MutationRow(playlistId, operation, payload)))
            }
        }
        rows.sortedWith(compareBy({ it.first }, { it.second.first })).forEach { (_, pair) ->
            applyMutation(pair.second, metadata)
        }
    }

    private fun applyMutation(row: MutationRow, metadata: Map<String, TrackMeta>) {
        val key = row.payload.string("track_id") ?: row.payload.string("trackId")
        val name = row.payload.string("name")
        val favorite = row.payload.boolean("is_favorite") ?: row.payload.boolean("isFavorite")
        when (row.operation) {
            "CREATE" -> {
                val playlist = playlists.getOrPut(row.playlistId) { LegacyPlaylist(row.playlistId, name ?: row.playlistId) }
                if (name != null) playlist.name = name
            }
            "UPDATE" -> playlists[row.playlistId]?.let { if (name != null) it.name = name }
            "DELETE" -> playlists.remove(row.playlistId)
            "SET_FAVORITE" -> if (key != null) ref(key, metadata).favorite = favorite ?: true
            "ADD_TRACK" -> if (key != null) {
                val playlist = playlists[row.playlistId] ?: return
                if (key in playlist.tracks) return
                val previous = row.payload.string("previous_track_id") ?: row.payload.string("previousTrackId")
                val next = row.payload.string("next_track_id") ?: row.payload.string("nextTrackId")
                val index = when {
                    next != null -> playlist.tracks.indexOf(next).takeIf { it >= 0 }
                    previous != null -> playlist.tracks.indexOf(previous).takeIf { it >= 0 }?.plus(1)
                    else -> null
                }
                if (index == null) playlist.tracks += key else playlist.tracks.add(index, key)
            }
            "MOVE_TRACK" -> if (key != null) {
                val playlist = playlists[row.playlistId] ?: return
                if (key !in playlist.tracks) return
                playlist.tracks.remove(key)
                val next = row.payload.string("next_track_id") ?: row.payload.string("nextTrackId")
                val previous = row.payload.string("previous_track_id") ?: row.payload.string("previousTrackId")
                val index = when {
                    next != null -> playlist.tracks.indexOf(next).takeIf { it >= 0 }
                    previous != null -> playlist.tracks.indexOf(previous).takeIf { it >= 0 }?.plus(1)
                    else -> null
                }
                if (index == null) playlist.tracks += key else playlist.tracks.add(index, key)
            }
            "REMOVE_TRACK" -> if (key != null) playlists[row.playlistId]?.tracks?.remove(key)
            "SET_ARTWORK" -> playlists[row.playlistId]?.artworkSha = row.payload.string("artwork_sha256") ?: row.payload.string("artworkSha256")
            "REMOVE_ARTWORK" -> playlists[row.playlistId]?.artworkSha = null
        }
    }

    private fun importFavorites(metadata: Map<String, TrackMeta>) {
        metadata.filterValues { it.favorite }.forEach { (id, _) -> ref(id, metadata).favorite = true }
    }

    private fun importListening(db: SQLiteDatabase, metadata: Map<String, TrackMeta>) {
        if (hasTable(db, "listening_sessions")) {
            val columns = columns(db, "listening_sessions")
            if (listOf("id", "trackId", "startedAt", "endedAt", "listenedSeconds").all { it in columns }) {
                val qualified = if ("qualifiedPlay" in columns) "qualifiedPlay" else "0"
                val dateColumn = if ("localDate" in columns) "localDate" else "NULL"
                db.rawQuery("SELECT id, trackId, startedAt, endedAt, listenedSeconds, $qualified, $dateColumn FROM listening_sessions", null).use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0) ?: continue
                        val endedAt = c.getLong(3)
                        val date = c.getString(6)?.takeIf { it.isNotBlank() } ?: localDate(endedAt)
                        sessions += LocalListeningSessionEntity(
                            id = "legacy:$id", trackId = c.getString(1).orEmpty(), sourceDeviceId = LOCAL_LEGACY,
                            startedAt = c.getLong(2), endedAt = endedAt, listenedSeconds = c.getInt(4),
                            localDate = date, playCounted = c.getInt(5) != 0,
                        )
                        ref(c.getString(1).orEmpty(), metadata)
                    }
                }
            }
        }
        if (hasTable(db, "playback_attempts")) {
            val columns = columns(db, "playback_attempts")
            if (listOf("id", "trackId", "startedAt", "endedAt", "listenedSeconds").all { it in columns }) {
                val startPos = if ("startPositionMs" in columns) "startPositionMs" else "0"
                val reason = if ("endReason" in columns) "endReason" else "NULL"
                val dateColumn = if ("localDate" in columns) "localDate" else "NULL"
                db.rawQuery("SELECT id, trackId, startedAt, endedAt, $startPos, listenedSeconds, $reason, $dateColumn FROM playback_attempts", null).use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0) ?: continue
                        val endedAt = c.getLong(3)
                        val date = c.getString(7)?.takeIf { it.isNotBlank() } ?: localDate(endedAt)
                        attempts += LocalPlaybackAttemptEntity(
                            id = "legacy:$id", trackId = c.getString(1).orEmpty(), sourceDeviceId = LOCAL_LEGACY,
                            startedAt = c.getLong(2), endedAt = endedAt, startPositionMs = c.getLong(4),
                            listenedSeconds = c.getInt(5), localDate = date, endReason = c.getString(6),
                        )
                        ref(c.getString(1).orEmpty(), metadata)
                    }
                }
            }
        }
        if (hasTable(db, "daily_track_listening_stats")) {
            val columns = columns(db, "daily_track_listening_stats")
            if (listOf("localDate", "trackId", "listenedSeconds", "playCount").all { it in columns }) {
                db.rawQuery("SELECT localDate, trackId, listenedSeconds, playCount FROM daily_track_listening_stats", null).use { c ->
                    while (c.moveToNext()) {
                        val trackId = c.getString(1).orEmpty()
                        dailyTrackStats += LocalDailyTrackStatEntity(
                            sourceDeviceId = LOCAL_LEGACY, localDate = c.getString(0).orEmpty(),
                            trackId = trackId, listenedSeconds = c.getInt(2), playCount = c.getInt(3),
                        )
                        ref(trackId, metadata)
                    }
                }
            }
        }
        if (hasTable(db, "daily_playback_attempt_stats")) {
            val columns = columns(db, "daily_playback_attempt_stats")
            if (listOf("localDate", "attempts", "completed", "skipped", "stopped", "listenedSeconds").all { it in columns }) {
                db.rawQuery("SELECT localDate, attempts, completed, skipped, stopped, listenedSeconds FROM daily_playback_attempt_stats", null).use { c ->
                    while (c.moveToNext()) {
                        dailyAttemptStats += LocalDailyAttemptStatEntity(
                            sourceDeviceId = LOCAL_LEGACY, localDate = c.getString(0).orEmpty(),
                            attempts = c.getInt(1), completed = c.getInt(2), skipped = c.getInt(3),
                            stopped = c.getInt(4), listenedSeconds = c.getInt(5),
                        )
                    }
                }
            }
        }
    }

    private fun importProviderLyrics(db: SQLiteDatabase, metadata: Map<String, TrackMeta>) {
        if (!hasTable(db, "provider_lyrics")) return
        val columns = columns(db, "provider_lyrics")
        if (!listOf("trackId", "content", "source").all { it in columns }) return
        db.rawQuery("SELECT trackId, content, source FROM provider_lyrics", null).use { c ->
            while (c.moveToNext()) {
                val trackId = c.getString(0) ?: continue
                val content = c.getString(1) ?: continue
                legacyLyrics += LegacyLyricsEntity(trackId, content, c.getString(2).orEmpty(), sha256(content))
                ref(trackId, metadata)
            }
        }
    }

    private suspend fun flush(metadata: Map<String, TrackMeta>) {
        playlists.values.forEach { playlist ->
            dao.upsertPlaylist(
                PlaylistEntity(
                    id = "legacy:${playlist.id}",
                    name = playlist.name,
                    artworkKey = playlist.artworkSha?.let { "legacy:$it" },
                ),
            )
            dao.upsertLegacyPlaylistTracks(
                playlist.tracks.mapIndexed { index, trackId ->
                    LegacyPlaylistTrackEntity("legacy:${playlist.id}", trackId, index)
                },
            )
            playlist.tracks.forEach { ref(it, metadata) }
        }
        refs.values.forEach { track ->
            dao.upsertLegacyRef(
                LegacyTrackRefEntity(
                    id = "legacy:${track.id}", legacyTrackId = track.id,
                    title = track.title, artist = track.artist, album = track.album,
                    legacyPath = metadata[track.id]?.path,
                    durationSeconds = metadata[track.id]?.durationSeconds ?: 0L,
                    playlistRefsJson = json.encodeToString(
                        ListSerializer(JsonObject.serializer()),
                        playlists.values.filter { track.id in it.tracks }.map {
                            JsonObject(mapOf("playlistId" to JsonPrimitive("legacy:${it.id}")))
                        },
                    ),
                    legacyFavorite = track.favorite,
                ),
            )
        }
        legacyLyrics.forEach { dao.upsertLegacyLyrics(it) }
        sessions.forEach { dao.insertListeningSession(it) }
        attempts.forEach { dao.insertPlaybackAttempt(it) }
        dao.upsertDailyTrackStats(dailyTrackStats)
        dao.upsertDailyAttemptStats(dailyAttemptStats)
    }

    private fun ref(trackId: String, metadata: Map<String, TrackMeta>): LegacyTrack {
        val meta = metadata[trackId]
        return refs.getOrPut(trackId) {
            LegacyTrack(trackId, meta?.title.orEmpty(), meta?.artist.orEmpty(), meta?.album.orEmpty(), meta?.favorite == true)
        }
    }

    private fun hasTable(db: SQLiteDatabase, name: String): Boolean =
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1", arrayOf(name)).use { it.moveToFirst() }

    private fun columns(db: SQLiteDatabase, table: String): Set<String> =
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(1)) }
        }

    private fun parseTrackIds(value: String?): List<String> = runCatching {
        (json.parseToJsonElement(value.orEmpty()) as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    }.getOrDefault(emptyList())

    private fun rawString(value: String?, vararg keys: String): String? = runCatching {
        val objectValue = json.parseToJsonElement(value.orEmpty()) as? JsonObject
        keys.firstNotNullOfOrNull { objectValue?.get(it)?.let { v -> (v as? JsonPrimitive)?.contentOrNull } }
    }.getOrNull()

    private fun rawLong(value: String?, vararg keys: String): Long = runCatching {
        val objectValue = json.parseToJsonElement(value.orEmpty()) as? JsonObject
        keys.firstNotNullOfOrNull { objectValue?.get(it)?.let { v -> (v as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong() } }
    }.getOrNull() ?: 0L

    private fun parseFavorite(value: String?): Boolean = runCatching {
        val objectValue = json.parseToJsonElement(value.orEmpty()) as? JsonObject
        (objectValue?.get("is_favorite") as? JsonPrimitive)?.content?.toBoolean() == true
    }.getOrDefault(false)

    private fun localDate(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    private fun fingerprint(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(file.length().toString().toByteArray())
        digest.update(file.lastModified().toString().toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

    private data class TrackMeta(
        val title: String,
        val artist: String,
        val album: String,
        val favorite: Boolean,
        val path: String? = null,
        val durationSeconds: Long = 0L,
    )
    private data class MutationRow(val playlistId: String, val operation: String, val payload: JsonObject)
    private class LegacyPlaylist(
        val id: String,
        var name: String,
        val tracks: MutableList<String> = mutableListOf(),
        var artworkSha: String? = null,
    )
    private class LegacyTrack(
        val id: String, val title: String, val artist: String, val album: String, var favorite: Boolean,
    )

    private companion object {
        const val LOCAL_LEGACY = "legacy-local"
    }
}
