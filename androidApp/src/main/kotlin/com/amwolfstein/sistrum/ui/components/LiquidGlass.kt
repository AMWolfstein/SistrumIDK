package com.amwolfstein.sistrum.ui.components

import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.blurEffect
import dev.chrisbanes.haze.hazeEffect
import com.amwolfstein.sistrum.ui.theme.SistrumColors

/** The shared backdrop treatment for the persistent navigation and page header. */
fun Modifier.liquidGlassBackground(
    hazeState: HazeState?,
    colors: SistrumColors,
    hazeBlurRadius: Dp = 16.dp,
    glassTint: Color? = null,
): Modifier = if (hazeState == null) {
    background(colors.glassOpaque)
} else {
    hazeEffect(hazeState) {
        // Downsampling makes small glass surfaces look pixelated after upscaling.
        inputScale = HazeInputScale.None
        blurEffect {
            blurRadius = hazeBlurRadius
            colorEffects = listOf(HazeColorEffect.tint(glassTint ?: colors.glass))
        }
    }.background(glassTint ?: colors.glass)
}
