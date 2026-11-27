package com.amwolfstein.sistrum.library.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeezerArtworkParserTest {
    @Test
    fun picksTheExactArtistAndPrefersExtraLargePicture() {
        val body = """
            {"data":[
              {"id":1,"name":"Björk","picture_xl":"https://img/xl/bjork.jpg","picture_big":"https://img/big/bjork.jpg"},
              {"id":2,"name":"Bjork Tribute Band","picture_xl":"https://img/xl/tribute.jpg"}
            ]}
        """.trimIndent()
        val artist = DeezerArtworkParser.parseArtistSearch(body, "björk")
        assertEquals(1L, artist?.id)
        assertEquals("https://img/xl/bjork.jpg", artist?.pictureUrl)
    }

    @Test
    fun fallsBackToBigOrMediumPicture() {
        val body = """{"data":[{"id":7,"name":"Portishead","picture_medium":"https://img/m.jpg"}]}"""
        assertEquals("https://img/m.jpg", DeezerArtworkParser.parseArtistSearch(body, "Portishead")?.pictureUrl)
    }

    @Test
    fun lookalikesAreRejected() {
        val body = """{"data":[{"id":3,"name":"Air","picture_xl":"https://img/xl/air.jpg"}]}"""
        assertNull(DeezerArtworkParser.parseArtistSearch(body, "Airbourne"))
    }

    @Test
    fun malformedOrEmptyResponsesReturnNull() {
        assertNull(DeezerArtworkParser.parseArtistSearch("<html>error</html>", "Air"))
        assertNull(DeezerArtworkParser.parseArtistSearch("""{"data":[]}""", "Air"))
        assertNull(DeezerArtworkParser.parseArtistSearch("""{"data":[{"name":"Air"}]}""", "Air"))
        assertNull(DeezerArtworkParser.parseArtistSearch("""{"data":[{"id":1,"name":"Air","picture_xl":""}]}""", "Air"))
        assertNull(DeezerArtworkParser.parseArtistSearch("""{"error":"quota"}""", "Air"))
    }

    @Test
    fun normalizationFoldsAccentsCaseAndPunctuation() {
        assertEquals("sigur ros", DeezerArtworkParser.normalizeName("  Sigur Rós "))
        assertEquals("ac dc", DeezerArtworkParser.normalizeName("AC/DC"))
        assertEquals("beyonce", DeezerArtworkParser.normalizeName("Beyoncé"))
    }
}
