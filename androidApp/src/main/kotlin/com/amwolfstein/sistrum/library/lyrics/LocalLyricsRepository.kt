package com.amwolfstein.sistrum.library.lyrics

import android.content.Context
import android.net.Uri
import com.amwolfstein.sistrum.library.db.LibraryDatabase
import com.amwolfstein.sistrum.library.db.LibraryItemEntity
import com.amwolfstein.sistrum.library.db.LyricsEntity
import com.amwolfstein.sistrum.library.tags.MetadataExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull

/** Local/embedded lyrics cache with the documented precedence and durable Room storage. */
internal class LocalLyricsRepository(
    private val context: Context,
    private val database: LibraryDatabase = LibraryDatabase.get(context),
    private val settings: suspend () -> LyricsLocalSettings = { LyricsLocalSettings() },
) {
    private val dao = database.libraryDao()
    private val finder = SafLyricsFinder(context)
    private val extractor = MetadataExtractor(context)

    fun observe(trackId: String): Flow<LyricsEntity?> = dao.observeLyrics(trackId)

    suspend fun track(trackId: String): com.amwolfstein.sistrum.lyrics.LyricsTrack? = withContext(Dispatchers.IO) {
        val item = dao.itemById(trackId) ?: return@withContext null
        val artists = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(item.rawArtistNames)
                .let { it as kotlinx.serialization.json.JsonArray }
                .mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
        }.getOrDefault(emptyList())
        com.amwolfstein.sistrum.lyrics.LyricsTrack(
            title = item.title,
            artist = artists.firstOrNull().orEmpty(),
            album = item.albumTitle,
            duration = item.durationSeconds.toInt(),
        )
    }

    suspend fun fingerprint(trackId: String): String = withContext(Dispatchers.IO) {
        dao.itemById(trackId)?.let { it.audioFingerprint ?: it.contentKey ?: it.documentKey } ?: trackId
    }

    suspend fun save(
        trackId: String,
        content: String,
        source: String,
        synced: Boolean,
        fingerprint: String,
        embeddedContent: String? = null,
        embeddedSource: String? = null,
    ) = withContext(Dispatchers.IO) {
        val existing = dao.lyrics(trackId)
        dao.upsertLyrics(
            LyricsEntity(
                trackId = trackId,
                content = content,
                source = source,
                synced = synced,
                offsetMs = existing?.offsetMs ?: 0L,
                fingerprint = fingerprint,
                updatedAt = System.currentTimeMillis(),
                embeddedContent = embeddedContent ?: existing?.embeddedContent,
                embeddedSource = embeddedSource ?: existing?.embeddedSource,
            ),
        )
    }

    suspend fun saveOnline(trackId: String, content: String, source: String, fingerprint: String) {
        val synced = SyncedPattern.containsMatchIn(content)
        save(trackId, content, source, synced, fingerprint)
    }

    /**
     * Resolves a track's lyrics in precedence order and stores the result:
     * sibling `.lrc`/`.txt`, subfolder, dedicated folder, embedded, cached online, online cache.
     * With `preferLocalLyrics` off the stored online copy is surfaced first.
     */
    suspend fun resolve(
        itemId: String,
        preferences: LyricsLocalSettings,
    ): LyricsEntity? = withContext(Dispatchers.IO) {
        val item = dao.itemById(itemId) ?: return@withContext null
        val existing = dao.lyrics(itemId)
        val fingerprint = item.audioFingerprint ?: item.contentKey ?: item.documentKey

        val local = dao.source(item.sourceId)?.let { source ->
            finder.find(
                item,
                source,
                preferences.subfolderEnabled,
                preferences.subfolderName,
                preferences.dedicatedFolderUri,
            )
        }
        val embedded = readEmbedded(item)
        val online = existing?.takeIf { it.source !in LOCAL_SOURCES }

        val chosen = when {
            preferences.preferLocalLyrics ->
                local?.let { "local-${it.source}" to it.content }
                    ?: embedded?.let { "embedded" to it }
                    ?: online?.let { it.source to it.content }
            else ->
                online?.let { it.source to it.content }
                    ?: local?.let { "local-${it.source}" to it.content }
                    ?: embedded?.let { "embedded" to it }
        }
        if (chosen == null) return@withContext existing
        val (source, content) = chosen
        save(
            trackId = itemId,
            content = content,
            source = source,
            synced = SyncedPattern.containsMatchIn(content),
            fingerprint = fingerprint,
            embeddedContent = embedded,
            embeddedSource = embedded?.let { "embedded" },
        )
        dao.lyrics(itemId)
    }

    private suspend fun readEmbedded(item: LibraryItemEntity): String? {
        val metadata = extractor.read(Uri.parse(item.fileUri)).getOrNull()?.metadata ?: return null
        return metadata.lyrics?.takeIf { it.isNotBlank() }
    }

    private companion object {
        val LOCAL_SOURCES = setOf("local-lrc", "local-txt", "embedded")
        val SyncedPattern = Regex("\\[\\d{1,2}:\\d{2}(\\.\\d{1,3})?]")
    }
}

/** Subset of lyric preferences needed for file precedence (persisted by LyricsPreferences). */
data class LyricsLocalSettings(
    val preferLocalLyrics: Boolean = true,
    val subfolderEnabled: Boolean = false,
    val subfolderName: String = "Lyrics",
    val dedicatedFolderUri: String? = null,
)
