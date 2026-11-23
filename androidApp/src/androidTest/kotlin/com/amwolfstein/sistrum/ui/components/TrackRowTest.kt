package com.amwolfstein.sistrum.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import com.amwolfstein.sistrum.settings.ThemeMode
import com.amwolfstein.sistrum.ui.theme.SistrumTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TrackRowTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun displaysTitleAndArtistAndHandlesClicks() {
        var rowClicked by mutableStateOf(false)
        var moreClicked by mutableStateOf(false)
        val title = "Starlight"
        val artist = "Muse"

        composeTestRule.setContent {
            SistrumTheme(themeMode = ThemeMode.Dark) {
                TrackRow(
                    title = title,
                    artist = artist,
                    onClick = { rowClicked = true },
                    onMoreClick = { moreClicked = true },
                )
            }
        }

        composeTestRule.onNodeWithText(title).assertExists()
        composeTestRule.onNodeWithText(artist).assertExists()

        composeTestRule.onNodeWithText(title).performClick()
        assertTrue(rowClicked)

        composeTestRule.onNode(hasContentDescription("Track options")).performClick()
        assertTrue(moreClicked)
    }

    @Test
    fun shortArabicTitleUsesTheFullTextColumnWithoutEllipsis() {
        val title = "حبيت"
        val artist = "Mohamed El Sharnouby"
        var direction by mutableStateOf(LayoutDirection.Ltr)
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                SistrumTheme(themeMode = ThemeMode.Dark) {
                    TrackRow(title = title, artist = artist)
                }
            }
        }

        for (layoutDirection in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            composeTestRule.runOnIdle { direction = layoutDirection }
            val titleNode = composeTestRule.onNodeWithText(title, useUnmergedTree = true)
            val artistNode = composeTestRule.onNodeWithText(artist, useUnmergedTree = true)
            val layouts = mutableListOf<TextLayoutResult>()
            titleNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(artistNode.fetchSemanticsNode().size.width, titleNode.fetchSemanticsNode().size.width)
            val layout = layouts.single()
            assertEquals(1, layout.lineCount)
            assertFalse(layout.isLineEllipsized(0))
        }
    }
}
