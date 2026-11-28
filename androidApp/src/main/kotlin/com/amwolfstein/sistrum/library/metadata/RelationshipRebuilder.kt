package com.amwolfstein.sistrum.library.metadata

import androidx.room.withTransaction
import com.amwolfstein.sistrum.library.db.AlbumArtistEntity
import com.amwolfstein.sistrum.library.db.AlbumEntity
import com.amwolfstein.sistrum.library.db.ArtistEntity
import com.amwolfstein.sistrum.library.db.ComposerEntity
import com.amwolfstein.sistrum.library.db.GenreEntity
import com.amwolfstein.sistrum.library.db.LibraryDatabase
import com.amwolfstein.sistrum.library.db.LibraryItemEntity
import com.amwolfstein.sistrum.library.db.TrackAlbumArtistEntity
import com.amwolfstein.sistrum.library.db.TrackArtistEntity
import com.amwolfstein.sistrum.library.db.TrackComposerEntity
import com.amwolfstein.sistrum.library.db.TrackGenreEntity
import com.amwolfstein.sistrum.library.tags.TagNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

data class RebuildResult(
    val items: Int,
    val artists: Int,
    val genres: Int,
    val composers: Int,
    val albums: Int,
    val regrouped: Int,
)

/**
 * Re-splits every stored raw tag value with the current delimiter configuration and rebuilds the
 * derived artist / album-artist / album / genre / composer entities and junctions.
 *
 * It reads only Room: no audio is opened, no tag is rewritten, and analysis is untouched. Raw
 * values, favorites, ratings, artwork, playlists, lyrics and analysis rows stay as they are.
 * Album identity follows the configured album-artist split, so changing that delimiter groups
 * tracks under the right album without a rescan.
 */
class RelationshipRebuilder(private val database: LibraryDatabase) {
    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(String.serializer())

    suspend fun rebuild(config: SeparatorConfig): RebuildResult = withContext(Dispatchers.IO) {
        database.withTransaction {
            val dao = database.libraryDao()
            val items = dao.allItems()
            // Seed with existing rows so a rebuild never wipes artist artwork or its source.
            val artists = dao.allArtists().associateByTo(linkedMapOf()) { it.id }
            val genres = linkedMapOf<String, GenreEntity>()
            val composers = linkedMapOf<String, ComposerEntity>()
            val existingAlbums = dao.allAlbums()
            val existingAlbumById = existingAlbums.associateBy { it.id }
            val claimedAlbumIds = mutableSetOf<String>()
            // Regrouping re-keys an album id; keep artwork reachable by the title it belonged to.
            val artworkByTitle = existingAlbums
                .filter { it.artworkKey != null }
                .associate { TagNormalizer.normalize(it.title) to (it.artworkKey to it.artworkSource) }

            var regrouped = 0
            val albumBuilders = linkedMapOf<String, AlbumBuilder>()

            items.forEach { original ->
                val artistNames = split(original.rawArtistNames, config.artist)
                val albumArtistNames = split(original.rawAlbumArtistNames, config.albumArtist)
                    .ifEmpty { artistNames }
                val genreNames = split(original.rawGenreNames, config.genre)
                val composerNames = split(original.rawComposerNames, config.composer)

                // Album FKs need their artist rows before the album row is inserted.
                val itemArtists = (artistNames + albumArtistNames).distinct().map { name ->
                    artists.getValue(artistId(artists, name))
                }
                if (itemArtists.isNotEmpty()) dao.upsertArtists(itemArtists)

                val item = regroup(
                    original,
                    albumArtistNames,
                    albumBuilders,
                    artworkByTitle,
                    existingAlbumById,
                    claimedAlbumIds,
                )
                if (item.albumId != original.albumId) regrouped += 1

                val trackArtists = artistNames.mapIndexed { index, name ->
                    TrackArtistEntity(item.id, artistId(artists, name), index)
                }
                val trackAlbumArtists = albumArtistNames.mapIndexed { index, name ->
                    TrackAlbumArtistEntity(item.id, artistId(artists, name), index)
                }
                val trackGenres = genreNames.map { name ->
                    val id = TagNormalizer.key("genre", name)
                    genres[id] = GenreEntity(id = id, name = name, normalizationKey = TagNormalizer.normalize(name))
                    TrackGenreEntity(item.id, id)
                }
                val trackComposers = composerNames.map { name ->
                    val id = TagNormalizer.key("composer", name)
                    composers[id] = ComposerEntity(
                        id = id,
                        name = name,
                        sortName = TagNormalizer.sortName(name),
                        normalizationKey = TagNormalizer.normalize(name),
                    )
                    TrackComposerEntity(item.id, id)
                }

                // Entities must exist before the junctions that reference them.
                val itemGenres = genreNames.map { genres.getValue(TagNormalizer.key("genre", it)) }
                if (itemGenres.isNotEmpty()) dao.upsertGenres(itemGenres)
                val itemComposers = composerNames.map { composers.getValue(TagNormalizer.key("composer", it)) }
                if (itemComposers.isNotEmpty()) dao.upsertComposers(itemComposers)

                dao.deleteTrackArtists(item.id)
                dao.deleteTrackAlbumArtists(item.id)
                dao.deleteTrackGenres(item.id)
                dao.deleteTrackComposers(item.id)
                if (trackArtists.isNotEmpty()) dao.upsertTrackArtists(trackArtists)
                if (trackAlbumArtists.isNotEmpty()) dao.upsertTrackAlbumArtists(trackAlbumArtists)
                if (trackGenres.isNotEmpty()) dao.upsertTrackGenres(trackGenres)
                if (trackComposers.isNotEmpty()) dao.upsertTrackComposers(trackComposers)
            }

            // Albums are derived from the regrouped items, not from their previous ids.
            albumBuilders.values.forEach { builder ->
                val previous = existingAlbumById[builder.id]
                val artwork = builder.artworkKey
                    ?: previous?.artworkKey
                    ?: artworkByTitle[TagNormalizer.normalize(builder.title)]?.first
                dao.upsertAlbums(
                    listOf(
                        AlbumEntity(
                            id = builder.id,
                            title = builder.title,
                            sortTitle = TagNormalizer.sortName(builder.title),
                            normalizationKey = TagNormalizer.normalize(
                                builder.title + "\u0000" + builder.albumArtistNames.firstOrNull().orEmpty(),
                            ),
                            year = builder.year ?: previous?.year,
                            copyright = previous?.copyright,
                            artworkKey = artwork,
                            artworkSource = previous?.artworkSource
                                ?: artworkByTitle[TagNormalizer.normalize(builder.title)]?.second,
                            primaryAlbumArtistId = builder.albumArtistNames.firstOrNull()
                                ?.let { TagNormalizer.key("artist", it) },
                        ),
                    ),
                )
                val relations = builder.albumArtistNames.mapIndexed { index, name ->
                    AlbumArtistEntity(builder.id, artistId(artists, name), index)
                }
                dao.deleteAlbumArtists(builder.id)
                if (relations.isNotEmpty()) {
                    dao.upsertArtists(relations.map { artists.getValue(it.artistId) }.distinctBy { it.id })
                    dao.upsertAlbumArtists(relations)
                    dao.updateAlbumPrimaryArtist(builder.id, relations.first().artistId)
                } else {
                    dao.updateAlbumPrimaryArtist(builder.id, null)
                }
            }

            if (artists.isNotEmpty()) dao.upsertArtists(artists.values.toList())
            if (genres.isNotEmpty()) dao.upsertGenres(genres.values.toList())
            if (composers.isNotEmpty()) dao.upsertComposers(composers.values.toList())

            // Orders matter: album FK uses SET_NULL, so album artists are deleted after rewiring.
            dao.deleteOrphanGenres()
            dao.deleteOrphanComposers()
            dao.deleteOrphanAlbums()
            dao.deleteOrphanArtists()
            com.amwolfstein.sistrum.library.db.SearchIndexer(database).rebuild()

            RebuildResult(
                items = items.size,
                artists = artists.size,
                genres = genres.size,
                composers = composers.size,
                albums = albumBuilders.size,
                regrouped = regrouped,
            )
        }
    }

    /** Recomputes the album identity from the configured album-artist split. */
    private suspend fun regroup(
        item: LibraryItemEntity,
        albumArtistNames: List<String>,
        builders: MutableMap<String, AlbumBuilder>,
        artworkByTitle: Map<String, Pair<String?, String?>>,
        existingAlbumById: Map<String, AlbumEntity>,
        claimedAlbumIds: MutableSet<String>,
    ): LibraryItemEntity {
        val dao = database.libraryDao()
        val albumTitle = item.albumTitle
        val title = albumTitle.ifBlank { "Unknown Album" }
        val firstArtist = albumArtistNames.firstOrNull().orEmpty()
        val normalizationKey = TagNormalizer.normalize(title + "\u0000" + firstArtist)
        val derivedId = TagNormalizer.key("album", "$title\u0000$firstArtist")
        val primaryArtistId = albumArtistNames.firstOrNull()?.let { TagNormalizer.key("artist", it) }
        // Keep the id of a row that already owns this identity, so a rebuild and a rescan agree.
        val adoptable = existingAlbumById.values.firstOrNull { album ->
            album.id !in claimedAlbumIds && album.normalizationKey == normalizationKey
        } ?: existingAlbumById.values.firstOrNull { album ->
            album.id !in claimedAlbumIds && album.id == item.albumId &&
                TagNormalizer.normalize(album.title) == TagNormalizer.normalize(title) &&
                (album.primaryAlbumArtistId == null || album.primaryAlbumArtistId == primaryArtistId)
        }
        val albumId = if (albumTitle.isBlank() && albumArtistNames.isEmpty()) {
            null
        } else {
            (adoptable?.id ?: derivedId).also { claimedAlbumIds += it }
        }
        albumId?.let { id ->
            val builder = builders.getOrPut(id) {
                AlbumBuilder(
                    id = id,
                    title = title,
                    albumArtistNames = albumArtistNames,
                    year = item.year,
                    artworkKey = existingAlbumById[id]?.artworkKey
                        ?: artworkByTitle[TagNormalizer.normalize(title)]?.first,
                )
            }
            if (builder.year == null) builder.year = item.year
        }
        if (albumId == item.albumId) return item
        // The foreign key requires the album row to exist before the item points at it.
        albumId?.let { id ->
            val builder = builders.getValue(id)
            dao.upsertAlbums(
                listOf(
                    AlbumEntity(
                        id = id,
                        title = builder.title,
                        sortTitle = TagNormalizer.sortName(builder.title),
                        normalizationKey = TagNormalizer.normalize(
                            builder.title + "\u0000" + builder.albumArtistNames.firstOrNull().orEmpty(),
                        ),
                        year = builder.year,
                        primaryAlbumArtistId = builder.albumArtistNames.firstOrNull()
                            ?.let { TagNormalizer.key("artist", it) },
                        artworkKey = builder.artworkKey,
                    ),
                ),
            )
        }
        val updated = item.copy(albumId = albumId)
        dao.upsertItem(updated)
        return updated
    }

    private fun artistId(artists: MutableMap<String, ArtistEntity>, name: String): String {
        val id = TagNormalizer.key("artist", name)
        artists.getOrPut(id) {
            ArtistEntity(
                id = id,
                name = name,
                sortName = TagNormalizer.sortName(name),
                normalizationKey = TagNormalizer.normalize(name),
            )
        }
        return id
    }

    /** Raw values are JSON arrays as written by the scanner; anything else is one literal value. */
    private fun split(raw: String, delimiters: List<String>): List<String> {
        val values = decode(raw)
        return if (delimiters.isEmpty()) values.distinct() else Separators.splitAll(values, delimiters)
    }

    private fun decode(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return runCatching { json.decodeFromString(listSerializer, raw) }.getOrElse {
            listOf(raw).map { it.trim() }.filter { it.isNotEmpty() }
        }
    }

    private class AlbumBuilder(
        val id: String,
        val title: String,
        val albumArtistNames: List<String>,
        var year: Int?,
        val artworkKey: String?,
    )
}
