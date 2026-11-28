package com.amwolfstein.sistrum

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import com.amwolfstein.sistrum.settings.ThemeMode
import com.amwolfstein.sistrum.sync.LibraryTrack
import com.amwolfstein.sistrum.ui.screens.InsightPeriod
import com.amwolfstein.sistrum.ui.screens.InsightLineChart
import com.amwolfstein.sistrum.ui.screens.InsightDonut
import com.amwolfstein.sistrum.ui.screens.InsightPoint
import com.amwolfstein.sistrum.ui.screens.InsightSourceFilter
import com.amwolfstein.sistrum.ui.screens.InsightTopTrack
import com.amwolfstein.sistrum.ui.screens.InsightTopArtist
import com.amwolfstein.sistrum.ui.screens.InsightUiState
import com.amwolfstein.sistrum.ui.screens.LibraryInsightState
import com.amwolfstein.sistrum.ui.screens.ListeningInsightState
import com.amwolfstein.sistrum.ui.theme.SistrumTheme
import com.amwolfstein.sistrum.ui.theme.LocalSistrumColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class InsightContentTest {
    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun insightReplacesPlaceholderAndExposesFiltersAndExpandableTracks() {
        var state by mutableStateOf(
            InsightUiState(
                hasOtherSources = true,
                library = LibraryInsightState(tracks = 6),
                listening = ListeningInsightState(
                    listenedSeconds = 3_600,
                    topArtists = listOf(InsightTopArtist("artist-1", "Artist name", null, 60)),
                    topTracks = (1..6).map { index ->
                        InsightTopTrack(LibraryTrack("track-$index", "Track $index", "Artist"), index, index * 60)
                    },
                ),
            ),
        )
        composeTestRule.setContent {
            App(
                uiState = AppUiState(selectedDestination = AppDestination.Insight),
                destinations = AppDestinationModels(
                    insight = InsightDestinationModel(
                        state = state,
                        onListeningPeriodSelected = { state = state.copy(listeningPeriod = it) },
                        onSourceSelected = { state = state.copy(sourceFilter = it) },
                    ),
                ),
            )
        }

        composeTestRule.onNodeWithTag("insight-page").assertIsDisplayed()
        val libraryWidth = composeTestRule.onNodeWithTag("insight-library-size").fetchSemanticsNode().boundsInRoot.width
        val playlistWidth = composeTestRule.onNodeWithTag("insight-playlists").fetchSemanticsNode().boundsInRoot.width
        assertTrue(libraryWidth > playlistWidth)
        composeTestRule.onAllNodesWithText("Insight will appear here.").assertCountEquals(0)
        // The page has a library and a listening period selector; scope the open
        // to the listening one, then pick from its anchored menu.
        composeTestRule.onNodeWithTag("insight-page")
            .performScrollToNode(hasTestTag("insight-listening-period"))
        composeTestRule.onNodeWithTag("insight-listening-period").performClick()
        composeTestRule.onNodeWithText("30D").performClick()
        assertEquals(InsightPeriod.ThirtyDays, state.listeningPeriod)
        composeTestRule.onNodeWithText("All devices").performClick()
        composeTestRule.onNodeWithText("Other synced devices").performClick()
        assertEquals(InsightSourceFilter.Other, state.sourceFilter)
        val page = composeTestRule.onNodeWithTag("insight-page")
        page.performScrollToNode(hasText("Artist name"))
        composeTestRule.onNodeWithText("Artist name").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Track 6").assertCountEquals(0)
        // "1 min" also labels the average session metric; the track row's copy is
        // the last one in the list. "1 plays" is unique to that row.
        page.performScrollToNode(hasText("1 plays"))
        composeTestRule.onNodeWithText("1 plays").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("1 min").onLast().assertIsDisplayed()
        composeTestRule.onNodeWithTag("insight-top-tracks-toggle").performScrollTo()
        composeTestRule.onNode(hasContentDescription("Track options")).assertDoesNotExist()
        composeTestRule.onAllNodesWithTag("insight-track-divider").assertCountEquals(4)
        // The page scrolls under the floating navigation chrome, so activate the
        // toggle through its semantics action instead of a screen-coordinate tap.
        composeTestRule.onNodeWithTag("insight-top-tracks-toggle")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Track 6").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun libraryGrowthChartDoesNotScrollForLongRanges() {
        composeTestRule.setContent {
            SistrumTheme(ThemeMode.Dark) { InsightLineChart((1..31).map { InsightPoint(it.toString(), it) }, "Library growth") }
        }

        composeTestRule.onNodeWithContentDescription("Library growth").assert(!hasScrollAction())
    }

    @Test
    fun libraryGrowthChartDisplaysASingleDataPoint() {
        composeTestRule.setContent {
            SistrumTheme(ThemeMode.Dark) { InsightLineChart(listOf(InsightPoint("2026", 1)), "Single library growth point") }
        }

        composeTestRule.onNodeWithContentDescription("Single library growth point").assertIsDisplayed()
    }

    @Test
    fun donutChartDisplaysASingleValue() {
        composeTestRule.setContent {
            SistrumTheme(ThemeMode.Dark) { InsightDonut(listOf(1 to LocalSistrumColors.current.primary), "1", "Single donut value") }
        }

        composeTestRule.onNodeWithContentDescription("Single donut value").assertIsDisplayed()
    }
}
