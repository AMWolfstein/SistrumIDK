package com.amwolfstein.sistrum.library.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import androidx.room.Upsert

@Dao
interface LibraryDao {
    @Upsert
    suspend fun upsertSource(source: LibrarySourceEntity)

    @Upsert
    suspend fun upsertSources(sources: List<LibrarySourceEntity>)

    @Query("SELECT * FROM library_sources ORDER BY isRemovable, displayName")
    suspend fun sources(): List<LibrarySourceEntity>

    @Query("SELECT * FROM library_sources ORDER BY isRemovable, displayName")
    fun observeSources(): Flow<List<LibrarySourceEntity>>

    @Query("UPDATE library_sources SET enabled = :enabled WHERE id = :id")
    suspend fun setSourceEnabled(id: String, enabled: Boolean)

    @Query("UPDATE library_sources SET available = :available, lastScanError = :error WHERE id = :id")
    suspend fun setSourceAvailability(id: String, available: Boolean, error: String? = null)

    @Query("SELECT * FROM library_sources WHERE id = :id")
    suspend fun source(id: String): LibrarySourceEntity?

    @Query("DELETE FROM library_sources WHERE id = :id")
    suspend fun deleteSource(id: String)

    @Upsert
    suspend fun upsertItem(item: LibraryItemEntity)

    @Upsert
    suspend fun upsertItems(items: List<LibraryItemEntity>)

    @Query("SELECT * FROM library_items WHERE documentKey = :documentKey")
    suspend fun itemByDocumentKey(documentKey: String): LibraryItemEntity?

    @Query("SELECT * FROM library_items WHERE contentKey = :contentKey")
    suspend fun itemsByContentKey(contentKey: String): List<LibraryItemEntity>

    @Query(
        "SELECT library_items.* FROM library_items " +
            "INNER JOIN item_sources ON library_items.id = item_sources.itemId " +
            "WHERE item_sources.sourceId = :sourceId",
    )
    suspend fun itemsForSource(sourceId: String): List<LibraryItemEntity>

    @Query("SELECT * FROM item_sources WHERE itemId = :itemId ORDER BY sourceId, fileUri")
    suspend fun itemSources(itemId: String): List<ItemSourceEntity>

    @Query("SELECT * FROM item_sources WHERE sourceId = :sourceId ORDER BY itemId, fileUri")
    suspend fun itemSourcesForSource(sourceId: String): List<ItemSourceEntity>

    @Query("SELECT * FROM library_items WHERE id = :itemId")
    suspend fun itemById(itemId: String): LibraryItemEntity?

    @Query("DELETE FROM item_sources WHERE itemId = :itemId AND sourceId = :sourceId")
    suspend fun deleteItemSource(itemId: String, sourceId: String)

    @Query("DELETE FROM item_sources WHERE itemId = :itemId AND sourceId != :keepSourceId")
    suspend fun deleteItemSourcesExcept(itemId: String, keepSourceId: String)

    @Query(
        "DELETE FROM library_items WHERE sourceId = :sourceId " +
            "AND id NOT IN (SELECT itemId FROM item_sources)",
    )
    suspend fun deleteOrphanItemsForSource(sourceId: String)

    @Query("DELETE FROM track_artists WHERE trackId = :trackId")
    suspend fun deleteTrackArtists(trackId: String)

    @Query("DELETE FROM track_album_artists WHERE trackId = :trackId")
    suspend fun deleteTrackAlbumArtists(trackId: String)

    @Query("DELETE FROM track_genres WHERE trackId = :trackId")
    suspend fun deleteTrackGenres(trackId: String)

    @Query("DELETE FROM track_composers WHERE trackId = :trackId")
    suspend fun deleteTrackComposers(trackId: String)

    @Query("SELECT * FROM library_items ORDER BY id")
    suspend fun allItems(): List<LibraryItemEntity>

    @Query("SELECT * FROM albums ORDER BY id")
    suspend fun allAlbums(): List<AlbumEntity>

    @Query("SELECT * FROM artists ORDER BY id")
    suspend fun allArtists(): List<ArtistEntity>

    @Query("SELECT * FROM library_items WHERE albumId = :albumId ORDER BY id")
    suspend fun itemsForAlbum(albumId: String): List<LibraryItemEntity>

    @Query("DELETE FROM album_artists WHERE albumId = :albumId")
    suspend fun deleteAlbumArtists(albumId: String)

    @Query("UPDATE albums SET primaryAlbumArtistId = :artistId WHERE id = :albumId")
    suspend fun updateAlbumPrimaryArtist(albumId: String, artistId: String?)

    @Query(
        "DELETE FROM artists WHERE id NOT IN (" +
            "SELECT artistId FROM track_artists " +
            "UNION SELECT artistId FROM track_album_artists " +
            "UNION SELECT artistId FROM album_artists " +
            "UNION SELECT primaryAlbumArtistId FROM albums WHERE primaryAlbumArtistId IS NOT NULL)",
    )
    suspend fun deleteOrphanArtists()

    @Query("DELETE FROM genres WHERE id NOT IN (SELECT genreId FROM track_genres)")
    suspend fun deleteOrphanGenres()

    @Query("DELETE FROM composers WHERE id NOT IN (SELECT composerId FROM track_composers)")
    suspend fun deleteOrphanComposers()

    @Query(
        "DELETE FROM albums WHERE id NOT IN " +
            "(SELECT albumId FROM library_items WHERE albumId IS NOT NULL)",
    )
    suspend fun deleteOrphanAlbums()

    @Query(
        "SELECT library_items.*, " +
            "(SELECT GROUP_CONCAT(name, char(1)) FROM (SELECT artists.name AS name FROM track_artists " +
            "JOIN artists ON artists.id = track_artists.artistId " +
            "WHERE track_artists.trackId = library_items.id)) AS artistNames, " +
            "(SELECT GROUP_CONCAT(name, char(1)) FROM (SELECT artists.name AS name FROM track_album_artists " +
            "JOIN artists ON artists.id = track_album_artists.artistId " +
            "WHERE track_album_artists.trackId = library_items.id)) AS albumArtistNames, " +
            "(SELECT GROUP_CONCAT(name, char(1)) FROM (SELECT genres.name AS name FROM track_genres " +
            "JOIN genres ON genres.id = track_genres.genreId " +
            "WHERE track_genres.trackId = library_items.id)) AS genreNames, " +
            "(SELECT GROUP_CONCAT(name, char(1)) FROM (SELECT composers.name AS name FROM track_composers " +
            "JOIN composers ON composers.id = track_composers.composerId " +
            "WHERE track_composers.trackId = library_items.id)) AS composerNames " +
            "FROM library_items WHERE available = 1",
    )
    fun observeLocalTracks(): Flow<List<LocalTrackRow>>

    @Query(
        "SELECT albums.*, (SELECT artists.name FROM artists WHERE artists.id = albums.primaryAlbumArtistId) AS artistName, " +
            "(SELECT COUNT(*) FROM library_items WHERE library_items.albumId = albums.id AND available = 1) AS trackCount " +
            "FROM albums WHERE EXISTS (SELECT 1 FROM library_items WHERE library_items.albumId = albums.id AND available = 1)",
    )
    fun observeLocalAlbums(): Flow<List<LocalAlbumRow>>

    @Query(
        "SELECT artists.*, (SELECT COUNT(DISTINCT trackId) FROM track_artists WHERE artistId = artists.id) AS trackCount " +
            "FROM artists WHERE trackCount > 0",
    )
    fun observeLocalArtists(): Flow<List<LocalArtistRow>>

    @Query(
        "SELECT genres.*, (SELECT COUNT(DISTINCT trackId) FROM track_genres WHERE genreId = genres.id) AS trackCount " +
            "FROM genres WHERE trackCount > 0",
    )
    fun observeLocalGenres(): Flow<List<LocalGenreRow>>

    @Query(
        "SELECT composers.*, (SELECT COUNT(DISTINCT trackId) FROM track_composers WHERE composerId = composers.id) AS trackCount " +
            "FROM composers WHERE trackCount > 0",
    )
    fun observeLocalComposers(): Flow<List<LocalComposerRow>>

    @Query("SELECT * FROM artwork_assets")
    fun observeArtworkAssets(): Flow<List<ArtworkAssetEntity>>

    @Query(
        "SELECT trackId, MAX(endedAt) AS lastListenedAt FROM listening_sessions GROUP BY trackId",
    )
    fun observeLocalLastListened(): Flow<List<LocalListeningRow>>

    @Query("UPDATE library_items SET rating = :rating WHERE id = :trackId")
    suspend fun updateRating(trackId: String, rating: Int?)

    @Query("UPDATE library_items SET isFavorite = :favorite WHERE id = :trackId")
    suspend fun updateFavorite(trackId: String, favorite: Boolean)

    @Query("SELECT COUNT(*) FROM library_items")
    suspend fun itemCount(): Int

    @Query("SELECT COUNT(*) FROM library_items WHERE available = 1")
    suspend fun availableItemCount(): Int

    @Update
    suspend fun updateItem(item: LibraryItemEntity)

    @Upsert
    suspend fun upsertArtists(values: List<ArtistEntity>)

    @Upsert
    suspend fun upsertAlbums(values: List<AlbumEntity>)

    @Upsert
    suspend fun upsertGenres(values: List<GenreEntity>)

    @Upsert
    suspend fun upsertComposers(values: List<ComposerEntity>)

    @Upsert
    suspend fun upsertTrackArtists(values: List<TrackArtistEntity>)

    @Upsert
    suspend fun upsertTrackAlbumArtists(values: List<TrackAlbumArtistEntity>)

    @Upsert
    suspend fun upsertTrackGenres(values: List<TrackGenreEntity>)

    @Upsert
    suspend fun upsertTrackComposers(values: List<TrackComposerEntity>)

    @Upsert
    suspend fun upsertAlbumArtists(values: List<AlbumArtistEntity>)

    @Upsert
    suspend fun upsertItemSources(values: List<ItemSourceEntity>)

    @Upsert
    suspend fun upsertArtwork(values: List<ArtworkAssetEntity>)

    @Query("SELECT * FROM artwork_assets WHERE key = :key")
    suspend fun artwork(key: String): ArtworkAssetEntity?

    @Query("SELECT * FROM artwork_assets ORDER BY lastUsedAt")
    suspend fun artworkAssets(): List<ArtworkAssetEntity>

    @Query("UPDATE artwork_assets SET lastUsedAt = :timestamp, active = 1 WHERE key = :key")
    suspend fun touchArtwork(key: String, timestamp: Long)

    @Query("DELETE FROM artwork_assets WHERE key = :key")
    suspend fun deleteArtwork(key: String)

    @Query(
        "SELECT artworkKey FROM library_items WHERE artworkKey IS NOT NULL " +
            "UNION SELECT artworkKey FROM albums WHERE artworkKey IS NOT NULL " +
            "UNION SELECT artworkKey FROM artists WHERE artworkKey IS NOT NULL " +
            "UNION SELECT artworkKey FROM playlists WHERE artworkKey IS NOT NULL",
    )
    suspend fun referencedArtworkKeys(): List<String>

    @Query("SELECT * FROM artists WHERE id = :id")
    suspend fun artist(id: String): ArtistEntity?

    @Query("UPDATE artists SET artworkKey = :key, artworkSource = :source, artworkUpdatedAt = :updatedAt WHERE id = :id")
    suspend fun updateArtistArtwork(id: String, key: String?, source: String?, updatedAt: Long? = null)

    @Query("SELECT * FROM albums WHERE id = :id")
    suspend fun album(id: String): AlbumEntity?

    @Query("UPDATE albums SET artworkKey = :key, artworkSource = :source WHERE id = :id")
    suspend fun updateAlbumArtwork(id: String, key: String?, source: String? = null)

    @Query("UPDATE library_items SET artworkKey = :key WHERE id = :id")
    suspend fun updateItemArtwork(id: String, key: String?)

    @Query("SELECT * FROM library_items WHERE artworkKey IS NULL AND available = 1 ORDER BY addedAt LIMIT :limit")
    suspend fun itemsMissingArtwork(limit: Int): List<LibraryItemEntity>

    @Query(
        "SELECT * FROM library_items WHERE sourceId = :sourceId AND artworkKey IS NULL " +
            "AND available = 1 ORDER BY addedAt LIMIT :limit",
    )
    suspend fun itemsMissingArtworkForSource(sourceId: String, limit: Int): List<LibraryItemEntity>

    @Query(
        "SELECT * FROM library_items WHERE sourceId = :sourceId AND artworkKey IS NULL " +
            "AND available = 1 AND id > :afterId ORDER BY id LIMIT :limit",
    )
    suspend fun itemsMissingArtworkForSourceAfter(
        sourceId: String,
        afterId: String,
        limit: Int,
    ): List<LibraryItemEntity>

    @Query(
        "SELECT * FROM artists WHERE artworkSource IS NULL OR artworkSource != 'DEEZER' " +
            "ORDER BY CASE WHEN artworkUpdatedAt IS NULL THEN 0 ELSE 1 END, artworkUpdatedAt, name " +
            "LIMIT :limit",
    )
    suspend fun artistsMissingDeezerArtwork(limit: Int): List<ArtistEntity>

    @Query(
        "SELECT * FROM library_items WHERE sourceId = :sourceId AND displayPath = :path " +
            "AND available = 1",
    )
    suspend fun itemsAtPath(sourceId: String, path: String): List<LibraryItemEntity>

    @Query(
        "SELECT library_items.id AS trackId, library_items.releaseDate AS releaseDate, " +
            "library_items.artworkKey AS artworkKey FROM library_items " +
            "WHERE library_items.id IN (" +
            "SELECT trackId FROM track_artists WHERE artistId = :artistId " +
            "UNION SELECT trackId FROM track_album_artists WHERE artistId = :artistId" +
            ") AND library_items.available = 1",
    )
    suspend fun artworkCandidatesForArtist(artistId: String): List<ArtistArtworkRow>

    @Query(
        "SELECT DISTINCT artistId FROM track_artists WHERE trackId = :itemId " +
            "UNION SELECT artistId FROM track_album_artists WHERE trackId = :itemId",
    )
    suspend fun artistsForItem(itemId: String): List<String>

    @Upsert
    suspend fun upsertPlaylist(value: PlaylistEntity)

    @Upsert
    suspend fun upsertPlaylists(values: List<PlaylistEntity>)

    @Query("SELECT * FROM playlists ORDER BY pinnedAt DESC, name COLLATE NOCASE")
    suspend fun playlists(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists ORDER BY pinnedAt DESC, name COLLATE NOCASE")
    fun observePlaylists(): Flow<List<PlaylistEntity>>

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylist(id: String)

    @Query("SELECT * FROM playlist_tracks ORDER BY playlistId, position, trackId")
    fun observeAllPlaylistTracks(): Flow<List<PlaylistTrackEntity>>

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun playlistTrack(playlistId: String, trackId: String): PlaylistTrackEntity?

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun deletePlaylistTrack(playlistId: String, trackId: String)

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY position, trackId")
    suspend fun playlistTracksList(playlistId: String): List<PlaylistTrackEntity>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun playlist(id: String): PlaylistEntity?

    @Upsert
    suspend fun upsertPlaylistTracks(values: List<PlaylistTrackEntity>)

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY position, trackId")
    suspend fun playlistTracks(playlistId: String): List<PlaylistTrackEntity>

    @Upsert
    suspend fun upsertLyrics(value: LyricsEntity)

    @Query("SELECT * FROM lyrics WHERE trackId = :trackId")
    suspend fun lyrics(trackId: String): LyricsEntity?

    @Query("SELECT * FROM lyrics WHERE trackId = :trackId")
    fun observeLyrics(trackId: String): Flow<LyricsEntity?>

    @Upsert
    suspend fun upsertAnalysis(value: TrackAnalysisEntity)

    @Query("SELECT * FROM track_analysis WHERE trackId = :trackId")
    suspend fun analysis(trackId: String): TrackAnalysisEntity?

    @Query(
        "SELECT track_analysis.* FROM track_analysis " +
            "INNER JOIN library_items ON library_items.id = track_analysis.trackId " +
            "WHERE library_items.albumId = :albumId AND track_analysis.status = 'COMPLETED' " +
            "AND track_analysis.loudnessLufs IS NOT NULL AND track_analysis.truePeak IS NOT NULL",
    )
    suspend fun analysesForAlbum(albumId: String): List<TrackAnalysisEntity>

    @Query(
        "SELECT track_analysis.trackId AS trackId, library_items.albumId AS albumId, " +
            "albums.primaryAlbumArtistId AS primaryArtistId, track_analysis.energy AS energy, " +
            "track_analysis.danceability AS danceability, track_analysis.brightness AS brightness, " +
            "track_analysis.tempo AS tempo FROM track_analysis " +
            "INNER JOIN library_items ON library_items.id = track_analysis.trackId " +
            "LEFT JOIN albums ON albums.id = library_items.albumId " +
            "WHERE library_items.available = 1 AND track_analysis.status = 'COMPLETED' " +
            "AND track_analysis.energy IS NOT NULL AND track_analysis.danceability IS NOT NULL " +
            "AND track_analysis.brightness IS NOT NULL AND track_analysis.tempo IS NOT NULL",
    )
    suspend fun moodRadioRows(): List<MoodRadioRow>

    @Query(
        "SELECT library_items.* FROM library_items LEFT JOIN track_analysis " +
            "ON track_analysis.trackId = library_items.id " +
            "WHERE library_items.available = 1 AND " +
            "(track_analysis.trackId IS NULL OR track_analysis.status != 'COMPLETED' " +
            "OR track_analysis.analyzerVersion != :analyzerVersion) LIMIT :limit",
    )
    suspend fun itemsNeedingAnalysis(limit: Int, analyzerVersion: Int = 1): List<LibraryItemEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertListeningSession(value: LocalListeningSessionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlaybackAttempt(value: LocalPlaybackAttemptEntity)

    @Upsert
    suspend fun upsertDailyTrackStats(values: List<LocalDailyTrackStatEntity>)

    @Upsert
    suspend fun upsertDailyAttemptStats(values: List<LocalDailyAttemptStatEntity>)

    @Query("SELECT COUNT(*) FROM listening_sessions")
    suspend fun listeningSessionCount(): Int

    @Upsert
    suspend fun upsertScanRun(value: ScanRunEntity)

    @Query("SELECT * FROM scan_runs WHERE sourceId = :sourceId ORDER BY startedAt DESC LIMIT 1")
    suspend fun latestScanRun(sourceId: String): ScanRunEntity?

    @Upsert
    suspend fun upsertScanEntries(values: List<ScanEntryEntity>)

    @Query("SELECT * FROM scan_entries WHERE runId = :runId")
    suspend fun scanEntries(runId: String): List<ScanEntryEntity>

    @Query("DELETE FROM scan_entries WHERE runId = :runId")
    suspend fun deleteScanEntries(runId: String)

    @Query("SELECT * FROM scan_runs WHERE state IN ('RUNNING','PAUSED') ORDER BY startedAt DESC")
    suspend fun unfinishedScanRuns(): List<ScanRunEntity>

    @Query("SELECT * FROM scan_runs WHERE id = :runId")
    suspend fun scanRun(runId: String): ScanRunEntity?

    @Query("SELECT * FROM scan_runs WHERE sourceId = :sourceId AND state = 'COMPLETED' ORDER BY startedAt DESC LIMIT 1")
    suspend fun latestCompletedScanRun(sourceId: String): ScanRunEntity?

    @Query("SELECT * FROM library_exclusions WHERE sourceId = :sourceId")
    suspend fun exclusions(sourceId: String): List<LibraryExclusionEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertScanError(value: ScanErrorEntity)

    @Upsert
    suspend fun upsertExclusions(values: List<LibraryExclusionEntity>)

    @Upsert
    suspend fun upsertLegacyRef(value: LegacyTrackRefEntity)

    @Query("SELECT * FROM legacy_track_refs WHERE legacyTrackId = :legacyTrackId")
    suspend fun legacyRef(legacyTrackId: String): LegacyTrackRefEntity?

    @Upsert
    suspend fun upsertLegacyLyrics(value: LegacyLyricsEntity)

    @Upsert
    suspend fun upsertLegacyPlaylistTracks(values: List<LegacyPlaylistTrackEntity>)

    @Query("SELECT * FROM legacy_playlist_tracks WHERE playlistId = :playlistId ORDER BY position")
    suspend fun legacyPlaylistTracks(playlistId: String): List<LegacyPlaylistTrackEntity>

    @Query("SELECT * FROM legacy_lyrics WHERE trackId = :trackId")
    suspend fun legacyLyrics(trackId: String): LegacyLyricsEntity?

    @Query("SELECT COUNT(*) FROM legacy_track_refs WHERE resolution = 'unresolved'")
    suspend fun unresolvedLegacyRefCount(): Int

    @Upsert
    suspend fun upsertLegacyImportState(value: LegacyImportStateEntity)

    @Query("SELECT * FROM legacy_import_state WHERE id = 1")
    suspend fun legacyImportState(): LegacyImportStateEntity?

    @Upsert
    suspend fun upsertTagWriteJournal(value: TagWriteJournalEntity)

    @Query("SELECT * FROM tag_write_journal WHERE state IN ('STAGED','VERIFIED','WRITING','FAILED') ORDER BY createdAt")
    suspend fun incompleteTagWrites(): List<TagWriteJournalEntity>
}
