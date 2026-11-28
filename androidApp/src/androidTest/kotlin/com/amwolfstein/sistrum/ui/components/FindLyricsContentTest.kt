package com.amwolfstein.sistrum.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import java.util.Locale
import com.amwolfstein.sistrum.settings.ThemeMode
import com.amwolfstein.sistrum.ui.components.MaterialSymbols
import com.amwolfstein.sistrum.sync.LibraryTrack
import com.amwolfstein.sistrum.ui.theme.SistrumTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FindLyricsContentTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var textMeasurer: TextMeasurer

    @Test
    fun durationFitsLongPlaceholderAndGrowsForLongValues() {
        var metadata by mutableStateOf("{}")
        var density = 1f
        compose.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                setLocale(Locale.forLanguageTag("ru"))
            }
            val context = LocalContext.current.createConfigurationContext(configuration)
            density = LocalDensity.current.density
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides configuration,
                LocalDensity provides Density(density, fontScale = 1.3f),
            ) {
                textMeasurer = rememberTextMeasurer()
                SistrumTheme(themeMode = ThemeMode.Dark) {
                    val track = LibraryTrack("track-1", "Track", "Artist", metadataJson = metadata)
                    Box(Modifier.size(width = 320.dp, height = 640.dp)) {
                        FindLyricsContent(track, FindLyricsSearchState(track), { _, _, _ -> emptyList() }, {})
                    }
                }
            }
        }

        val label = "Длительность"
        val placeholderLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(label, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(placeholderLayouts) }
        assertEquals(1, placeholderLayouts.single().lineCount)
        assertFalse(placeholderLayouts.single().hasVisualOverflow)

        var shortWidth = 0f
        for (seconds in listOf(245L, 360000L)) {
            compose.runOnIdle { metadata = """{"duration":$seconds}""" }
            val value = if (seconds == 245L) "4:05" else "6000:00"
            val field = compose.onNodeWithContentDescription(label).assertIsDisplayed()
            val fieldBounds = field.fetchSemanticsNode().boundsInRoot
            // The field semantics node covers the content area inside the 18dp
            // horizontal paddings. Derive the room left for the value from the
            // laid-out icon (it grows with fontScale) and the 10dp icon gap.
            val iconBounds = compose.onNodeWithText(MaterialSymbols.Schedule, useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            val available = fieldBounds.width - iconBounds.width - 10f * density
            val textWidth = measureValueWidth(value)
            assertTrue(
                "Duration $value ($textWidth px) must fit the field content width ($available px)",
                textWidth <= available + 2,
            )
            val width = fieldBounds.width
            if (seconds == 245L) shortWidth = width
            else assertTrue("Duration field must grow for a longer value", width > shortWidth)
        }
    }

    /** Measures the value with the field's own text style at the test density. */
    private fun measureValueWidth(value: String): Int = textMeasurer.measure(
        text = AnnotatedString(value),
        style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    ).size.width
}
