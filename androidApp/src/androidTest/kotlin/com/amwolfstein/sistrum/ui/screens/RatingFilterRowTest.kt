package com.amwolfstein.sistrum.ui.screens

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.amwolfstein.sistrum.settings.ThemeMode
import com.amwolfstein.sistrum.ui.theme.SistrumTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.junit.Rule
import org.junit.Test

class RatingFilterRowTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun tappingAStarSelectsItAndTappingAgainClearsTheFilter() {
        var selected by androidx.compose.runtime.mutableStateOf<Int?>(null)
        composeTestRule.setContent {
            SistrumTheme(themeMode = ThemeMode.Dark) {
                RatingFilterRow(selected = selected, onSelected = { selected = it })
            }
        }

        composeTestRule.onNodeWithTag("rating-filter-star-3").performClick()
        assertEquals(3, selected)
        composeTestRule.onNodeWithTag("rating-filter-star-3").performClick()
        assertNull(selected)
    }
}
