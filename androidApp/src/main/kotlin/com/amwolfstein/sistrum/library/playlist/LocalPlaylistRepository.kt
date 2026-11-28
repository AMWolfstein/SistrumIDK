package com.amwolfstein.sistrum.library.playlist

import android.content.Context
import androidx.room.withTransaction
import com.amwolfstein.sistrum.library.artwork.ArtworkCache
import com.amwolfstein.sistrum.library.db.LibraryDatabase
import com.amwolfstein.sistrum.library.db.PlaylistEntity
import com.amwolfstein.sistrum.library.db.PlaylistTrackEntity
import com.amwolfstein.sistrum.sync.LibraryPlaylist
import com.amwolfstein.sistrum.sync.PlaylistMutation
import com.amwolfstein.sistrum.sync.PlaylistMutationOperation
import com.amwolfstein.sistrum.sync.StagedPlaylistArtwork
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Local manual playlists: create/update/delete, ordered membership with fractional ranks,
 * custom artwork, and favorites as a virtual playlist derived from `library_items.isFavorite`.
 */
class LocalPlaylistRepository(
    private val context: Context,
    private val database: LibraryDatabase = LibraryDatabase.get(context),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.libraryDao()
    private val artworkCache = ArtworkCache(context, dao, now)

    fun observePlaylists(
        libraryTracks: Flow<List<com.amwolfstein.sistrum.sync.LibraryTrack>>,
    ): Flow<List<LibraryPlaylist>> = combine(
        dao.observePlaylists(),
        dao.observeAllPlaylistTracks(),
        libraryTracks,
    ) { rows, memberships, tracks ->
        rows.asSequence()
            .filter { it.systemKind != FAVORITES_KIND }
            .map { playlist ->
                val storedIds = memberships.filter { it.playlistId == playlist.id }
                    .sortedWith(compareBy({ it.position }, { it.trackId }))
                    .map { it.trackId }
                val ids = if (playlist.isSmart && playlist.liveUpdating) {
                    SmartPlaylistJson.decode(playlist.rulesJson)
                        ?.let { config -> SmartRules.evaluate(config, tracks).map { it.id } }
                        ?: emptyList()
                } else {
                    storedIds
                }
                LibraryPlaylist(
                    id = playlist.id,
                    name = playlist.name,
                    trackIds = ids,
                    metadataJson = metadataJson(playlist),
                    syncFailed = false,
                )
            }
            .toList()
    }

    suspend fun create(
        mutation: PlaylistMutation,
        artwork: StagedPlaylistArtwork?,
        initialTrackIds: List<String>,
    ) = withContext(Dispatchers.IO) {
        require(mutation.operation == PlaylistMutationOperation.CREATE) { "Expected playlist create mutation" }
        val name = mutation.payload.name?.trim().orEmpty()
        require(name.isNotEmpty()) { "Playlist name cannot be empty" }
        val playlistId = mutation.playlistId
        val artworkKey = artwork?.let { importArtwork(it) }
        database.withTransaction {
            dao.upsertPlaylist(
                PlaylistEntity(
                    id = playlistId,
                    name = name,
                    description = mutation.payload.description.orEmpty(),
                    artworkKey = artworkKey,
                    createdAt = now(),
                    updatedAt = now(),
                ),
            )
            var previous: String? = null
            initialTrackIds.distinct().filter(String::isNotBlank).forEach { trackId ->
                if (dao.itemById(trackId) == null) return@forEach
                val rank = LexoRank.between(previous, null)
                dao.upsertPlaylistTracks(listOf(PlaylistTrackEntity(playlistId, trackId, rank)))
                previous = rank
            }
        }
    }

    /** Creates a smart playlist; a frozen (non-live) playlist materialises its match once. */
    suspend fun createSmart(
        name: String,
        config: SmartPlaylistConfig,
        libraryTracks: List<com.amwolfstein.sistrum.sync.LibraryTrack>,
        id: String = java.util.UUID.randomUUID().toString(),
    ): String = withContext(Dispatchers.IO) {
        SmartRules.validate(config)?.let { error(it) }
        val matched = SmartRules.evaluate(config, libraryTracks)
        database.withTransaction {
            dao.upsertPlaylist(
                PlaylistEntity(
                    id = id,
                    name = name,
                    isSmart = true,
                    rulesJson = SmartPlaylistJson.encode(config),
                    liveUpdating = config.liveUpdating,
                    limitEnabled = config.limit.enabled,
                    limitCount = config.limit.count,
                    limitSort = config.limit.by,
                    createdAt = now(),
                    updatedAt = now(),
                ),
            )
            replaceSnapshot(id, if (config.liveUpdating) emptyList() else matched.map { it.id })
        }
        id
    }

    suspend fun updateSmart(
        playlistId: String,
        name: String,
        config: SmartPlaylistConfig,
        libraryTracks: List<com.amwolfstein.sistrum.sync.LibraryTrack>,
    ) = withContext(Dispatchers.IO) {
        SmartRules.validate(config)?.let { error(it) }
        val playlist = dao.playlist(playlistId) ?: return@withContext
        require(playlist.isSmart) { "Not a smart playlist" }
        val matched = SmartRules.evaluate(config, libraryTracks)
        database.withTransaction {
            dao.upsertPlaylist(
                playlist.copy(
                    name = name.trim().takeIf { it.isNotBlank() } ?: playlist.name,
                    rulesJson = SmartPlaylistJson.encode(config),
                    liveUpdating = config.liveUpdating,
                    limitEnabled = config.limit.enabled,
                    limitCount = config.limit.count,
                    limitSort = config.limit.by,
                    updatedAt = now(),
                ),
            )
            replaceSnapshot(playlistId, if (config.liveUpdating) emptyList() else matched.map { it.id })
        }
    }

    private suspend fun replaceSnapshot(playlistId: String, trackIds: List<String>) {
        dao.playlistTracksList(playlistId).forEach { dao.deletePlaylistTrack(playlistId, it.trackId) }
        var previous: String? = null
        trackIds.distinct().forEach { trackId ->
            val rank = LexoRank.between(previous, null)
            dao.upsertPlaylistTracks(listOf(PlaylistTrackEntity(playlistId, trackId, rank)))
            previous = rank
        }
    }

    suspend fun createWithoutMutation(name: String, initialTrackIds: List<String> = emptyList()): String {
        val id = java.util.UUID.randomUUID().toString()
        create(
            PlaylistMutation(
                mutationId = java.util.UUID.randomUUID().toString(),
                playlistId = id,
                operation = PlaylistMutationOperation.CREATE,
                updatedAt = now(),
                payload = com.amwolfstein.sistrum.sync.PlaylistMutationPayload(name = name),
            ),
            artwork = null,
            initialTrackIds = initialTrackIds,
        )
        return id
    }

    suspend fun update(playlistId: String, name: String?, description: String?) = withContext(Dispatchers.IO) {
        val playlist = dao.playlist(playlistId) ?: return@withContext
        require(playlist.systemKind != FAVORITES_KIND) { "Favorites cannot be renamed" }
        val newName = name?.trim()?.takeIf { it.isNotEmpty() }
        dao.upsertPlaylist(
            playlist.copy(
                name = newName ?: playlist.name,
                description = description ?: playlist.description,
                updatedAt = now(),
            ),
        )
    }

    suspend fun delete(playlistId: String) = withContext(Dispatchers.IO) {
        val playlist = dao.playlist(playlistId) ?: return@withContext
        require(playlist.systemKind != FAVORITES_KIND) { "Favorites cannot be deleted" }
        dao.deletePlaylist(playlistId)
    }

    suspend fun addTrack(playlistId: String, trackId: String, index: Int? = null) = withContext(Dispatchers.IO) {
        if (dao.playlist(playlistId)?.systemKind == FAVORITES_KIND) return@withContext
        if (dao.itemById(trackId) == null) return@withContext
        if (dao.playlistTrack(playlistId, trackId) != null) return@withContext
        val existing = dao.playlistTracksList(playlistId)
        val position = index?.coerceIn(0, existing.size) ?: existing.size
        val previous = existing.getOrNull(position - 1)?.position
        val next = existing.getOrNull(position)?.position
        dao.upsertPlaylistTracks(
            listOf(PlaylistTrackEntity(playlistId, trackId, LexoRank.between(previous, next))),
        )
        touch(playlistId)
    }

    suspend fun removeTrack(playlistId: String, trackId: String) = withContext(Dispatchers.IO) {
        if (dao.playlist(playlistId)?.systemKind == FAVORITES_KIND) return@withContext
        dao.deletePlaylistTrack(playlistId, trackId)
        touch(playlistId)
    }

    /** Moves a track between the given neighbours; a null neighbour means start or end. */
    suspend fun moveTrack(
        playlistId: String,
        trackId: String,
        previousTrackId: String?,
        nextTrackId: String?,
    ) = withContext(Dispatchers.IO) {
        if (dao.playlist(playlistId)?.systemKind == FAVORITES_KIND) return@withContext
        val rows = dao.playlistTracksList(playlistId)
        val previous = rows.firstOrNull { it.trackId == previousTrackId }?.position
        val next = rows.firstOrNull { it.trackId == nextTrackId }?.position
        if (previous != null && next != null && previous >= next) {
            // Neighbours are now reversed (concurrent move); fall back to appending.
            val last = rows.lastOrNull { it.trackId != trackId }?.position
            dao.upsertPlaylistTracks(listOf(PlaylistTrackEntity(playlistId, trackId, LexoRank.after(last ?: "U"))))
        } else {
            dao.upsertPlaylistTracks(
                listOf(PlaylistTrackEntity(playlistId, trackId, LexoRank.between(previous, next))),
            )
        }
        touch(playlistId)
    }

    suspend fun setArtwork(playlistId: String, staged: StagedPlaylistArtwork?) = withContext(Dispatchers.IO) {
        val playlist = dao.playlist(playlistId) ?: return@withContext
        require(playlist.systemKind != FAVORITES_KIND) { "Favorites artwork is derived" }
        val key = staged?.let { importArtwork(it) }
        dao.upsertPlaylist(playlist.copy(artworkKey = key, updatedAt = now()))
    }

    suspend fun applyMutation(mutation: PlaylistMutation, artwork: StagedPlaylistArtwork? = null) {
        when (mutation.operation) {
            PlaylistMutationOperation.CREATE -> {
                val trackId = mutation.payload.trackId
                create(mutation, artwork, listOfNotNull(trackId))
            }
            PlaylistMutationOperation.UPDATE ->
                update(mutation.playlistId, mutation.payload.name, mutation.payload.description)
            PlaylistMutationOperation.DELETE -> delete(mutation.playlistId)
            PlaylistMutationOperation.ADD_TRACK ->
                mutation.payload.trackId?.let { addTrack(mutation.playlistId, it) }
            PlaylistMutationOperation.REMOVE_TRACK ->
                mutation.payload.trackId?.let { removeTrack(mutation.playlistId, it) }
            PlaylistMutationOperation.MOVE_TRACK ->
                mutation.payload.trackId?.let {
                    moveTrack(mutation.playlistId, it, mutation.payload.previousTrackId, mutation.payload.nextTrackId)
                }
            PlaylistMutationOperation.SET_ARTWORK -> setArtwork(mutation.playlistId, artwork)
            PlaylistMutationOperation.REMOVE_ARTWORK -> setArtwork(mutation.playlistId, null)
            PlaylistMutationOperation.SET_FAVORITE ->
                mutation.payload.trackId?.let { dao.updateFavorite(it, mutation.payload.isFavorite ?: true) }
        }
    }

    private suspend fun importArtwork(staged: StagedPlaylistArtwork): String? {
        val file = File(context.filesDir, staged.relativePath)
        if (!file.isFile) return null
        return artworkCache.store(file.readBytes(), kind = "PLAYLIST", origin = "USER")?.key
    }

    private suspend fun touch(playlistId: String) {
        val playlist = dao.playlist(playlistId) ?: return
        dao.upsertPlaylist(playlist.copy(updatedAt = now()))
    }

    private fun metadataJson(playlist: PlaylistEntity): String = buildJsonObject {
        put("description", playlist.description)
        playlist.artworkKey?.let { put("artwork_key", it) }
        put("is_smart", playlist.isSmart)
        playlist.rulesJson?.let { put("rules", Json.parseToJsonElement(it)) }
    }.toString()

    private companion object {
        const val FAVORITES_KIND = "favorites"
    }
}
