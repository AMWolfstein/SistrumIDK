package com.amwolfstein.sistrum.library.tags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TagNormalizerTest {
    @Test
    fun normalizationFoldsCaseAccentsAndWhitespace() {
        assertEquals("bjork", TagNormalizer.normalize("  Björk "))
        assertEquals("sigur ros", TagNormalizer.normalize("Sigur   Rós"))
        assertEquals("a tribe called quest", TagNormalizer.normalize("A Tribe Called Quest"))
    }

    @Test
    fun sortNameMovesLeadingArticles() {
        assertEquals("Beatles, The", TagNormalizer.sortName("The Beatles"))
        assertEquals("Perfect Circle, A", TagNormalizer.sortName("A Perfect Circle"))
        assertEquals("The", TagNormalizer.sortName("The"))
        assertEquals("Therapy", TagNormalizer.sortName("Therapy"))
    }

    @Test
    fun keysAreDeterministicAndKindScoped() {
        val first = TagNormalizer.key("artist", "Björk")
        assertEquals(first, TagNormalizer.key("artist", "  BJORK "))
        assertNotEquals(first, TagNormalizer.key("album", "Björk"))
        assertTrue(first.matches(Regex("artist_[0-9a-f]{24}")))
    }

    @Test
    fun fileTitleStripsFolderAndExtension() {
        assertEquals("01 - Song", TagNormalizer.fileTitle("Music/Album/01 - Song.flac"))
        assertEquals("NoExt", TagNormalizer.fileTitle("NoExt"))
    }
}

class EntityResolverTest {
    private val resolver = EntityResolver()

    @Test
    fun aliasPriorityPrefersCanonicalKeys() {
        val metadata = TagMetadata(
            title = "Tag title",
            artist = "Tag artist",
            rawTags = mapOf(
                "TITLE" to listOf("Canonical title"),
                "ARTIST" to listOf("Canonical artist"),
                "ALBUMARTIST" to listOf("Album artist"),
                "ALBUM" to listOf("Canonical album"),
                "GENRE" to listOf("Rock", "Shoegaze"),
                "DATE" to listOf("1998-06-01"),
            ),
        )
        val track = resolver.resolve(metadata, "x.flac", "source:a")
        assertEquals("Canonical title", track.title)
        assertEquals(listOf("Canonical artist"), track.artistNames)
        assertEquals(listOf("Album artist"), track.albumArtistNames)
        assertEquals("Canonical album", track.albumTitle)
        assertEquals(listOf("Rock", "Shoegaze"), track.genreNames)
        assertEquals("1998-06-01", track.releaseDate)
    }

    @Test
    fun fallsBackToFileNameAndHasNoAlbumWhenTagsAreEmpty() {
        val track = resolver.resolve(TagMetadata(), "Music/Artist/07 - Untitled.opus", "source:b")
        assertEquals("07 - Untitled", track.title)
        assertTrue(track.artistNames.isEmpty())
        assertNull(track.albumId)
        assertNull(track.albumArtistId)
    }

    @Test
    fun idsAreStableAcrossRunsAndEditsDoNotChangeDocumentIdentity() {
        val metadata = TagMetadata(title = "Song", artist = "Artist", album = "Album")
        val first = resolver.resolve(metadata, "x.flac", "source:a")
        val second = resolver.resolve(metadata.copy(title = "Renamed"), "x.flac", "source:a")
        assertEquals(first.itemId, second.itemId)
        assertEquals(first.artistIds, second.artistIds)
        assertEquals(first.albumId, second.albumId)
    }

    @Test
    fun differentDocumentsGetDifferentItemIds() {
        val metadata = TagMetadata(title = "Same")
        assertNotEquals(
            resolver.resolve(metadata, "a.flac", "source:a").itemId,
            resolver.resolve(metadata, "b.flac", "source:b").itemId,
        )
    }
}

class TagMetadataJsonTest {
    @Test
    fun parsesBridgePayloadIncludingRawArrays() {
        val payload = """
            {"title":"Song","artist":"Artist","album":"Album","year":1999,"track":4,
             "durationMs":12345,"bitrate":320,"sampleRate":44100,"channels":2,"bitsPerSample":16,
             "rawTags":{"ALBUMARTIST":["Album Artist"],"COMPOSER":["Composer"],"DISCNUMBER":["2"],
               "LYRICS":["line one"],"ISRC":["ABC"]},
             "hasArtwork":true,"artworkMime":"image/jpeg"}
        """.trimIndent()
        val metadata = TagMetadataJson.parse(payload)
        assertEquals("Song", metadata.title)
        assertEquals(1999, metadata.year)
        assertEquals(4, metadata.track)
        assertEquals(12_345L, metadata.durationMs)
        assertEquals(2, metadata.disc)
        assertEquals("Album Artist", metadata.albumArtist)
        assertEquals("Composer", metadata.composer)
        assertTrue(metadata.hasArtwork)
        assertEquals("image/jpeg", metadata.artworkMime)
        assertEquals(listOf("line one"), metadata.rawTags["LYRICS"])
    }

    @Test
    fun malformedPayloadIsEmptyRatherThanCrashing() {
        assertEquals(TagMetadata(), TagMetadataJson.parse("not json"))
        assertEquals(TagMetadata(), TagMetadataJson.parse("{}"))
    }
}
