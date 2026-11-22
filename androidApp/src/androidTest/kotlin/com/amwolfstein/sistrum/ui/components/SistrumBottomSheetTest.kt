package com.amwolfstein.sistrum.ui.components

import android.view.KeyEvent
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import com.amwolfstein.sistrum.ui.theme.SistrumColors
import com.amwolfstein.sistrum.ui.theme.LocalSistrumColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.geometry.Offset
import androidx.test.platform.app.InstrumentationRegistry
import com.amwolfstein.sistrum.settings.ThemeMode
import com.amwolfstein.sistrum.ui.theme.SistrumTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse

class SistrumBottomSheetTest {
    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun implicitTitleAndBodyFollowThemeChangesWhileExplicitColorsArePreserved() {
        var theme by mutableStateOf(ThemeMode.Dark)
        lateinit var colors: SistrumColors
        composeTestRule.setContent {
            SistrumTheme(themeMode = theme) {
                colors = LocalSistrumColors.current
                SistrumBottomSheet(title = { Text("Sheet title") }, onDismiss = {}) {
                    Text("Default body")
                    Text("Muted body", color = colors.textMuted)
                    Text("Explicit accent", color = colors.primary)
                }
            }
        }
        for (mode in listOf(ThemeMode.Dark, ThemeMode.Light, ThemeMode.Dark)) {
            composeTestRule.runOnIdle { theme = mode }
            assertEquals("Sheet title must use $mode foreground", colors.textMain, textColor("Sheet title"))
            assertEquals(colors.textMain, textColor("Default body"))
            assertNotEquals("Title must remain distinct from the sheet surface", colors.glassOpaque, textColor("Sheet title"))
            assertEquals(colors.textMuted, textColor("Muted body"))
            assertEquals(colors.primary, textColor("Explicit accent"))
        }
    }

    private fun textColor(label: String): Color {
        val results = mutableListOf<TextLayoutResult>()
        composeTestRule.onNodeWithText(label).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single().layoutInput.style.color
    }

    @Test
    fun systemBackDismissesSheet() {
        var visible by mutableStateOf(true)
        var dismissed = false
        composeTestRule.setContent {
            SistrumTheme(themeMode = ThemeMode.Dark) {
                if (visible) SistrumBottomSheet(
                    title = { Text("Sheet") },
                    onDismiss = { dismissed = true; visible = false },
                ) {}
            }
        }

        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        composeTestRule.waitUntil(1_000) { dismissed }
        assertTrue(dismissed)
    }

    @Test
    fun tappingHeaderDoesNotDismissSheet() {
        var visible by mutableStateOf(true)
        var dismissed = false
        composeTestRule.setContent {
            SistrumTheme(themeMode = ThemeMode.Dark) {
                if (visible) SistrumBottomSheet(
                    title = { Text("Sheet") },
                    onDismiss = { dismissed = true; visible = false },
                ) {}
            }
        }

        composeTestRule.onNodeWithText("Sheet").performTouchInput { click() }
        composeTestRule.waitForIdle()

        assertFalse(dismissed)
    }

    @Test
    fun draggingHeaderDownDismissesSheet() {
        var visible by mutableStateOf(true)
        var dismissed = false
        composeTestRule.setContent {
            SistrumTheme(themeMode = ThemeMode.Dark) {
                if (visible) SistrumBottomSheet(
                    title = { Text("Sheet") },
                    onDismiss = { dismissed = true; visible = false },
                ) {}
            }
        }

        composeTestRule.onNodeWithTag("bottom-sheet-header").performTouchInput { swipeDown() }
        composeTestRule.waitUntil(1_000) { dismissed }

        assertTrue(dismissed)
    }

    @Test
    fun shortHeaderDragKeepsSheetOpen() {
        var visible by mutableStateOf(true)
        var dismissed = false
        composeTestRule.setContent {
            SistrumTheme(themeMode = ThemeMode.Dark) {
                if (visible) SistrumBottomSheet(
                    title = { Text("Sheet") },
                    onDismiss = { dismissed = true; visible = false },
                ) {}
            }
        }

        composeTestRule.onNodeWithTag("bottom-sheet-header").performTouchInput {
            down(center)
            moveTo(center + Offset(0f, 24f))
            up()
        }
        composeTestRule.waitForIdle()

        assertFalse(dismissed)
    }
}
