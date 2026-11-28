package com.amwolfstein.sistrum.ui.components

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.amwolfstein.sistrum.settings.ThemeMode
import com.amwolfstein.sistrum.ui.theme.SistrumTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SistrumMarqueeTextTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun overflowingTextPausesThenScrolls() {
        checkMotion("Very Long Track Title That Will Overflow The Container Width", scrolls = true)
    }

    @Test
    fun fittingTextRemainsStill() {
        checkMotion("Title", scrolls = false)
    }

    @Test
    fun fadeSoftensOnlyTheTrailingEdgeOfOverflowingText() {
        checkFade("████████████████████████████████", overflows = true)
    }

    @Test
    fun fadeLeavesFittingTextUnchanged() {
        checkFade("Title", overflows = false)
    }

    private fun checkFade(text: String, overflows: Boolean) {
        var fade by mutableStateOf(false)
        var theme by mutableStateOf(ThemeMode.Dark)
        var fadeWidth = 0
        var containerWidth = 0
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            SistrumTheme(themeMode = theme) {
                with(androidx.compose.ui.platform.LocalDensity.current) {
                    fadeWidth = 16.dp.roundToPx()
                    containerWidth = 120.dp.roundToPx()
                }
                Box(Modifier.width(120.dp)) {
                    SistrumMarqueeText(text, MaterialTheme.colorScheme.onSurface, MaterialTheme.typography.bodyMedium, fade = fade)
                }
            }
        }

        for (mode in listOf(ThemeMode.Dark, ThemeMode.Light)) {
            composeTestRule.runOnIdle { theme = mode; fade = false }
            composeTestRule.mainClock.advanceTimeBy(300)
            val node = composeTestRule.onNodeWithText(text).assertIsDisplayed()
            val original = node.captureToImage().asAndroidBitmap()
            composeTestRule.runOnIdle { fade = true }
            composeTestRule.mainClock.advanceTimeByFrame()
            val faded = node.captureToImage().asAndroidBitmap()
            // The fade mask is anchored to the 120dp container, not to the wider
            // bitmap that captureToImage returns for the marquee content.
            val unchangedWidth = containerWidth - fadeWidth
            assertTrue(
                "Fade must preserve text before the trailing edge in $mode",
                sameWithRasterizationTolerance(original, faded, unchangedWidth),
            )
            if (overflows) assertFalse("Overflowing text must fade in $mode", original.sameAs(faded))
            else assertTrue("Fitting text must not fade in $mode", sameWithRasterizationTolerance(original, faded, original.width))
        }
    }

    /**
     * Enabling [SistrumMarqueeText]'s fade routes the text through an offscreen
     * layer, which changes antialiasing by a few steps per channel. Compare with
     * that rasterization tolerance but reject any visible tint.
     */
    private fun sameWithRasterizationTolerance(first: Bitmap, second: Bitmap, width: Int): Boolean {
        if (first.width != second.width || first.height != second.height) return false
        for (y in 0 until first.height) for (x in 0 until width) {
            val a = first.getPixel(x, y)
            val b = second.getPixel(x, y)
            if (kotlin.math.abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)) > 6 ||
                kotlin.math.abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)) > 6 ||
                kotlin.math.abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b)) > 6) return false
        }
        return true
    }

    private fun checkMotion(text: String, scrolls: Boolean) {
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            SistrumTheme(themeMode = ThemeMode.Dark) {
                Box(Modifier.width(120.dp)) {
                    SistrumMarqueeText(
                        text = text,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        val node = composeTestRule.onNodeWithText(text).assertIsDisplayed()
        val initial = node.captureToImage().asAndroidBitmap()
        composeTestRule.mainClock.advanceTimeBy(2_000)
        assertTrue("Text must pause initially", initial.sameAs(node.captureToImage().asAndroidBitmap()))
        composeTestRule.mainClock.advanceTimeBy(2_000)
        val unchanged = initial.sameAs(node.captureToImage().asAndroidBitmap())
        if (scrolls) assertFalse("Overflowing text must scroll after three seconds", unchanged)
        else assertTrue("Fitting text must stay still", unchanged)
    }
}
