package com.amwolfstein.sistrum.library.db

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * Builds the transactional full-text index for tracks, albums, artists, genres,
 * composers and playlists. The whole index is swapped inside one Room
 * transaction, so a search never sees a half-updated library.
 */
class SearchIndexer(private val database: LibraryDatabase) {
    private val dao = database.libraryDao()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun rebuild(): Int = withContext(Dispatchers.IO) {
        database.withTransaction {
            dao.clearSearchIndex()
            val rows = mutableListOf<LibrarySearchEntity>()
            dao.allItems().forEach { item ->
                rows += row(
                    kind = "track",
                    entityId = item.id,
                    title = item.title,
                    subtitle = item.albumTitle.orEmpty(),
                    terms = decode(item.rawArtistNames) + decode(item.rawAlbumArtistNames) +
                        decode(item.rawGenreNames) + decode(item.rawComposerNames),
                )
            }
            dao.allAlbums().forEach { album ->
                rows += row("album", album.id, album.title, "", emptyList())
            }
            dao.allArtists().forEach { artist ->
                rows += row("artist", artist.id, artist.name, artist.sortName, emptyList())
            }
            dao.allGenres().forEach { genre ->
                rows += row("genre", genre.id, genre.name, "", emptyList())
            }
            dao.allComposers().forEach { composer ->
                rows += row("composer", composer.id, composer.name, composer.sortName, emptyList())
            }
            dao.playlists().forEach { playlist ->
                rows += row("playlist", playlist.id, playlist.name, "", emptyList())
            }
            if (rows.isNotEmpty()) dao.insertSearch(rows)
            rows.size
        }
    }

    /** Re-indexes one track after a tag edit without rebuilding the whole library. */
    suspend fun indexItem(item: LibraryItemEntity) = withContext(Dispatchers.IO) {
        database.withTransaction {
            dao.insertSearch(
                listOf(
                    row(
                        kind = "track",
                        entityId = item.id,
                        title = item.title,
                        subtitle = item.albumTitle,
                        terms = decode(item.rawArtistNames) + decode(item.rawAlbumArtistNames) +
                            decode(item.rawGenreNames) + decode(item.rawComposerNames),
                    ),
                ),
            )
        }
    }

    /** Re-indexes a playlist name after create/rename. */
    suspend fun indexPlaylist(playlist: PlaylistEntity) = withContext(Dispatchers.IO) {
        database.withTransaction {
            dao.insertSearch(listOf(row("playlist", playlist.id, playlist.name, "", emptyList())))
        }
    }

    /**
     * Prefix MATCH first; a LIKE pass covers scripts the unicode61 tokenizer does
     * not segment (for example contiguous CJK) and single characters.
     */
    suspend fun search(text: String, limit: Int = 40): List<LibrarySearchHit> = withContext(Dispatchers.IO) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()
        val match = SearchIndexSql.matchQuery(trimmed)
        val fts = if (match.isBlank()) emptyList() else {
            runCatching {
                dao.searchRaw(
                    SimpleSQLiteQuery(
                        "SELECT kind, entityId FROM library_search_fts WHERE library_search_fts MATCH ? " +
                            "ORDER BY title LIMIT ?",
                        arrayOf<Any>(match, limit),
                    ),
                )
            }.getOrDefault(emptyList())
        }
        if (fts.size >= limit) return@withContext fts.take(limit)
        val seen = fts.toMutableSet()
        val pattern = "%" + trimmed.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val like = runCatching {
            dao.searchRaw(
                SimpleSQLiteQuery(
                    "SELECT kind, entityId FROM library_search WHERE title LIKE ? ESCAPE '\\' " +
                        "OR subtitle LIKE ? ESCAPE '\\' OR terms LIKE ? ESCAPE '\\' ORDER BY title LIMIT ?",
                    arrayOf<Any>(pattern, pattern, pattern, limit),
                ),
            )
        }.getOrDefault(emptyList())
        (fts + like.filter { it !in seen }).take(limit)
    }

    private fun row(
        kind: String,
        entityId: String,
        title: String,
        subtitle: String,
        terms: List<String>,
    ) = LibrarySearchEntity(
        id = "$kind:$entityId",
        kind = kind,
        entityId = entityId,
        title = title,
        subtitle = subtitle,
        terms = terms.distinct().joinToString(" "),
    )

    private fun decode(raw: String): List<String> =
        runCatching {
            (json.parseToJsonElement(raw) as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content }
                .orEmpty()
        }.getOrDefault(emptyList())
}
