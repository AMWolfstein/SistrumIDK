package com.amwolfstein.sistrum.ui.screens

import com.amwolfstein.sistrum.ui.createHostedComposeRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.amwolfstein.sistrum.library.playlist.SmartPlaylistConfig
import com.amwolfstein.sistrum.settings.ThemeMode
import com.amwolfstein.sistrum.ui.theme.SistrumTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class SmartPlaylistContentTest {
    @get:Rule
    val composeTestRule = createHostedComposeRule()

    @Test
    fun builderSavesNameRulesAndLiveFlag() {
        var savedName: String? = null
        var savedConfig: SmartPlaylistConfig? = null
        composeTestRule.setContent {
            SistrumTheme(themeMode = ThemeMode.Dark) {
                SmartPlaylistContent(onSave = { name, config ->
                    savedName = name
                    savedConfig = config
                })
            }
        }

        composeTestRule.onNodeWithTag("smart-playlist-name").performTextInput("Trip hop")
        composeTestRule.onAllNodesWithTag("smart-rule-value")[0].performTextInput("Trip Hop")
        composeTestRule.onNodeWithTag("smart-playlist-add-rule").performClick()
        composeTestRule.onAllNodesWithTag("smart-rule-value")[1].performTextInput("1990")
        composeTestRule.onNodeWithTag("smart-playlist-live").performClick()
        composeTestRule.onNodeWithTag("smart-playlist-save").performClick()

        assertEquals("Trip hop", savedName)
        assertEquals(2, savedConfig?.root?.rules?.size)
        assertFalse(savedConfig?.liveUpdating ?: true)
    }
}
