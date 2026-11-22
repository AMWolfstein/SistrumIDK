package com.amwolfstein.sistrum.update

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class GitHubAppReleaseSourceTest {
    @Test fun selectsPublishedAndroidReleaseAndFetchesExactTag() = runTest {
        val urls = mutableListOf<String>()
        val source = GitHubAppReleaseSource { url ->
            urls += url
            if (url == GitHubAppReleaseSource.ReleaseListUrl) {
                val noApk = release().replace("v1.10.0", "v9.0.0").replace("app.apk", "app.zip")
                val old = release().replace("v1.10.0", "v1.9.0")
                "[${noApk},${old},${release()}]"
            } else release()
        }
        val tag = source.latestAndroidTag()
        val update = source.androidRelease(tag)!!
        assertEquals("1.10.0", update.version)
        assertEquals("New features", update.notes)
        assertEquals("https://github.com/AMWolfstein/SistrumIDK/releases/tag/v1.10.0", update.releaseUrl)
        assertEquals("https://api.github.com/repos/AMWolfstein/SistrumIDK/releases/tags/v1.10.0", urls.last())
    }

    @Test fun acceptsPrereleasesAndEmptyNotes() = runTest {
        val source = GitHubAppReleaseSource { release().replace("\"New features\"", "null") }
        assertEquals("", source.androidRelease("v1.10.0")!!.notes)
    }

    @Test fun rejectsDraftsMissingApksUnpublishedAndMismatchedTags() = runTest {
        for (body in listOf(
            release().replace("\"draft\":false", "\"draft\":true"),
            release().replace("app.apk", "app.zip"),
            release().replace("uploaded", "starter"),
            release().replace("\"2026-10-31T00:00:00Z\"", "null"),
            release().replace("v1.10.0", "v1.9.0"),
        )) {
            assertNull(GitHubAppReleaseSource { body }.androidRelease("v1.10.0"))
        }
    }

    @Test fun malformedJsonAndHttpFailuresPropagate() = runTest {
        for (source in listOf(
            GitHubAppReleaseSource { "invalid" },
            GitHubAppReleaseSource { throw java.io.IOException("HTTP 403") },
        )) {
            try {
                source.latestAndroidTag()
                fail("Expected failure")
            } catch (_: Exception) { }
        }
    }

    private fun release() = """{"tag_name":"v1.10.0","draft":false,"prerelease":true,"published_at":"2026-10-31T00:00:00Z","body":"New features","assets":[{"name":"app.apk","state":"uploaded"}]}"""
}
