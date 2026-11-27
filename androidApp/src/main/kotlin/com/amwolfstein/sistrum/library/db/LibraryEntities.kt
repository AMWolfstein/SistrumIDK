package com.amwolfstein.sistrum.library.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "library_sources",
    indices = [Index(value = ["treeUri"], unique = true)],
)
data class LibrarySourceEntity(
    @PrimaryKey val id: String,
    val treeUri: String,
    val displayName: String,
    val volumeId: String? = null,
    val isRemovable: Boolean = false,
    val enabled: Boolean = true,
    val available: Boolean = true,
    val lastScannedAt: Long? = null,
    val lastSeenAt: Long? = null,
    val lastScanError: String? = null,
    val createdAt: Long = 0L,
)

@Entity(
    tableName = "artists",
    indices = [Index(value = ["normalizationKey"], unique = true)],
)
data class ArtistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val sortName: String,
    val normalizationKey: String,
    val artworkKey: String? = null,
    val artworkSource: String? = null,
    val artworkUpdatedAt: Long? = null,
)

@Entity(
    tableName = "albums",
    indices = [
        Index(value = ["normalizationKey"], unique = true),
        Index("primaryAlbumArtistId"),
    ],
    foreignKeys = [
        ForeignKey(
            entity = ArtistEntity::class,
            parentColumns = ["id"],
            childColumns = ["primaryAlbumArtistId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
)
data class AlbumEntity(
    @PrimaryKey val id: String,
    val title: String,
    val sortTitle: String,
    val normalizationKey: String,
    val year: Int? = null,
    val copyright: String? = null,
    val artworkKey: String? = null,
    val artworkSource: String? = null,
    val primaryAlbumArtistId: String? = null,
)

@Entity(
    tableName = "genres",
    indices = [Index(value = ["normalizationKey"], unique = true)],
)
data class GenreEntity(
    @PrimaryKey val id: String,
    val name: String,
    val normalizationKey: String,
)

@Entity(
    tableName = "composers",
    indices = [Index(value = ["normalizationKey"], unique = true)],
)
data class ComposerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val sortName: String,
    val normalizationKey: String,
)

@Entity(
    tableName = "library_items",
    indices = [
        Index(value = ["documentKey"], unique = true),
        Index("sourceId"),
        Index(value = ["sourceId", "available"]),
        Index("albumId"),
        Index("sortTitle"),
        Index("isFavorite"),
        Index("addedAt"),
        Index("releaseDate"),
        Index("contentKey"),
    ],
    foreignKeys = [
        ForeignKey(
            entity = LibrarySourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["id"],
            childColumns = ["albumId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
)
data class LibraryItemEntity(
    @PrimaryKey val id: String,
    val documentKey: String,
    val fileUri: String,
    val sourceId: String,
    val displayPath: String? = null,
    val cueKey: String? = null,
    val trackNumberInCue: Int? = null,
    val cueStartMs: Long? = null,
    val cueEndMs: Long? = null,
    val fileModTime: Long = 0L,
    val fileSize: Long? = null,
    val metadataVersion: Int = 0,
    val albumId: String? = null,
    val title: String = "",
    val sortTitle: String = "",
    val albumTitle: String = "",
    val albumSortTitle: String = "",
    val rawArtistNames: String = "",
    val rawAlbumArtistNames: String = "",
    val rawGenreNames: String = "",
    val rawComposerNames: String = "",
    val copyright: String? = null,
    val label: String? = null,
    val isrc: String? = null,
    val year: Int? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val totalTracks: Int? = null,
    val totalDiscs: Int? = null,
    val durationSeconds: Long = 0L,
    val bitrate: Int? = null,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
    val codec: String? = null,
    val format: String? = null,
    val releaseDate: String? = null,
    val isFavorite: Boolean = false,
    val rating: Int? = null,
    val playCount: Int = 0,
    val artworkKey: String? = null,
    val audioFingerprint: String? = null,
    val contentKey: String? = null,
    val addedAt: Long = 0L,
    val updatedAt: Long = 0L,
    val available: Boolean = true,
    val missingSince: Long? = null,
    val otherMetadataJson: String = "{}",
    val analysisStatus: String = "NONE",
)

@Entity(
    tableName = "item_sources",
    primaryKeys = ["itemId", "sourceId"],
    indices = [Index("sourceId")],
    foreignKeys = [
        ForeignKey(
            entity = LibraryItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = LibrarySourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ItemSourceEntity(
    val itemId: String,
    val sourceId: String,
    val fileUri: String,
)

@Entity(
    tableName = "track_artists",
    primaryKeys = ["trackId", "artistId"],
    indices = [Index("artistId")],
    foreignKeys = [
        ForeignKey(entity = LibraryItemEntity::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ArtistEntity::class, parentColumns = ["id"], childColumns = ["artistId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class TrackArtistEntity(val trackId: String, val artistId: String, val position: Int)

@Entity(
    tableName = "track_album_artists",
    primaryKeys = ["trackId", "artistId"],
    indices = [Index("artistId")],
    foreignKeys = [
        ForeignKey(entity = LibraryItemEntity::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ArtistEntity::class, parentColumns = ["id"], childColumns = ["artistId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class TrackAlbumArtistEntity(val trackId: String, val artistId: String, val position: Int)

@Entity(
    tableName = "track_genres",
    primaryKeys = ["trackId", "genreId"],
    indices = [Index("genreId")],
    foreignKeys = [
        ForeignKey(entity = LibraryItemEntity::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = GenreEntity::class, parentColumns = ["id"], childColumns = ["genreId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class TrackGenreEntity(val trackId: String, val genreId: String)

@Entity(
    tableName = "track_composers",
    primaryKeys = ["trackId", "composerId"],
    indices = [Index("composerId")],
    foreignKeys = [
        ForeignKey(entity = LibraryItemEntity::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ComposerEntity::class, parentColumns = ["id"], childColumns = ["composerId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class TrackComposerEntity(val trackId: String, val composerId: String)

@Entity(
    tableName = "album_artists",
    primaryKeys = ["albumId", "artistId"],
    indices = [Index("artistId")],
    foreignKeys = [
        ForeignKey(entity = AlbumEntity::class, parentColumns = ["id"], childColumns = ["albumId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ArtistEntity::class, parentColumns = ["id"], childColumns = ["artistId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class AlbumArtistEntity(val albumId: String, val artistId: String, val position: Int)

@Entity(tableName = "artwork_assets")
data class ArtworkAssetEntity(
    @PrimaryKey val key: String,
    val kind: String,
    val width: Int,
    val height: Int,
    val mime: String,
    val sizeBytes: Long,
    val path: String,
    val origin: String? = null,
    val createdAt: Long = 0L,
    val lastUsedAt: Long = 0L,
    val active: Boolean = true,
)

@Entity(tableName = "playlists", indices = [Index(value = ["systemKind"], unique = true)])
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String = "",
    val isSmart: Boolean = false,
    val rulesJson: String? = null,
    val liveUpdating: Boolean = true,
    val limitEnabled: Boolean = false,
    val limitCount: Int = 25,
    val limitSort: String = "random",
    val pinnedAt: Long? = null,
    val artworkKey: String? = null,
    val systemKind: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "trackId"],
    indices = [Index("trackId"), Index(value = ["playlistId", "position", "trackId"])],
    foreignKeys = [
        ForeignKey(entity = PlaylistEntity::class, parentColumns = ["id"], childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = LibraryItemEntity::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class PlaylistTrackEntity(
    val playlistId: String,
    val trackId: String,
    val position: String,
)

@Entity(
    tableName = "lyrics",
    foreignKeys = [ForeignKey(entity = LibraryItemEntity::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE)],
)
data class LyricsEntity(
    @PrimaryKey val trackId: String,
    val content: String,
    val source: String,
    val synced: Boolean = false,
    val offsetMs: Long = 0L,
    val fingerprint: String,
    val updatedAt: Long = 0L,
    val embeddedContent: String? = null,
    val embeddedSource: String? = null,
)

@Entity(
    tableName = "track_analysis",
    foreignKeys = [ForeignKey(entity = LibraryItemEntity::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE)],
)
data class TrackAnalysisEntity(
    @PrimaryKey val trackId: String,
    val loudnessLufs: Double? = null,
    val loudnessRange: Double? = null,
    val truePeak: Double? = null,
    val rms: Double? = null,
    val crest: Double? = null,
    val spectralCentroid: Double? = null,
    val spectralRolloff: Double? = null,
    val spectralFlatness: Double? = null,
    val spectralFlux: Double? = null,
    val zcr: Double? = null,
    val tempo: Double? = null,
    val onsetVariance: Double? = null,
    val energy: Double? = null,
    val danceability: Double? = null,
    val brightness: Double? = null,
    val ffmpegVersion: Int = 0,
    val aubioVersion: Int = 0,
    val analyzerVersion: Int = 0,
    val moodVersion: Int = 0,
    val audioFingerprint: String? = null,
    val source: String = "local",
    val status: String = "PENDING",
    val analyzedAt: Long? = null,
)

@Entity(tableName = "listening_sessions", indices = [Index("trackId"), Index("endedAt")])
data class LocalListeningSessionEntity(
    @PrimaryKey val id: String,
    val trackId: String,
    val sourceDeviceId: String,
    val startedAt: Long,
    val endedAt: Long,
    val listenedSeconds: Int,
    val localDate: String,
    val playCounted: Boolean = false,
)

@Entity(tableName = "playback_attempts", indices = [Index("trackId"), Index("endedAt")])
data class LocalPlaybackAttemptEntity(
    @PrimaryKey val id: String,
    val trackId: String,
    val sourceDeviceId: String,
    val startedAt: Long,
    val endedAt: Long,
    val startPositionMs: Long = 0L,
    val listenedSeconds: Int,
    val localDate: String,
    val endReason: String? = null,
)

@Entity(tableName = "daily_track_listening_stats", primaryKeys = ["sourceDeviceId", "localDate", "trackId"])
data class LocalDailyTrackStatEntity(
    val sourceDeviceId: String,
    val localDate: String,
    val trackId: String,
    val listenedSeconds: Int,
    val playCount: Int,
)

@Entity(tableName = "daily_playback_attempt_stats", primaryKeys = ["sourceDeviceId", "localDate"])
data class LocalDailyAttemptStatEntity(
    val sourceDeviceId: String,
    val localDate: String,
    val attempts: Int,
    val completed: Int,
    val skipped: Int,
    val stopped: Int,
    val listenedSeconds: Int,
)

@Entity(
    tableName = "scan_runs",
    indices = [Index("sourceId")],
    foreignKeys = [ForeignKey(entity = LibrarySourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)],
)
data class ScanRunEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val treeUri: String,
    val mode: String,
    val generation: Long,
    val state: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val totalFiles: Int = 0,
    val scannedFiles: Int = 0,
    val errorCount: Int = 0,
    val cancelled: Boolean = false,
)

@Entity(
    tableName = "scan_entries",
    primaryKeys = ["runId", "documentKey"],
    indices = [Index("runId"), Index("documentKey")],
    foreignKeys = [ForeignKey(entity = ScanRunEntity::class, parentColumns = ["id"], childColumns = ["runId"], onDelete = ForeignKey.CASCADE)],
)
data class ScanEntryEntity(
    val runId: String,
    val documentKey: String,
    val fileUri: String,
    val modTime: Long,
    val size: Long? = null,
    val state: String = "SEEN",
    val error: String? = null,
    val relativePath: String = "",
    val name: String = "",
    val cueKey: String? = null,
    val cueTrackNumber: Int? = null,
    val cueStartMs: Long? = null,
    val cueEndMs: Long? = null,
    val cueTitle: String? = null,
    val cuePerformer: String? = null,
    val changed: Boolean = false,
)

@Entity(
    tableName = "scan_errors",
    indices = [Index("runId")],
    foreignKeys = [ForeignKey(entity = ScanRunEntity::class, parentColumns = ["id"], childColumns = ["runId"], onDelete = ForeignKey.CASCADE)],
)
data class ScanErrorEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: String,
    val documentKey: String? = null,
    val operation: String,
    val message: String,
    val occurredAt: Long,
)

@Entity(
    tableName = "library_exclusions",
    indices = [Index(value = ["sourceId"])],
    foreignKeys = [ForeignKey(entity = LibrarySourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)],
)
data class LibraryExclusionEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val relativePath: String,
    val kind: String,
    val createdAt: Long = 0L,
)

@Entity(
    tableName = "legacy_track_refs",
    indices = [Index(value = ["legacyTrackId"], unique = true)],
)
data class LegacyTrackRefEntity(
    @PrimaryKey val id: String,
    val legacyTrackId: String,
    val legacyPath: String? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationSeconds: Long = 0L,
    val playlistRefsJson: String = "[]",
    val legacyFavorite: Boolean = false,
    val resolvedItemId: String? = null,
    val resolution: String = "unresolved",
    val resolvedAt: Long? = null,
)

@Entity(tableName = "tag_write_journal")
data class TagWriteJournalEntity(
    @PrimaryKey val id: String,
    val trackId: String,
    val operation: String,
    val stagedPath: String,
    val originalFingerprint: String?,
    val state: String,
    val error: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "legacy_lyrics")
data class LegacyLyricsEntity(
    @PrimaryKey val trackId: String,
    val content: String,
    val source: String,
    val fingerprint: String,
)

@Entity(
    tableName = "legacy_playlist_tracks",
    primaryKeys = ["playlistId", "legacyTrackId"],
    indices = [Index("legacyTrackId"), Index(value = ["playlistId", "position"])],
)
data class LegacyPlaylistTrackEntity(
    val playlistId: String,
    val legacyTrackId: String,
    val position: Int,
)

@Entity(tableName = "legacy_import_state")
data class LegacyImportStateEntity(
    @PrimaryKey val id: Int = 1,
    val importedAt: Long,
    val sourceFingerprint: String,
    val resolved: Int,
    val unresolved: Int,
)

/** Read model for the artist-artwork precedence decision. */
data class ArtistArtworkRow(
    val trackId: String,
    val releaseDate: String?,
    val artworkKey: String?,
)

/** One library item with its split relationship names, for local catalog mapping. */
data class LocalTrackRow(
    @androidx.room.Embedded val item: LibraryItemEntity,
    val artistNames: String? = null,
    val albumArtistNames: String? = null,
    val genreNames: String? = null,
    val composerNames: String? = null,
)

data class LocalAlbumRow(
    @androidx.room.Embedded val album: AlbumEntity,
    val artistName: String? = null,
    val trackCount: Int = 0,
)

data class LocalArtistRow(
    @androidx.room.Embedded val artist: ArtistEntity,
    val trackCount: Int = 0,
)

data class LocalGenreRow(
    @androidx.room.Embedded val genre: GenreEntity,
    val trackCount: Int = 0,
)

data class LocalComposerRow(
    @androidx.room.Embedded val composer: ComposerEntity,
    val trackCount: Int = 0,
)

data class LocalListeningRow(val trackId: String, val lastListenedAt: Long)
