package com.amwolfstein.sistrum.ui.components

import android.animation.ValueAnimator
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Loops overflowing text with a trailing copy and a pause at the start of each cycle. */
@Composable
fun SistrumMarqueeText(
    text: String,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
    fade: Boolean = false,
    animate: Boolean = true,
) = SistrumMarqueeText(AnnotatedString(text), color, style, modifier, fade, animate)

@Composable
internal fun SistrumMarqueeText(
    text: AnnotatedString,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
    fade: Boolean = false,
    animate: Boolean = true,
    inlineContent: Map<String, InlineTextContent> = emptyMap(),
) {
    key(text) {
        var containerWidth by remember { mutableIntStateOf(0) }
        var textWidth by remember { mutableIntStateOf(0) }
        Text(
            text = text,
            inlineContent = inlineContent,
            modifier = modifier.fillMaxWidth().onSizeChanged { containerWidth = it.width }
                .graphicsLayer {
                    compositingStrategy = if (fade) CompositingStrategy.Offscreen else CompositingStrategy.Auto
                }
                .drawWithCache {
                    val rtl = isRtlText(text.text, layoutDirection)
                    val mask = Brush.horizontalGradient(
                        colors = listOf(color.copy(alpha = 1f), color.copy(alpha = 0f)),
                        startX = if (rtl) minOf(16.dp.toPx(), size.width) else (size.width - 16.dp.toPx()).coerceAtLeast(0f),
                        endX = if (rtl) 0f else size.width,
                    )
                    onDrawWithContent {
                        drawContent()
                        if (fade && textWidth > containerWidth) drawRect(mask, blendMode = BlendMode.DstIn)
                    }
                }.basicMarquee(
                iterations = if (animate && ValueAnimator.areAnimatorsEnabled()) Int.MAX_VALUE else 0,
                repeatDelayMillis = 3_000,
                initialDelayMillis = 3_000,
                spacing = MarqueeSpacing(32.dp),
                velocity = 20.dp,
            ),
            color = color,
            style = style,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            onTextLayout = { textWidth = it.size.width },
        )
    }
}
