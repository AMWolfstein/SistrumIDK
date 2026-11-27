package com.amwolfstein.sistrum.library.tags

import com.amwolfstein.sistrum.library.metadata.SeparatorCategory
import com.amwolfstein.sistrum.library.metadata.SeparatorConfig
import com.amwolfstein.sistrum.library.metadata.Separators

/** Deterministic ids for the entities derived from one audio file. */
data class ResolvedTrack(
    val itemId: String,
    val artistIds: List<String>,
    val albumId: String?,
    val albumArtistId: String?,
    val albumArtistIds: List<String>,
    val genreIds: List<String>,
    val composerIds: List<String>,
    val title: String,
    val sortTitle: String,
    val albumTitle: String,
    val albumSortTitle: String,
    val artistNames: List<String>,
    val albumArtistNames: List<String>,
    val genreNames: List<String>,
    val composerNames: List<String>,
    val releaseDate: String?,
)

class EntityResolver(private val separators: SeparatorConfig = SeparatorConfig()) {
    fun resolve(metadata: TagMetadata, relativePath: String, documentKey: String): ResolvedTrack {
        val title = TagAliases.first(metadata, TagAliases.titleKeys)
            ?: metadata.title
            ?: TagNormalizer.fileTitle(relativePath)
        val artists = names(metadata, TagAliases.artistKeys, metadata.artist, SeparatorCategory.ARTIST)
        val albumArtists = names(metadata, TagAliases.albumArtistKeys, metadata.albumArtist, SeparatorCategory.ALBUM_ARTIST)
            .ifEmpty { artists }
        val albumTitle = TagAliases.first(metadata, TagAliases.albumKeys) ?: metadata.album ?: ""
        val genres = names(metadata, listOf("GENRE"), metadata.genre, SeparatorCategory.GENRE)
        val composers = names(metadata, listOf("COMPOSER"), metadata.composer, SeparatorCategory.COMPOSER)
        val albumKeyName = albumTitle.ifBlank { "Unknown Album" }
        val albumKeyArtist = albumArtists.firstOrNull().orEmpty()

        return ResolvedTrack(
            itemId = TagNormalizer.key("track", documentKey),
            artistIds = artists.map { TagNormalizer.key("artist", it) }.distinct(),
            albumId = if (albumTitle.isBlank() && albumArtists.isEmpty()) {
                null
            } else {
                TagNormalizer.key("album", "$albumKeyName\u0000$albumKeyArtist")
            },
            albumArtistId = albumArtists.firstOrNull()?.let { TagNormalizer.key("artist", it) },
            albumArtistIds = albumArtists.map { TagNormalizer.key("artist", it) }.distinct(),
            genreIds = genres.map { TagNormalizer.key("genre", it) }.distinct(),
            composerIds = composers.map { TagNormalizer.key("composer", it) }.distinct(),
            title = title,
            sortTitle = TagNormalizer.sortName(title),
            albumTitle = albumTitle,
            albumSortTitle = TagNormalizer.sortName(albumTitle),
            artistNames = artists,
            albumArtistNames = albumArtists,
            genreNames = genres,
            composerNames = composers,
            releaseDate = TagAliases.first(metadata, TagAliases.dateKeys)
                ?: metadata.year?.toString(),
        )
    }

    private fun names(
        metadata: TagMetadata,
        keys: List<String>,
        fallback: String?,
        category: SeparatorCategory,
    ): List<String> {
        val raw = TagAliases.all(metadata, keys)
            .ifEmpty { listOfNotNull(fallback).map { it.trim() }.filter { it.isNotEmpty() } }
        return if (separators.delimitersFor(category).isEmpty()) raw.distinct() else
            Separators.splitAll(raw, separators.delimitersFor(category))
    }
}
