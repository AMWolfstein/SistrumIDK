package com.amwolfstein.sistrum.ui.navigation

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.amwolfstein.sistrum.settings.ThemeMode
import com.amwolfstein.sistrum.ui.theme.SistrumTheme
import com.amwolfstein.sistrum.ui.theme.LocalSistrumColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

class FullScreenPlayerArtworkTest {
    @get:Rule val compose = createComposeRule()

    @Test fun squareBackgroundBlendsInBothDirectionsAndCanReverseMidTransition() {
        var showArtwork by mutableStateOf(true)
        var theme by mutableStateOf(ThemeMode.Light)
        val artwork = FullScreenArtwork(
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.BLUE)
            }.asImageBitmap(),
            dominant = Color.Red,
        )
        compose.setContent {
            SistrumTheme(theme) {
                FullScreenPlayerBackground(
                    artwork = artwork, outgoingArtwork = null, incomingArtwork = null,
                    crossfadeProgress = 1f, isArtworkCrossfading = false,
                    modifier = Modifier.size(128.dp).testTag("background_probe"),
                    showArtwork = showArtwork, livingArtworkBackground = false,
                )
            }
        }
        compose.mainClock.autoAdvance = false
        fun sample(): Color {
            // Let the pending composition and draw settle before sampling pixels.
            compose.waitForIdle()
            val pixels = compose.onNodeWithTag("background_probe").captureToImage().toPixelMap()
            return pixels[pixels.width / 2, pixels.height / 2]
        }
        fun distance(a: Color, b: Color): Float =
            maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue))

        for (mode in listOf(ThemeMode.Light, ThemeMode.Dark)) {
            compose.runOnIdle { theme = mode; showArtwork = true }
            compose.mainClock.advanceTimeBy(300)
            val main = sample()
            compose.runOnIdle { showArtwork = false }
            compose.mainClock.advanceTimeByFrame()
            assertTrue("Opening a panel must not replace the background immediately", distance(main, sample()) < 0.02f)
            compose.mainClock.advanceTimeBy(96)
            val opening = sample()
            compose.onNodeWithTag("square_player_artwork_background").assertExists()
            compose.mainClock.advanceTimeBy(300)
            val panel = sample()
            assertTrue(distance(main, opening) > 0.02f)
            assertTrue(distance(panel, opening) > 0.02f)
            compose.onNodeWithTag("square_player_artwork_background").assertDoesNotExist()

            compose.runOnIdle { showArtwork = true }
            compose.mainClock.advanceTimeByFrame()
            assertTrue("Returning to Main must start at the panel background", distance(panel, sample()) < 0.02f)
            compose.mainClock.advanceTimeBy(96)
            val closing = sample()
            assertTrue(distance(main, closing) > 0.02f)
            assertTrue(distance(panel, closing) > 0.02f)
            compose.runOnIdle { showArtwork = false }
            // Sample before the next animation frame: the reversal must keep the
            // current blend instead of snapping back to either endpoint.
            assertTrue("Reversing must retain the current blend", distance(closing, sample()) < 0.02f)
            compose.mainClock.advanceTimeBy(300)
            assertTrue(distance(panel, sample()) < 0.02f)
        }
    }

    @Test fun livingArtworkAddsShadowOutsideTheCoverInBothThemes() {
        var theme by mutableStateOf(ThemeMode.Light)
        var living by mutableStateOf(false)
        compose.setContent {
            SistrumTheme(theme) {
                val colors = LocalSistrumColors.current
                Box(
                    Modifier.size(256.dp).background(colors.card).testTag("shadow_probe"),
                    contentAlignment = Alignment.Center,
                ) {
                    FullScreenPlayerArtwork(
                        artwork = null, outgoingArtwork = null, incomingArtwork = null,
                        crossfadeProgress = 1f, isArtworkCrossfading = false,
                        modifier = Modifier.size(128.dp), livingArtworkBackground = living,
                    )
                }
            }
        }
        for (mode in listOf(ThemeMode.Light, ThemeMode.Dark)) {
            compose.runOnIdle { theme = mode; living = false }
            compose.waitForIdle()
            val solid = compose.onNodeWithTag("shadow_probe").captureToImage()
            compose.runOnIdle { living = true }
            compose.waitForIdle()
            val raised = compose.onNodeWithTag("shadow_probe").captureToImage()
            assertTrue("Living artwork must cast a shadow in $mode", belowCoverBrightness(raised) < belowCoverBrightness(solid) - 0.001f)
            val solidPixels = solid.toPixelMap()
            val raisedPixels = raised.toPixelMap()
            val solidCover = solidPixels[solid.width / 3, solid.height / 3]
            val raisedCover = raisedPixels[raised.width / 3, raised.height / 3]
            // Elevation renders the cover through a shadow layer, so compositing can
            // round each channel by a couple of steps; that is not a tint.
            assertEquals("Shadow must not tint the cover (red)", solidCover.red, raisedCover.red, 0.01f)
            assertEquals("Shadow must not tint the cover (green)", solidCover.green, raisedCover.green, 0.01f)
            assertEquals("Shadow must not tint the cover (blue)", solidCover.blue, raisedCover.blue, 0.01f)
            assertEquals("Shadow must not tint the cover (alpha)", solidCover.alpha, raisedCover.alpha, 0.01f)
            compose.runOnIdle { living = false }
            compose.waitForIdle()
            val restored = compose.onNodeWithTag("shadow_probe").captureToImage()
            assertEquals(belowCoverBrightness(solid), belowCoverBrightness(restored), 0.001f)
        }
    }

    private fun belowCoverBrightness(image: ImageBitmap): Float {
        val pixels = image.toPixelMap()
        var total = 0f
        var count = 0
        for (y in image.height * 76 / 100 until image.height * 82 / 100) {
            for (x in image.width * 3 / 8 until image.width * 5 / 8) {
                val color = pixels[x, y]
                total += (color.red + color.green + color.blue) / 3f
                count++
            }
        }
        return total / count
    }
}
