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

    @Upsert
    suspend fun upsertPlaylist(value: PlaylistEntity)

    @Upsert
    suspend fun upsertPlaylists(values: List<PlaylistEntity>)

    @Query("SELECT * FROM playlists ORDER BY pinnedAt DESC, name COLLATE NOCASE")
    suspend fun playlists(): List<PlaylistEntity>

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

    @Upsert
    suspend fun upsertAnalysis(value: TrackAnalysisEntity)

    @Query("SELECT * FROM track_analysis WHERE trackId = :trackId")
    suspend fun analysis(trackId: String): TrackAnalysisEntity?

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

    @Query("SELECT * FROM tag_write_journal WHERE state IN ('STAGED','VERIFIED')")
    suspend fun incompleteTagWrites(): List<TagWriteJournalEntity>
}
