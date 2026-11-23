package com.amwolfstein.sistrum.ui

import com.amwolfstein.sistrum.sync.LibraryAlbum
import com.amwolfstein.sistrum.sync.LibraryArtist
import com.amwolfstein.sistrum.sync.LibraryComposer
import com.amwolfstein.sistrum.sync.LibraryTrack
import com.amwolfstein.sistrum.ui.components.AlphabeticalIndexEntry
import com.amwolfstein.sistrum.ui.components.alphabeticalIndexEntries
import com.amwolfstein.sistrum.ui.screens.AlbumSortOption
import com.amwolfstein.sistrum.ui.screens.ArtistSortOption
import com.amwolfstein.sistrum.ui.screens.ComposerSortOption
import com.amwolfstein.sistrum.ui.screens.SortOrder
import com.amwolfstein.sistrum.ui.screens.TrackSortOption
import com.amwolfstein.sistrum.ui.screens.sortAlbums
import com.amwolfstein.sistrum.ui.screens.sortArtists
import com.amwolfstein.sistrum.ui.screens.sortComposers
import com.amwolfstein.sistrum.ui.screens.sortTracks
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryAlphabeticalOrderTest {
    @Test
    fun ordersLettersThenNumbersThenOtherCharacters() {
        assertEquals(
            listOf("Álpha", "Đen", "Zulu", "2 Fast", "#Hashtag"),
            listOf("#Hashtag", "2 Fast", "Zulu", "Đen", "Álpha").sortedWith(libraryAlphabeticalComparator),
        )
    }

    @Test
    fun sortsDisplayNamesWhenNoCanonicalKeyIsAvailable() {
        val albums = listOf(LibraryAlbum("1", "Zulu", "Alpha"), LibraryAlbum("2", "Alpha", "Zulu"))
        val artists = listOf(LibraryArtist("1", "Zulu"), LibraryArtist("2", "Alpha", sortName = " "))
        val composers = listOf(LibraryComposer("1", "Zulu"), LibraryComposer("2", "Alpha"))

        assertEquals(listOf("2", "1"), sortAlbums(albums, AlbumSortOption.Name, SortOrder.Ascending).map { it.id })
        assertEquals(listOf("1", "2"), sortAlbums(albums, AlbumSortOption.Artist, SortOrder.Ascending).map { it.id })
        assertEquals(listOf("2", "1"), sortArtists(artists, ArtistSortOption.Name, SortOrder.Ascending).map { it.id })
        assertEquals(listOf("2", "1"), sortComposers(composers, ComposerSortOption.Name, SortOrder.Ascending).map { it.id })
    }

    @Test
    fun localTracksSortAndIndexVisibleTitlesEvenWithStoredMediaStoreKeys() {
        val tracks = listOf(
            LibraryTrack(id = "local:1", title = "Zulu", artists = "Alfa", sortTitle = "\u0001opaque1"),
            LibraryTrack(id = "local:2", title = "أغنية", artists = "فنان", sortTitle = "\u0001opaque2"),
            LibraryTrack(id = "local:3", title = "Alpha", artists = "Zulu", sortTitle = "\u0001opaque3"),
            LibraryTrack(id = "local:4", title = "٢ Fast", artists = "Other", sortTitle = "\u0001opaque4"),
        )
        val ascending = sortTracks(tracks, TrackSortOption.Name, SortOrder.Ascending)
        val descending = sortTracks(tracks, TrackSortOption.Name, SortOrder.Descending)

        assertEquals(listOf("Alpha", "Zulu", "أغنية", "٢ Fast"), ascending.map { it.title })
        assertEquals(ascending.reversed(), descending)
        val entries = listOf(
            AlphabeticalIndexEntry("A", 2),
            AlphabeticalIndexEntry("Z", 3),
            AlphabeticalIndexEntry("ا", 4),
            AlphabeticalIndexEntry("#", 5),
        )
        assertEquals(entries, alphabeticalIndexEntries(ascending.map { it.alphabeticalTitle }, itemOffset = 2))
        assertEquals(listOf("#", "ا", "Z", "A"), alphabeticalIndexEntries(descending.map { it.alphabeticalTitle }, 2).map { it.label })
    }

    @Test
    fun normalizesLeadingSpaceAndInteriorAccentsConsistently() {
        val expected = listOf("Aé", "Áex", "Af", " Zebra", "Zulu")
        assertEquals(expected, listOf("Af", "Zulu", "Áex", " Zebra", "Aé").sortedWith(libraryAlphabeticalComparator))
        assertEquals(listOf("A", "Z"), expected.mapNotNull(::libraryAlphabeticalIndexLabel).distinct())
    }
}
