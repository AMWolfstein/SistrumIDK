package com.amwolfstein.sistrum.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amwolfstein.sistrum.R
import com.amwolfstein.sistrum.ui.libraryAlphabeticalIndexLabel
import com.amwolfstein.sistrum.ui.theme.LocalSistrumColors
import kotlinx.coroutines.launch

internal data class AlphabeticalIndexEntry(val label: String, val itemIndex: Int)

internal fun alphabeticalIndexEntries(
    values: List<String>,
    itemOffset: Int,
    itemsPerLazyItem: Int = 1,
): List<AlphabeticalIndexEntry> = values.mapIndexedNotNull { index, value ->
    alphabeticalIndexLabel(value)?.let { label ->
        AlphabeticalIndexEntry(label, itemOffset + index / itemsPerLazyItem)
    }
}.distinctBy(AlphabeticalIndexEntry::label)

internal fun alphabeticalIndexLabel(value: String): String? = libraryAlphabeticalIndexLabel(value)

internal fun alphabeticalIndexItemIndexAt(
    y: Float,
    labelsTop: Float,
    labelsHeight: Int,
    entries: List<AlphabeticalIndexEntry>,
): Int? = entries.takeIf { labelsHeight > 0 && it.isNotEmpty() }?.let {
    val pixel = (y - labelsTop).toInt().coerceIn(0, labelsHeight - 1)
    it[pixel * it.size / labelsHeight].itemIndex
}

// Match the touch calculation's floor division at every pixel boundary.
internal fun alphabeticalIndexSlotTop(index: Int, height: Int, count: Int): Int =
    (index * height + count - 1) / count

/** Floating fast-scroll rail; its parent keeps it outside LazyColumn content. */
@Composable
internal fun AlphabeticalIndex(
    entries: List<AlphabeticalIndexEntry>,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) return

    val scope = rememberCoroutineScope()
    val colors = LocalSistrumColors.current
    val hapticFeedback = LocalHapticFeedback.current
    val indexDescription = "${stringResource(R.string.alphabetical_index)}: ${entries.joinToString { it.label }}"
    val labelStyle = MaterialTheme.typography.labelSmall

    BoxWithConstraints(modifier) {
        val railHeight = with(LocalDensity.current) {
            minOf(maxHeight, labelStyle.lineHeight.toDp() * entries.size)
        }
        val slotHeight = with(LocalDensity.current) { (railHeight / entries.size).toSp() }
        val fittedStyle = labelStyle.copy(
            fontSize = minOf(labelStyle.fontSize.value, slotHeight.value * 0.8f).sp,
            lineHeight = slotHeight,
        )
        Layout(
            modifier = Modifier
                .width(18.dp)
                .height(railHeight)
                .semantics { contentDescription = indexDescription }
                .pointerInput(entries, listState, hapticFeedback) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var selectedEntry: Int? = null

                        fun selectAt(y: Float, haptic: Boolean = false) {
                            val entry = alphabeticalIndexItemIndexAt(y, 0f, size.height, entries) ?: return
                            if (entry == selectedEntry) return
                            selectedEntry = entry
                            scope.launch { listState.scrollToItem(entry) }
                            if (haptic) hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                        }

                        selectAt(down.position.y)
                        verticalDrag(down.id) { change ->
                            selectAt(change.position.y, haptic = true)
                            change.consume()
                        }
                    }
                },
            content = {
                entries.forEach { entry ->
                    Text(
                        text = entry.label,
                        style = fittedStyle,
                        color = colors.primary,
                        maxLines = 1,
                        softWrap = false,
                        textAlign = TextAlign.Center,
                    )
                }
            },
        ) { labels, constraints ->
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val placements = labels.mapIndexed { index, label ->
                val top = alphabeticalIndexSlotTop(index, height, labels.size)
                val bottom = alphabeticalIndexSlotTop(index + 1, height, labels.size)
                val slot = bottom - top
                val text = label.measure(Constraints(minWidth = width, maxWidth = width, maxHeight = slot))
                text to (top + (slot - text.height) / 2)
            }
            layout(width, height) {
                placements.forEach { (text, y) -> text.placeRelative(0, y) }
            }
        }
    }
}
