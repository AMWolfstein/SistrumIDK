package com.amwolfstein.sistrum.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import com.amwolfstein.sistrum.R
import com.amwolfstein.sistrum.settings.ThemeMode
import com.amwolfstein.sistrum.ui.theme.SistrumColors
import com.amwolfstein.sistrum.ui.theme.SistrumTheme
import com.amwolfstein.sistrum.ui.theme.LocalSistrumColors
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RadioListTest {
    @get:Rule val compose = createComposeRule()

    @Test fun genericValuesResourceLabelsAndPrimaryCheckFollowTheCallerState() {
        var selected by mutableStateOf(0)
        var theme by mutableStateOf(ThemeMode.Light)
        lateinit var colors: SistrumColors
        val resourceLabel = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.theme_light)
        compose.setContent {
            SistrumTheme(themeMode = theme) {
                colors = LocalSistrumColors.current
                RadioList(
                    options = listOf(
                        SelectionOption(0, label = "First"),
                        SelectionOption(1, labelRes = R.string.theme_light),
                        SelectionOption(2, label = "Third"),
                    ),
                    selectedValue = selected,
                    onValueSelected = { selected = it },
                )
            }
        }
        compose.onNodeWithText("First").assertIsSelected()
        compose.onNodeWithText(resourceLabel).assertIsNotSelected().performClick()
        compose.runOnIdle { assertEquals(1, selected) }
        compose.onNodeWithText("First").assertIsNotSelected()
        compose.onNodeWithText(resourceLabel).assertIsSelected()
        for (mode in listOf(ThemeMode.Light, ThemeMode.Dark)) {
            compose.runOnIdle { theme = mode }
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("radio_list_selected_check", useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals("Selected check must use primary in $mode", colors.primary, layouts.single().layoutInput.style.color)
        }
        compose.onNodeWithText(resourceLabel).performClick()
        compose.runOnIdle { assertEquals(1, selected) }
        compose.runOnIdle { selected = 2 }
        compose.onNodeWithText("Third").assertIsSelected()
        compose.onNodeWithText(resourceLabel).assertIsNotSelected()
    }
}
