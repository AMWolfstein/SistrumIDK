package com.amwolfstein.sistrum.library.metadata

import androidx.room.withTransaction
import com.amwolfstein.sistrum.library.db.AlbumArtistEntity
import com.amwolfstein.sistrum.library.db.ArtistEntity
import com.amwolfstein.sistrum.library.db.ComposerEntity
import com.amwolfstein.sistrum.library.db.GenreEntity
import com.amwolfstein.sistrum.library.db.LibraryDatabase
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
)

/**
 * Re-splits every stored raw tag value with the current delimiter configuration and rebuilds the
 * derived artist / album-artist / genre / composer entities and junctions.
 *
 * It reads only Room: no audio is opened, no tag is rewritten, and analysis is untouched. Raw
 * values, favorites, ratings, artwork, playlists, lyrics and analysis rows stay as they are.
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

            items.forEach { item ->
                val artistNames = split(item.rawArtistNames, config.artist)
                val albumArtistNames = split(item.rawAlbumArtistNames, config.albumArtist)
                    .ifEmpty { artistNames }
                val genreNames = split(item.rawGenreNames, config.genre)
                val composerNames = split(item.rawComposerNames, config.composer)

                val trackArtists = artistNames.mapIndexed { index, name ->
                    val id = artistId(artists, name)
                    TrackArtistEntity(item.id, id, index)
                }
                val trackAlbumArtists = albumArtistNames.mapIndexed { index, name ->
                    val id = artistId(artists, name)
                    TrackAlbumArtistEntity(item.id, id, index)
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
                val itemArtists = (artistNames + albumArtistNames).distinct().map { name ->
                    artists.getValue(TagNormalizer.key("artist", name))
                }
                if (itemArtists.isNotEmpty()) dao.upsertArtists(itemArtists)
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

            // Album artists are the union of their tracks' configured album-artist names.
            val albums = dao.allAlbums()
            var albumsUpdated = 0
            albums.forEach { album ->
                val albumArtistNames = dao.itemsForAlbum(album.id)
                    .flatMap { item ->
                        split(item.rawAlbumArtistNames, config.albumArtist)
                            .ifEmpty { split(item.rawArtistNames, config.artist) }
                    }
                    .distinct()
                val relations = albumArtistNames.mapIndexed { index, name ->
                    AlbumArtistEntity(album.id, artistId(artists, name), index)
                }
                dao.deleteAlbumArtists(album.id)
                if (relations.isNotEmpty()) {
                    dao.upsertArtists(relations.map { artists.getValue(it.artistId) }.distinctBy { it.id })
                    dao.upsertAlbumArtists(relations)
                    dao.updateAlbumPrimaryArtist(album.id, relations.first().artistId)
                } else {
                    dao.updateAlbumPrimaryArtist(album.id, null)
                }
                albumsUpdated += 1
            }

            if (artists.isNotEmpty()) dao.upsertArtists(artists.values.toList())
            if (genres.isNotEmpty()) dao.upsertGenres(genres.values.toList())
            if (composers.isNotEmpty()) dao.upsertComposers(composers.values.toList())

            // Orders matter: album FK uses SET_NULL, so album artists are deleted after rewiring.
            dao.deleteOrphanGenres()
            dao.deleteOrphanComposers()
            dao.deleteOrphanAlbums()
            dao.deleteOrphanArtists()

            RebuildResult(
                items = items.size,
                artists = artists.size,
                genres = genres.size,
                composers = composers.size,
                albums = albumsUpdated,
            )
        }
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
}
