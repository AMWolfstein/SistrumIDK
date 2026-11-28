package com.amwolfstein.sistrum.library.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.migration.Migration

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
        LibrarySearchEntity::class,
        LibrarySearchFtsEntity::class,
    ],
    version = 4,
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .addCallback(ratingCheck())
                .build()

        /** Adds the per-document metadata and CUE timing needed to resume a scan without re-reading. */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scan_entries ADD COLUMN relativePath TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE scan_entries ADD COLUMN name TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE scan_entries ADD COLUMN cueKey TEXT")
                db.execSQL("ALTER TABLE scan_entries ADD COLUMN cueTrackNumber INTEGER")
                db.execSQL("ALTER TABLE scan_entries ADD COLUMN cueStartMs INTEGER")
                db.execSQL("ALTER TABLE scan_entries ADD COLUMN cueEndMs INTEGER")
                db.execSQL("ALTER TABLE scan_entries ADD COLUMN cueTitle TEXT")
                db.execSQL("ALTER TABLE scan_entries ADD COLUMN cuePerformer TEXT")
                db.execSQL("ALTER TABLE scan_entries ADD COLUMN changed INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Adds the transactional full-text index and its synchronization triggers. */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS library_search (" +
                        "id TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, entityId TEXT NOT NULL, " +
                        "title TEXT NOT NULL, subtitle TEXT NOT NULL, terms TEXT NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_library_search_kind ON library_search(kind)")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_library_search_kind_entityId ON library_search(kind, entityId)",
                )
                SearchIndexSql.createFts(db)
                SearchIndexSql.createTriggers(db)
            }
        }

        /** Ensures the FTS table and triggers exist for databases already migrated to 3. */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                SearchIndexSql.createFts(db)
                SearchIndexSql.createTriggers(db)
            }
        }

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
                SearchIndexSql.createFts(db)
                SearchIndexSql.createTriggers(db)
            }
        }
    }
}
