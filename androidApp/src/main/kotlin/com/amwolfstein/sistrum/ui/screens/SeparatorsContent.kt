package com.amwolfstein.sistrum.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amwolfstein.sistrum.R
import com.amwolfstein.sistrum.library.db.LibraryDatabase
import com.amwolfstein.sistrum.library.metadata.RebuildResult
import com.amwolfstein.sistrum.library.metadata.RelationshipRebuilder
import com.amwolfstein.sistrum.library.metadata.SeparatorCategory
import com.amwolfstein.sistrum.library.metadata.SeparatorConfig
import com.amwolfstein.sistrum.library.metadata.SeparatorPreferences
import com.amwolfstein.sistrum.library.metadata.Separators
import com.amwolfstein.sistrum.ui.components.LabeledCard
import com.amwolfstein.sistrum.ui.components.SistrumPillButton
import com.amwolfstein.sistrum.ui.components.SistrumPillButtonVariant
import com.amwolfstein.sistrum.ui.theme.LocalSistrumColors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/** `Settings → Library → Metadata separators`; edits are persisted per category. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SeparatorsContent(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val preferences = remember { SeparatorPreferences(context) }
    val config by preferences.settings.collectAsStateWithLifecycle(initialValue = SeparatorConfig())
    val scope = rememberCoroutineScope()
    var validationError by remember { mutableStateOf<Separators.ValidationError?>(null) }
    var rebuilding by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<RebuildResult?>(null) }
    var rebuildFailed by remember { mutableStateOf(false) }

    fun add(category: SeparatorCategory, value: String) {
        val current = config.delimitersFor(category)
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            validationError = Separators.ValidationError.Empty
            return
        }
        if (trimmed.codePointCount(0, trimmed.length) > Separators.MAX_LENGTH) {
            validationError = Separators.ValidationError.TooLong(trimmed)
            return
        }
        if (current.any { it == trimmed }) {
            validationError = Separators.ValidationError.Duplicate(trimmed)
            return
        }
        validationError = null
        scope.launch { preferences.setCategory(category, current + trimmed) }
    }

    fun remove(category: SeparatorCategory, value: String) {
        validationError = null
        scope.launch { preferences.setCategory(category, config.delimitersFor(category) - value) }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.separators_description),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalSistrumColors.current.textMuted,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        SeparatorCategory.entries.forEach { category ->
            SeparatorCategoryCard(
                category = category,
                delimiters = config.delimitersFor(category),
                onAdd = { add(category, it) },
                onRemove = { remove(category, it) },
                onReset = {
                    validationError = null
                    scope.launch { preferences.setCategory(category, Separators.DEFAULT_DELIMITERS) }
                },
            )
        }
        validationError?.let { error ->
            Text(
                text = when (error) {
                    Separators.ValidationError.Empty -> stringResource(R.string.separators_error_empty)
                    is Separators.ValidationError.TooLong -> stringResource(
                        R.string.separators_error_too_long,
                        error.value,
                        Separators.MAX_LENGTH,
                    )
                    is Separators.ValidationError.Duplicate -> stringResource(
                        R.string.separators_error_duplicate,
                        error.value,
                    )
                },
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 4.dp).testTag("separators-error"),
            )
        }
        SistrumPillButton(
            label = stringResource(
                if (rebuilding) R.string.separators_rebuilding else R.string.separators_rebuild,
            ),
            onClick = {
                if (rebuilding) return@SistrumPillButton
                rebuilding = true
                rebuildFailed = false
                result = null
                scope.launch {
                    // Read the latest persisted config so a just-added separator is included.
                    val latest = preferences.settings.first()
                    runCatching {
                        RelationshipRebuilder(LibraryDatabase.get(context)).rebuild(latest)
                    }.onSuccess { result = it }.onFailure { rebuildFailed = true }
                    rebuilding = false
                }
            },
            enabled = !rebuilding,
            variant = SistrumPillButtonVariant.Primary,
            modifier = Modifier.testTag("separators-rebuild"),
        )
        Text(
            text = when {
                rebuildFailed -> stringResource(R.string.separators_rebuild_failed)
                result != null -> stringResource(
                    R.string.separators_rebuild_done,
                    result?.items ?: 0,
                    result?.artists ?: 0,
                    result?.genres ?: 0,
                    result?.composers ?: 0,
                )
                else -> stringResource(R.string.separators_rebuild_hint)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (rebuildFailed) MaterialTheme.colorScheme.error else LocalSistrumColors.current.textMuted,
            modifier = Modifier.padding(horizontal = 4.dp).testTag("separators-rebuild-status"),
        )
    }
}

@Composable
private fun SeparatorCategoryCard(
    category: SeparatorCategory,
    delimiters: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onReset: () -> Unit,
) {
    var input by remember(category) { mutableStateOf("") }
    LabeledCard(label = stringResource(category.labelRes())) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (delimiters.isEmpty()) {
                Text(
                    text = stringResource(R.string.separators_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalSistrumColors.current.textMuted,
                )
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    delimiters.forEach { delimiter ->
                        DelimiterChip(
                            label = if (delimiter.isBlank()) "␠" else delimiter,
                            enabled = !isProtected(delimiter),
                            onRemove = { onRemove(delimiter) },
                            category = category,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.take(10) },
                    label = { Text(stringResource(R.string.separators_add_label)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f).testTag("separators-input-${category.id}"),
                )
                TextButton(
                    onClick = {
                        onAdd(input)
                        input = ""
                    },
                    modifier = Modifier.testTag("separators-add-${category.id}"),
                ) {
                    Text(stringResource(R.string.separators_add))
                }
            }
            Text(
                text = stringResource(R.string.separators_frame_note),
                style = MaterialTheme.typography.bodySmall,
                color = LocalSistrumColors.current.textMuted,
            )
            TextButton(onClick = onReset, modifier = Modifier.testTag("separators-reset-${category.id}")) {
                Text(stringResource(R.string.separators_reset_defaults))
            }
        }
    }
}

@Composable
private fun DelimiterChip(
    label: String,
    enabled: Boolean,
    onRemove: () -> Unit,
    category: SeparatorCategory,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(LocalSistrumColors.current.card)
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
            .testTag("separators-chip-${category.id}-$label"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = onRemove, enabled = enabled) {
            Text("×")
        }
    }
}

private fun isProtected(delimiter: String): Boolean = delimiter.isBlank()

private fun SeparatorCategory.labelRes(): Int = when (this) {
    SeparatorCategory.ARTIST -> R.string.separators_artist
    SeparatorCategory.ALBUM_ARTIST -> R.string.separators_album_artist
    SeparatorCategory.GENRE -> R.string.separators_genre
    SeparatorCategory.COMPOSER -> R.string.separators_composer
}
