package com.amwolfstein.sistrum.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import com.amwolfstein.sistrum.library.artwork.ArtworkSettings
import com.amwolfstein.sistrum.settings.ThemeMode
import com.amwolfstein.sistrum.ui.theme.SistrumTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class OnlineArtworkContentTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun deezerIsOffByDefaultAndToggleReportsTheChange() {
        var enabled = false
        var changed: Boolean? = null
        composeTestRule.setContent {
            SistrumTheme(themeMode = ThemeMode.Dark) {
                OnlineArtworkContent(
                    settings = ArtworkSettings(),
                    cacheBytes = 1_572_864,
                    assetCount = 12,
                    onDeezerChanged = { changed = it; enabled = it },
                    onClearCache = {},
                )
            }
        }

        assertFalse(enabled)
        composeTestRule.onNodeWithText("Deezer artist artwork").assertIsDisplayed()
        composeTestRule.onNodeWithTag("deezer-artwork-switch").performClick()
        assertTrue(changed == true)
    }

    @Test
    fun onlineArtworkSitsDirectlyBelowLyricsInIntegrations() {
        composeTestRule.setContent {
            SistrumTheme(themeMode = ThemeMode.Dark) {
                IntegrationContent(
                    onLastFmSelected = {},
                    onLyricsSelected = {},
                    onOnlineArtworkSelected = {},
                )
            }
        }

        val lyrics = composeTestRule.onNodeWithText("Lyrics").getUnclippedBoundsInRoot()
        val artwork = composeTestRule.onNodeWithText("Online Artwork").getUnclippedBoundsInRoot()
        assertTrue(artwork.top > lyrics.top)
        composeTestRule.onNodeWithText("Online Artwork").assertIsDisplayed()
    }

    @Test
    fun cacheSummaryAndClearActionAreShown() {
        var cleared = false
        composeTestRule.setContent {
            SistrumTheme(themeMode = ThemeMode.Dark) {
                OnlineArtworkContent(
                    settings = ArtworkSettings(deezerEnabled = true),
                    cacheBytes = 2_097_152,
                    assetCount = 3,
                    onDeezerChanged = {},
                    onClearCache = { cleared = true },
                )
            }
        }

        composeTestRule.onNodeWithTag("artwork-cache-summary").assertIsDisplayed()
        composeTestRule.onNodeWithText("3 cached images, 2.0 MB used").assertIsDisplayed()
        composeTestRule.onNodeWithText("Clear unused artwork").performClick()
        assertTrue(cleared)
    }
}
