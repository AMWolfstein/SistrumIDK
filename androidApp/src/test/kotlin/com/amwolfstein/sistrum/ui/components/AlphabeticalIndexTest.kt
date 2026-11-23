package com.amwolfstein.sistrum.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class AlphabeticalIndexTest {
    @Test
    fun derivesPresentLettersAndTheirFirstLazyItems() {
        assertEquals(
            listOf(
                AlphabeticalIndexEntry("A", 2),
                AlphabeticalIndexEntry("#", 3),
                AlphabeticalIndexEntry("B", 4),
            ),
            alphabeticalIndexEntries(
                values = listOf("Álpha", "2 Fast", "Bravo", "Beta", ""),
                itemOffset = 2,
            ),
        )
    }

    @Test
    fun usesTheFirstAlbumGridRowForEachLetter() {
        assertEquals(
            listOf(
                AlphabeticalIndexEntry("A", 2),
                AlphabeticalIndexEntry("B", 3),
                AlphabeticalIndexEntry("#", 4),
            ),
            alphabeticalIndexEntries(
                values = listOf("Alpha", "Another", "Bravo", "Beta", "4ever"),
                itemOffset = 2,
                itemsPerLazyItem = 2,
            ),
        )
    }

    @Test
    fun mapsTouchesToTheCenteredLetterRail() {
        val entries = listOf(
            AlphabeticalIndexEntry("A", 2),
            AlphabeticalIndexEntry("B", 3),
            AlphabeticalIndexEntry("C", 4),
        )

        assertEquals(2, alphabeticalIndexItemIndexAt(210f, 200f, 60, entries))
        assertEquals(3, alphabeticalIndexItemIndexAt(230f, 200f, 60, entries))
        assertEquals(4, alphabeticalIndexItemIndexAt(250f, 200f, 60, entries))
    }

    @Test
    fun compactMixedScriptRailMapsEveryVisualSlotToItsItem() {
        val labels = ('A'..'Z').toList() + ('ا'..'ي').filter(Char::isLetter) + '#'
        val entries = labels.mapIndexed { index, label -> AlphabeticalIndexEntry(label.toString(), index + 2) }
        val height = 239
        entries.forEachIndexed { index, entry ->
            val top = alphabeticalIndexSlotTop(index, height, entries.size)
            val bottom = alphabeticalIndexSlotTop(index + 1, height, entries.size)
            assertEquals(entry.itemIndex, alphabeticalIndexItemIndexAt(top.toFloat(), 0f, height, entries))
            assertEquals(entry.itemIndex, alphabeticalIndexItemIndexAt(bottom - 0.1f, 0f, height, entries))
        }
    }

    @Test
    fun denseRailKeepsEndpointsAndSpreadsReadableLabels() {
        assertEquals(listOf(0, 1, 2), alphabeticalIndexVisibleSlots(3, 4))
        assertEquals(listOf(0, 2, 4, 5, 7, 9), alphabeticalIndexVisibleSlots(10, 6))
        assertEquals(listOf(0), alphabeticalIndexVisibleSlots(10, 1))
    }
    @Test
    fun tapsChoosePrintedSlotsWhileDraggingCanReachUnprintedSlots() {
        val entries = List(54) { index -> AlphabeticalIndexEntry(index.toString(), index + 2) }
        val printedEntries = alphabeticalIndexVisibleSlots(entries.size, 27).map(entries::get)

        assertEquals(2, alphabeticalIndexItemIndexAt(15.9f, 0f, 432, printedEntries))
        assertEquals(4, alphabeticalIndexItemIndexAt(16f, 0f, 432, printedEntries))
        assertEquals(3, alphabeticalIndexItemIndexAt(15.9f, 0f, 432, entries))
    }
}
