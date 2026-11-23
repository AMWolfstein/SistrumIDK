package com.amwolfstein.sistrum.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
): Int? = alphabeticalIndexEntryAt(y - labelsTop, labelsHeight, entries)?.itemIndex

private fun alphabeticalIndexEntryAt(
    y: Float,
    height: Int,
    entries: List<AlphabeticalIndexEntry>,
): AlphabeticalIndexEntry? = entries.takeIf { height > 0 && it.isNotEmpty() }?.let {
    val pixel = y.toInt().coerceIn(0, height - 1)
    it[pixel * it.size / height]
}

internal fun alphabeticalIndexVisibleSlots(count: Int, capacity: Int): List<Int> {
    val visibleCount = minOf(count, capacity)
    if (visibleCount <= 1) return List(visibleCount.coerceAtLeast(0)) { 0 }
    val intervals = visibleCount - 1
    return List(visibleCount) { index -> (index * (count - 1) + intervals / 2) / intervals }
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

    var activeEntry by remember(entries) { mutableStateOf<AlphabeticalIndexEntry?>(null) }
    val scope = rememberCoroutineScope()
    val colors = LocalSistrumColors.current
    val hapticFeedback = LocalHapticFeedback.current
    val indexDescription = "${stringResource(R.string.alphabetical_index)}: ${entries.joinToString { it.label }}"
    val labelStyle = MaterialTheme.typography.labelSmall

    BoxWithConstraints(modifier.width(18.dp)) {
        val labelHeight = with(LocalDensity.current) { labelStyle.lineHeight.toDp() }
        val railHeight = minOf(maxHeight, labelHeight * entries.size)
        if (railHeight <= 0.dp) return@BoxWithConstraints

        val visibleEntries = alphabeticalIndexVisibleSlots(entries.size, (railHeight / labelHeight).toInt().coerceAtLeast(1))
            .map(entries::get)
        val labelHeightPx = with(LocalDensity.current) { labelHeight.roundToPx() }
        Layout(
            modifier = Modifier
                .height(railHeight)
                .semantics { contentDescription = indexDescription }
                .pointerInput(entries, visibleEntries, listState, hapticFeedback) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        fun selectAt(y: Float, dragging: Boolean = false) {
                            val entry = alphabeticalIndexEntryAt(y, size.height, if (dragging) entries else visibleEntries) ?: return
                            if (entry == activeEntry) return
                            activeEntry = entry
                            scope.launch { listState.scrollToItem(entry.itemIndex) }
                            if (dragging) hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                        }

                        try {
                            selectAt(down.position.y)
                            verticalDrag(down.id) { change ->
                                selectAt(change.position.y, dragging = true)
                                change.consume()
                            }
                        } finally {
                            activeEntry = null
                        }
                    }
                },
            content = {
                visibleEntries.forEach { entry ->
                    Text(
                        text = entry.label,
                        style = labelStyle,
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
                val text = label.measure(Constraints(minWidth = width, maxWidth = width, maxHeight = minOf(bottom - top, labelHeightPx)))
                text to (top + (bottom - top - text.height) / 2)
            }
            layout(width, height) {
                placements.forEach { (text, y) -> text.placeRelative(0, y) }
            }
        }
        // In a dense index, preview every section while scrubbing, including those without a printed label.
        if (visibleEntries.size < entries.size) {
            activeEntry?.let { entry ->
                Box(Modifier.matchParentSize()) {
                    Box(
                        modifier = Modifier.align(Alignment.CenterStart)
                            .offset(x = (-36).dp)
                            .wrapContentSize(Alignment.Center, unbounded = true)
                            .size(32.dp)
                            .background(colors.card, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(entry.label, style = MaterialTheme.typography.titleMedium, color = colors.primary)
                    }
                }
            }
        }
    }
}
