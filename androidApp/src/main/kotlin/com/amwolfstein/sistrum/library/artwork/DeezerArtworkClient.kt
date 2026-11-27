package com.amwolfstein.sistrum.library.artwork

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class DeezerArtist(val id: Long, val name: String, val pictureUrl: String)

/**
 * Parses Deezer's artist search response and only accepts an exact normalized name match, so a
 * lookup for one artist never imports a lookalike's picture. Pure and host-testable.
 */
object DeezerArtworkParser {
    private val json = Json { ignoreUnknownKeys = true }
    private const val MAX_RESULTS = 10

    fun parseArtistSearch(body: String, query: String): DeezerArtist? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val data = runCatching { root["data"]?.jsonArray }.getOrNull() ?: return null
        val wanted = normalizeName(query)
        if (wanted.isEmpty()) return null
        return data.asSequence()
            .take(MAX_RESULTS)
            .mapNotNull { element -> runCatching { element.jsonObject }.getOrNull() }
            .mapNotNull { obj ->
                val name = obj.string("name") ?: return@mapNotNull null
                val id = obj.string("id")?.toLongOrNull() ?: return@mapNotNull null
                val picture = obj.string("picture_xl")
                    ?: obj.string("picture_big")
                    ?: obj.string("picture_medium")
                    ?: return@mapNotNull null
                if (picture.isBlank()) return@mapNotNull null
                DeezerArtist(id, name, picture)
            }
            .firstOrNull { normalizeName(it.name) == wanted }
    }

    fun normalizeName(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    private fun JsonObject.string(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()
}

/** Minimal Deezer client; it never throws, so a failed lookup can keep previously cached art. */
class DeezerArtworkClient(
    private val request: suspend (String) -> ByteArray? = { url -> httpGet(url) },
) {
    suspend fun searchArtist(name: String): DeezerArtist? {
        if (name.isBlank()) return null
        val body = request(
            "https://api.deezer.com/search/artist?q=" +
                URLEncoder.encode(name.trim(), "UTF-8") + "&limit=10",
        )?.toString(Charsets.UTF_8) ?: return null
        return DeezerArtworkParser.parseArtistSearch(body, name)
    }

    suspend fun download(pictureUrl: String): ByteArray? {
        if (pictureUrl.isBlank()) return null
        val bytes = request(pictureUrl) ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) return null
        return bytes.takeIf { ArtworkKeys.extensionFor(it) != null }
    }

    private companion object {
        const val MAX_IMAGE_BYTES = 10 * 1024 * 1024

        suspend fun httpGet(url: String): ByteArray? = withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 12_000
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/json,image/*")
                    setRequestProperty("User-Agent", "Sistrum/0.1.0 (Android)")
                }
                if (connection.responseCode !in 200..299) return@withContext null
                connection.inputStream.use { stream ->
                    val buffer = ByteArray(16 * 1024)
                    val output = java.io.ByteArrayOutputStream()
                    var total = 0
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_IMAGE_BYTES) return@withContext null
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
            } catch (failure: Exception) {
                null
            } finally {
                connection?.disconnect()
            }
        }
    }
}
