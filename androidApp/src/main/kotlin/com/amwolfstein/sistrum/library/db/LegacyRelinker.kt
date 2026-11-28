package com.amwolfstein.sistrum.library.db

import androidx.room.withTransaction
import com.amwolfstein.sistrum.library.playlist.LexoRank
import com.amwolfstein.sistrum.library.tags.TagNormalizer

data class LegacyRelinkResult(
    val resolved: Int = 0,
    val unresolved: Int = 0,
    val playlistEntries: Int = 0,
    val favorites: Int = 0,
)

/**
 * Re-links imported legacy playlist and favorite references to real library items.
 *
 * The legacy mirror is read-only history: this pass never edits the old database. It
 * runs after a successful scan so that unresolved references can be matched by
 * normalized metadata, then by the original path's file stem, then by duration.
 * Anything still unmatched stays visible as `unresolved` for the migration report.
 */
class LegacyRelinker(
    private val database: LibraryDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.libraryDao()

    suspend fun relinkIfNeeded(): LegacyRelinkResult = database.withTransaction {
        if (dao.legacyImportState() == null) return@withTransaction LegacyRelinkResult()
        val unresolved = dao.unresolvedLegacyRefs()
        if (unresolved.isEmpty()) {
            val state = dao.legacyImportState()!!
            return@withTransaction LegacyRelinkResult(resolved = state.resolved, unresolved = 0)
        }
        val items = dao.allItems().filter { it.available }
        if (items.isEmpty()) return@withTransaction LegacyRelinkResult(unresolved = unresolved.size)

        val byMetadata = items.groupBy { key(it.title, it.rawArtistNames(), it.albumTitle) }
        val byStem = items.groupBy { stem(it.displayPath) }
        val byTitleDuration = items.groupBy { key(it.title, it.rawArtistNames(), "") }

        var resolvedCount = 0
        var favorites = 0
        val resolvedByLegacyId = mutableMapOf<String, LibraryItemEntity>()
        unresolved.forEach { ref ->
            val (item, resolution) = match(ref, byMetadata, byStem, byTitleDuration) ?: return@forEach
            resolvedByLegacyId[ref.legacyTrackId] = item
            dao.upsertLegacyRef(
                ref.copy(
                    resolvedItemId = item.id,
                    resolution = resolution,
                    resolvedAt = now(),
                ),
            )
            resolvedCount += 1
            if (ref.legacyFavorite && !item.isFavorite) {
                dao.updateFavorite(item.id, true)
                favorites += 1
            }
        }

        var playlistEntries = 0
        if (resolvedByLegacyId.isNotEmpty()) {
            dao.legacyPlaylists().forEach { playlist ->
                val existing = dao.playlistTracksList(playlist.id).map { it.trackId }.toMutableSet()
                var position = dao.playlistTracksList(playlist.id).maxOfOrNull { it.position }
                dao.legacyPlaylistTracks(playlist.id)
                    .sortedBy { it.position }
                    .forEach { reference ->
                        val item = resolvedByLegacyId[reference.legacyTrackId] ?: return@forEach
                        if (!existing.add(item.id)) return@forEach
                        position = LexoRank.after(position ?: "")
                        dao.upsertPlaylistTracks(
                            listOf(PlaylistTrackEntity(playlist.id, item.id, position)),
                        )
                        playlistEntries += 1
                    }
            }
        }

        val state = dao.legacyImportState()!!
        dao.upsertLegacyImportState(
            state.copy(
                resolved = state.resolved + resolvedCount,
                unresolved = dao.unresolvedLegacyRefCount(),
            ),
        )
        LegacyRelinkResult(
            resolved = resolvedCount,
            unresolved = dao.unresolvedLegacyRefCount(),
            playlistEntries = playlistEntries,
            favorites = favorites,
        )
    }

    private fun match(
        ref: LegacyTrackRefEntity,
        byMetadata: Map<String, List<LibraryItemEntity>>,
        byStem: Map<String, List<LibraryItemEntity>>,
        byTitleDuration: Map<String, List<LibraryItemEntity>>,
    ): Pair<LibraryItemEntity, String>? {
        val metadataKey = key(ref.title, ref.artist, ref.album)
        unique(byMetadata[metadataKey])?.let { return it to "metadata" }
        unique(byStem[stem(ref.legacyPath)])?.let { return it to "path" }
        return unique(byTitleDuration[key(ref.title, ref.artist, "")])
            ?.takeIf { ref.durationSeconds <= 0 || kotlin.math.abs(it.durationSeconds - ref.durationSeconds) <= 2 }
            ?.let { it to "duration" }
    }

    private fun unique(candidates: List<LibraryItemEntity>?): LibraryItemEntity? =
        candidates?.takeIf { it.size == 1 }?.single()

    private fun key(title: String, artist: String, album: String): String = listOf(title, artist, album)
        .joinToString("\u0000") { TagNormalizer.normalize(it) }

    private fun stem(path: String?): String = (path ?: "")
        .substringAfterLast('/')
        .substringBeforeLast('.', "")
        .let { TagNormalizer.normalize(it) }

    private fun LibraryItemEntity.rawArtistNames(): String =
        runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(rawArtistNames)
                .let { element ->
                    (element as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                        ?.joinToString("|")
                }
        }.getOrNull().orEmpty()
}
