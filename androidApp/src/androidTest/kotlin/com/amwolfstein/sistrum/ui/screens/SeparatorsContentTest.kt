package com.amwolfstein.sistrum.ui.screens

import com.amwolfstein.sistrum.ui.createHostedComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.amwolfstein.sistrum.settings.ThemeMode
import com.amwolfstein.sistrum.ui.theme.SistrumTheme
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SeparatorsContentTest {
    @get:Rule
    val composeTestRule = createHostedComposeRule()

    private val dataStoreFile: File
        get() = File(
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "datastore/separators.preferences_pb",
        )

    @Before
    fun resetPreferences() {
        dataStoreFile.delete()
    }

    @After
    fun tearDown() {
        dataStoreFile.delete()
    }

    private fun showPage() {
        composeTestRule.setContent {
            SistrumTheme(themeMode = ThemeMode.Dark) {
                SeparatorsContent()
            }
        }
        composeTestRule.waitUntil {
            composeTestRule.onAllNodesWithTag("separators-input-artist").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun showsAllFourCategoriesWithDefaults() {
        showPage()
        composeTestRule.onNodeWithTag("separators-input-artist").assertIsDisplayed()
        composeTestRule.onNodeWithTag("separators-input-album_artist").assertExists()
        composeTestRule.onNodeWithTag("separators-input-genre").assertExists()
        composeTestRule.onNodeWithTag("separators-input-composer").assertExists()
        composeTestRule.onNodeWithTag("separators-rebuild").assertExists()
    }

    @Test
    fun duplicateSeparatorIsRejectedWithAnError() {
        showPage()
        composeTestRule.onNodeWithTag("separators-input-artist").performTextInput(";")
        composeTestRule.onNodeWithTag("separators-add-artist").performClick()
        composeTestRule.onNodeWithTag("separators-error").assertExists()
    }

    @Test
    fun tooLongSeparatorIsRejected() {
        showPage()
        composeTestRule.onNodeWithTag("separators-input-genre").performTextInput("abcdef")
        composeTestRule.onNodeWithTag("separators-add-genre").performClick()
        composeTestRule.onNodeWithTag("separators-error").assertExists()
    }
}
