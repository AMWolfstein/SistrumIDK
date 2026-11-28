package com.amwolfstein.sistrum.library.mood

import com.amwolfstein.sistrum.library.db.LibraryDatabase
import com.amwolfstein.sistrum.mood.MoodRadioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Local candidate source and readiness gate for Mood Radio (replaces the desktop analysis flag). */
class MoodRepository(
    database: LibraryDatabase,
) {
    private val dao = database.libraryDao()

    suspend fun tracks(): List<MoodRadioTrack> = withContext(Dispatchers.IO) {
        dao.moodRadioRows().mapNotNull { row ->
            if (row.energy == null || row.danceability == null || row.brightness == null || row.tempo == null) {
                return@mapNotNull null
            }
            MoodRadioTrack(
                id = row.trackId,
                albumId = row.albumId.orEmpty(),
                primaryArtistId = row.primaryArtistId.orEmpty(),
                energy = row.energy,
                danceability = row.danceability,
                brightness = row.brightness,
                tempo = row.tempo,
            )
        }
    }

    /** Mood Radio needs at least two analyzed tracks with the four mood features. */
    suspend fun ready(): Boolean = withContext(Dispatchers.IO) { tracks().size >= 2 }
}
