package com.amwolfstein.sistrum.update

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal class GitHubAppReleaseSource(
    private val fetch: suspend (String) -> String = ::fetchReleaseJson,
) : AppReleaseSource {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun latestAndroidTag(): String =
        json.decodeFromString<List<GitHubRelease>>(fetch(ReleaseListUrl))
            .mapNotNull { release ->
                val version = parseReleaseTag(release.tag)
                if (version != null && release.hasAndroidApk()) version to release.tag else null
            }
            .maxByOrNull { (version, _) -> version }?.second.orEmpty()

    override suspend fun androidRelease(tag: String): AppUpdate? {
        require(parseReleaseTag(tag) != null)
        val release = json.decodeFromString<GitHubRelease>(fetch("$ReleasesUrl/$tag"))
        if (release.tag != tag || !release.hasAndroidApk()) return null
        return AppUpdate(tag.removePrefix("v"), release.body.orEmpty(), "https://github.com/AMWolfstein/SistrumIDK/releases/tag/$tag")
    }

    companion object {
        const val ReleaseListUrl = "https://api.github.com/repos/AMWolfstein/SistrumIDK/releases"
        private const val ReleasesUrl = "https://api.github.com/repos/AMWolfstein/SistrumIDK/releases/tags"
    }
}

private fun parseReleaseTag(tag: String): AppVersion? =
    if (tag.startsWith("v")) AppVersion.parse(tag.removePrefix("v")) else null

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tag: String,
    val draft: Boolean,
    @SerialName("published_at") val publishedAt: String? = null,
    val body: String? = null,
    val assets: List<ReleaseAsset> = emptyList(),
) {
    fun hasAndroidApk(): Boolean = !draft && !publishedAt.isNullOrBlank() &&
        assets.any { it.state == "uploaded" && it.name.endsWith(".apk") }
}

@Serializable
private data class ReleaseAsset(val name: String, val state: String)

private suspend fun fetchReleaseJson(address: String): String = withContext(Dispatchers.IO) {
    ensureActive()
    val connection = (URL(address).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 10_000
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("User-Agent", "Sistrum-Android")
    }
    try {
        check(connection.responseCode == HttpURLConnection.HTTP_OK) { "Update check HTTP ${connection.responseCode}" }
        connection.inputStream.bufferedReader().use { it.readText() }.also { ensureActive() }
    } finally {
        connection.disconnect()
    }
}
