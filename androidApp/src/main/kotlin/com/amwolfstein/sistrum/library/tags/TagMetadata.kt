package com.amwolfstein.sistrum.library.tags

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

/** Metadata read from one audio file through the TagLib JNI bridge. */
data class TagMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val genre: String? = null,
    val composer: String? = null,
    val comment: String? = null,
    val year: Int? = null,
    val track: Int? = null,
    val disc: Int? = null,
    val durationMs: Long = 0L,
    val bitrate: Int? = null,
    val sampleRate: Int? = null,
    val channels: Int? = null,
    val bitsPerSample: Int? = null,
    val rawTags: Map<String, List<String>> = emptyMap(),
    val hasArtwork: Boolean = false,
    val artworkMime: String? = null,
    val lyrics: String? = null,
) {
    fun values(key: String): List<String> = rawTags[key.uppercase()].orEmpty()

    fun first(key: String): String? = values(key).firstOrNull()?.takeIf { it.isNotBlank() }
}

object TagMetadataJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): TagMetadata {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return TagMetadata()
        val raw = runCatching {
            root["rawTags"]?.jsonObject?.mapValues { (_, value) ->
                (value as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
            }.orEmpty()
        }.getOrDefault(emptyMap())
        fun string(key: String): String? =
            (root[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        fun int(key: String): Int? = (root[key] as? JsonPrimitive)?.content?.toIntOrNull()
        fun long(key: String): Long = (root[key] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
        fun bool(key: String): Boolean = (root[key] as? JsonPrimitive)?.booleanOrNull ?: false
        return TagMetadata(
            title = string("title"),
            artist = string("artist"),
            album = string("album"),
            albumArtist = raw["ALBUMARTIST"]?.firstOrNull() ?: string("albumArtist"),
            genre = string("genre"),
            composer = raw["COMPOSER"]?.firstOrNull(),
            comment = string("comment"),
            year = int("year"),
            track = int("track"),
            disc = raw["DISCNUMBER"]?.firstOrNull()?.toIntOrNull(),
            durationMs = long("durationMs"),
            bitrate = int("bitrate"),
            sampleRate = int("sampleRate"),
            channels = int("channels"),
            bitsPerSample = int("bitsPerSample"),
            rawTags = raw,
            hasArtwork = bool("hasArtwork"),
            artworkMime = string("artworkMime"),
            lyrics = string("lyrics") ?: raw["LYRICS"]?.firstOrNull(),
        )
    }
}
