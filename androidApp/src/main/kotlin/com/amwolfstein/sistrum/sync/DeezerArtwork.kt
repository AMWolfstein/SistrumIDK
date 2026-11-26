package com.amwolfstein.sistrum.sync

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/** On-device provider cache, separate from manually selected artist artwork. */
internal class DeezerArtwork(
    context: Context,
    private val testReader: ((String, Int) -> ByteArray?)? = null,
) {
    private val filesDir = context.filesDir
    private val preferences = context.getSharedPreferences("deezer_artwork", Context.MODE_PRIVATE)
    private val lock = Any()
    private var connection: HttpURLConnection? = null
    private var generation = 0L
    private val mutableEnabled = MutableStateFlow(preferences.getBoolean("enabled", false))
    val enabled: StateFlow<Boolean> = mutableEnabled
    private val mutablePaths = MutableStateFlow(readPaths())
    val paths: StateFlow<Map<String, String>> = mutablePaths

    fun setEnabled(value: Boolean) {
        synchronized(lock) {
            if (mutableEnabled.value != value) generation++
            mutableEnabled.value = value
            preferences.edit().putBoolean("enabled", value).apply()
            if (!value) connection?.disconnect()
        }
    }

    suspend fun refresh(artists: List<LibraryArtist>) {
        if (artists.isEmpty()) return
        val start = preferences.getInt("cursor", 0).mod(artists.size)
        val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
        repeat(minOf(artists.size, 50)) { offset ->
            if (android.os.SystemClock.elapsedRealtime() >= deadline) return
            currentCoroutineContext().ensureActive()
            if (!enabled.value) return
            val index = (start + offset) % artists.size
            val completed = try {
                refreshArtist(artists[index])
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (!completed) return
            preferences.edit().putInt("cursor", (index + 1) % artists.size).apply()
        }
    }

    private suspend fun refreshArtist(artist: LibraryArtist): Boolean {
        val currentGeneration = synchronized(lock) { generation }
        val query = Uri.encode(artist.name)
        val search = readUrl("https://api.deezer.com/search/artist?q=$query", 512 * 1024, currentGeneration, false) ?: return false
        currentCoroutineContext().ensureActive()
        val records = JSONObject(search.toString(Charsets.UTF_8)).optJSONArray("data") ?: return false
        val expected = artist.name.trim().lowercase(Locale.ROOT)
        val matches = (0 until records.length()).mapNotNull { records.optJSONObject(it) }
            .filter { it.optString("name").trim().lowercase(Locale.ROOT) == expected }
        if (matches.size != 1) return true
        val pictureUrl = matches.single().optString("picture_medium")
        val pictureHost = Uri.parse(pictureUrl).host?.lowercase(Locale.ROOT).orEmpty()
        if (!pictureUrl.startsWith("https://") || (pictureHost != "dzcdn.net" && !pictureHost.endsWith(".dzcdn.net"))) return true
        val bytes = readUrl(pictureUrl, 4 * 1024 * 1024, currentGeneration, true) ?: return false
        currentCoroutineContext().ensureActive()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 2..1024 || bounds.outHeight !in 2..1024) return true
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return true
        decoded.recycle()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val directory = File(filesDir, "deezer-artist-artwork")
        if (!directory.isDirectory && !directory.mkdirs()) return false
        val target = File(directory, "$hash.image")
        if (!target.exists()) {
            val pending = File.createTempFile("image-", ".tmp", directory)
            try {
                pending.writeBytes(bytes)
                if (!pending.renameTo(target) && !target.isFile) return false
            } finally {
                pending.delete()
            }
        }
        synchronized(lock) {
            val relativePath = "deezer-artist-artwork/${target.name}"
            if (!mutableEnabled.value || generation != currentGeneration) {
                if (relativePath !in mutablePaths.value.values) target.delete()
                return false
            }
            val previous = mutablePaths.value[artist.id]
            preferences.edit().putString("artist:${artist.id}", relativePath).apply()
            mutablePaths.value = mutablePaths.value + (artist.id to relativePath)
            if (previous != null && previous != relativePath && previous !in mutablePaths.value.values) {
                File(filesDir, previous).delete()
            }
        }
        return true
    }

    private fun readUrl(url: String, limit: Int, expectedGeneration: Long, image: Boolean): ByteArray? {
        val uri = Uri.parse(url)
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        if (uri.scheme != "https") return null
        if (image && host != "dzcdn.net" && !host.endsWith(".dzcdn.net")) return null
        if (!image && host != "api.deezer.com") return null
        if (!enabled.value) return null
        if (testReader != null) {
            synchronized(lock) {
                if (!mutableEnabled.value || generation != expectedGeneration) return null
            }
            return testReader.invoke(url, limit)?.takeIf { it.size <= limit }
        }
        val request = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 3_000
            readTimeout = 3_000
            instanceFollowRedirects = false
        }
        synchronized(lock) {
            if (!mutableEnabled.value || generation != expectedGeneration) {
                request.disconnect()
                return null
            }
            connection = request
        }
        return try {
            if (request.responseCode != HttpURLConnection.HTTP_OK || request.url.protocol != "https") return null
            if (request.contentLength > limit) return null
            request.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (synchronized(lock) { mutableEnabled.value && generation == expectedGeneration }) {
                    val count = input.read(buffer)
                    if (count < 0) return@use output.toByteArray()
                    if (output.size() + count > limit) return null
                    output.write(buffer, 0, count)
                }
                null
            }
        } finally {
            synchronized(lock) {
                if (connection === request) connection = null
            }
            request.disconnect()
        }
    }

    private fun readPaths(): Map<String, String> = preferences.all.mapNotNull { (key, value) ->
        if (!key.startsWith("artist:") || value !is String) return@mapNotNull null
        val file = File(filesDir, value)
        if (!file.isFile) return@mapNotNull null
        key.removePrefix("artist:") to value
    }.toMap()
}
