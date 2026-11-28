package com.amwolfstein.sistrum.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import com.amwolfstein.sistrum.settings.ThemeMode
import com.amwolfstein.sistrum.sync.LibraryTrack
import com.amwolfstein.sistrum.ui.theme.SistrumTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TrackContextMenuHapticTest {
    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun favoriteActionConfirmsOnlyWhenAdding() {
        val haptics = mutableListOf<HapticFeedbackType>()
        var change: Boolean? = null
        var expanded by mutableStateOf(false)
        composeTestRule.setContent {
            SistrumTheme(themeMode = ThemeMode.Dark) {
                // The menu renders through the app-level anchored popup host.
                AnchoredPopupMenuHost(hazeState = null) {
                    CompositionLocalProvider(LocalHapticFeedback provides object : HapticFeedback {
                        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { haptics += hapticFeedbackType }
                    }) {
                        TrackContextMenu(
                            track = LibraryTrack(id = "track", title = "Track", artists = "Artist"),
                            expanded = expanded,
                            onDismiss = {},
                            onFavoriteChange = { _, favorite -> change = favorite },
                            anchor = { Box(Modifier.size(40.dp)) },
                        )
                    }
                }
            }
        }

        // Anchored menus resolve their anchor on the first layout, so open the
        // menu after that frame the way an interactive long-press would.
        composeTestRule.runOnIdle { expanded = true }
        composeTestRule.onNodeWithText("Add to favorites").performClick()
        composeTestRule.runOnIdle {
            assertEquals(true, change)
            assertEquals(listOf(HapticFeedbackType.Confirm), haptics)
        }
    }

    @Test
    fun unfavoriteActionDoesNotConfirm() {
        val haptics = mutableListOf<HapticFeedbackType>()
        var change: Boolean? = null
        var expanded by mutableStateOf(false)
        composeTestRule.setContent {
            SistrumTheme(themeMode = ThemeMode.Dark) {
                AnchoredPopupMenuHost(hazeState = null) {
                    CompositionLocalProvider(LocalHapticFeedback provides object : HapticFeedback {
                        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { haptics += hapticFeedbackType }
                    }) {
                        TrackContextMenu(
                            track = LibraryTrack(id = "track", title = "Track", artists = "Artist", metadataJson = "{\"is_favorite\":true}"),
                            expanded = expanded,
                            onDismiss = {},
                            onFavoriteChange = { _, favorite -> change = favorite },
                            anchor = { Box(Modifier.size(40.dp)) },
                        )
                    }
                }
            }
        }

        composeTestRule.runOnIdle { expanded = true }
        composeTestRule.onNodeWithText("Remove from favorites").performClick()
        composeTestRule.runOnIdle {
            assertEquals(false, change)
            assertEquals(emptyList<HapticFeedbackType>(), haptics)
        }
    }

    @Test
    fun findLyricsOpensTheTrackContextSheet() {
        var expanded by mutableStateOf(false)
        composeTestRule.setContent {
            SistrumTheme(themeMode = ThemeMode.Dark) {
                AnchoredPopupMenuHost(hazeState = null) {
                    TrackContextMenu(
                        track = LibraryTrack(id = "track", title = "Track", artists = "Artist"),
                        expanded = expanded,
                        onDismiss = {},
                        anchor = { Box(Modifier.size(40.dp)) },
                    )
                }
            }
        }

        composeTestRule.runOnIdle { expanded = true }
        composeTestRule.onNodeWithText("Find lyrics").performClick()

        composeTestRule.onNodeWithText("Find Lyrics").assertIsDisplayed()
    }
}
