package com.amwolfstein.sistrum.library.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        LibrarySourceEntity::class,
        LibraryItemEntity::class,
        ItemSourceEntity::class,
        ArtistEntity::class,
        AlbumEntity::class,
        GenreEntity::class,
        ComposerEntity::class,
        TrackArtistEntity::class,
        TrackAlbumArtistEntity::class,
        TrackGenreEntity::class,
        TrackComposerEntity::class,
        AlbumArtistEntity::class,
        ArtworkAssetEntity::class,
        PlaylistEntity::class,
        PlaylistTrackEntity::class,
        LyricsEntity::class,
        TrackAnalysisEntity::class,
        LocalListeningSessionEntity::class,
        LocalPlaybackAttemptEntity::class,
        LocalDailyTrackStatEntity::class,
        LocalDailyAttemptStatEntity::class,
        ScanRunEntity::class,
        ScanEntryEntity::class,
        ScanErrorEntity::class,
        LibraryExclusionEntity::class,
        LegacyTrackRefEntity::class,
        LegacyLyricsEntity::class,
        LegacyPlaylistTrackEntity::class,
        TagWriteJournalEntity::class,
        LegacyImportStateEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao

    companion object {
        const val NAME = "library.db"

        @Volatile private var instance: LibraryDatabase? = null

        fun get(context: Context): LibraryDatabase =
            instance ?: synchronized(this) {
                instance ?: create(context).also { instance = it }
            }

        fun create(context: Context): LibraryDatabase =
            Room.databaseBuilder(context.applicationContext, LibraryDatabase::class.java, NAME)
                .addCallback(ratingCheck())
                .build()

        /** Room has no @Check; keep 0..5 ratings enforced at the database boundary. */
        fun ratingCheck(): RoomDatabase.Callback = object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TRIGGER IF NOT EXISTS rating_insert BEFORE INSERT ON library_items " +
                        "WHEN NEW.rating IS NOT NULL AND (NEW.rating < 0 OR NEW.rating > 5) " +
                        "BEGIN SELECT RAISE(ABORT, 'rating must be between 0 and 5'); END",
                )
                db.execSQL(
                    "CREATE TRIGGER IF NOT EXISTS rating_update BEFORE UPDATE OF rating ON library_items " +
                        "WHEN NEW.rating IS NOT NULL AND (NEW.rating < 0 OR NEW.rating > 5) " +
                        "BEGIN SELECT RAISE(ABORT, 'rating must be between 0 and 5'); END",
                )
            }
        }
    }
}
