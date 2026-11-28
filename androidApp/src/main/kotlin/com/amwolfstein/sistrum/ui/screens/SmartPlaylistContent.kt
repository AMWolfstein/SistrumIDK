package com.amwolfstein.sistrum.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.amwolfstein.sistrum.R
import com.amwolfstein.sistrum.library.playlist.SmartPlaylistConfig
import com.amwolfstein.sistrum.library.playlist.SmartPlaylistLimit
import com.amwolfstein.sistrum.library.playlist.SmartRule
import com.amwolfstein.sistrum.library.playlist.SmartRuleGroup
import com.amwolfstein.sistrum.library.playlist.SmartRules
import com.amwolfstein.sistrum.ui.components.LabeledCard
import com.amwolfstein.sistrum.ui.components.SistrumPillButton
import com.amwolfstein.sistrum.ui.components.SistrumPillButtonVariant
import com.amwolfstein.sistrum.ui.theme.LocalSistrumColors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

private data class RuleDraft(
    var field: String = "genre",
    var op: String = "is",
    var value: String = "",
    var secondValue: String = "",
)

private class GroupDraft(var match: String = "all") {
    val rules = mutableStateListOf<RuleDraft>()
    val groups = mutableStateListOf<GroupDraft>()
}

/** Compose rule builder for smart playlists: fields/operators, nesting, limit and live updating. */
@Composable
internal fun SmartPlaylistContent(
    modifier: Modifier = Modifier,
    error: String? = null,
    saving: Boolean = false,
    initialName: String = "",
    initialConfig: SmartPlaylistConfig? = null,
    onSave: (String, SmartPlaylistConfig) -> Unit = { _, _ -> },
) {
    var name by remember(initialConfig) { mutableStateOf(initialName) }
    val root = remember(initialConfig) {
        initialConfig?.root?.toDraft() ?: GroupDraft().also { it.rules += RuleDraft() }
    }
    var live by remember(initialConfig) { mutableStateOf(initialConfig?.liveUpdating ?: true) }
    var limitEnabled by remember(initialConfig) { mutableStateOf(initialConfig?.limit?.enabled ?: false) }
    var limitCount by remember(initialConfig) {
        mutableStateOf((initialConfig?.limit?.count ?: 25).toString())
    }
    var limitBy by remember(initialConfig) { mutableStateOf(initialConfig?.limit?.by ?: "random") }
    var localError by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.smart_playlist_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("smart-playlist-name"),
        )
        LabeledCard(label = stringResource(R.string.smart_playlist_match)) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { root.setMatch("all") }) {
                    Text(stringResource(R.string.smart_playlist_match_all), color = if (root.match == "all") LocalSistrumColors.current.primary else LocalSistrumColors.current.textMuted)
                }
                TextButton(onClick = { root.setMatch("any") }) {
                    Text(stringResource(R.string.smart_playlist_match_any), color = if (root.match == "any") LocalSistrumColors.current.primary else LocalSistrumColors.current.textMuted)
                }
            }
        }
        GroupEditor(root, depth = 0)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = { root.rules += RuleDraft() },
                modifier = Modifier.testTag("smart-playlist-add-rule"),
            ) { Text(stringResource(R.string.smart_playlist_add_rule)) }
            if (root.groups.size < 2) {
                TextButton(
                    onClick = { root.groups += GroupDraft().also { it.rules += RuleDraft() } },
                    modifier = Modifier.testTag("smart-playlist-add-group"),
                ) { Text(stringResource(R.string.smart_playlist_add_group)) }
            }
        }
        LabeledCard(label = stringResource(R.string.smart_playlist_options)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.smart_playlist_live), modifier = Modifier.weight(1f))
                Switch(checked = live, onCheckedChange = { live = it }, modifier = Modifier.testTag("smart-playlist-live"))
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.smart_playlist_limit), modifier = Modifier.weight(1f))
                Switch(checked = limitEnabled, onCheckedChange = { limitEnabled = it })
            }
            if (limitEnabled) {
                OutlinedTextField(
                    value = limitCount,
                    onValueChange = { limitCount = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.smart_playlist_limit_count)) },
                    singleLine = true,
                    modifier = Modifier.padding(horizontal = 16.dp).testTag("smart-playlist-limit-count"),
                )
                SortPicker(selected = limitBy, onSelected = { limitBy = it })
            }
        }
        (localError ?: error)?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("smart-playlist-error"))
        }
        SistrumPillButton(
            label = stringResource(R.string.smart_playlist_save),
            enabled = !saving,
            onClick = {
                localError = null
                val config = SmartPlaylistConfig(
                    root = root.toGroup(),
                    limit = SmartPlaylistLimit(limitEnabled, limitCount.toIntOrNull() ?: 25, limitBy),
                    liveUpdating = live,
                )
                val validation = SmartRules.validate(config)
                if (name.isBlank()) {
                    localError = "Name cannot be empty"
                } else if (validation != null) {
                    localError = validation
                } else {
                    onSave(name.trim(), config)
                }
            },
            variant = SistrumPillButtonVariant.Primary,
            modifier = Modifier.testTag("smart-playlist-save"),
        )
    }
}

@Composable
private fun GroupEditor(group: GroupDraft, depth: Int) {
    group.rules.forEachIndexed { index, rule ->
        RuleEditor(
            rule = rule,
            onRemove = { if (group.rules.size > 1) group.rules.removeAt(index) },
        )
    }
    group.groups.forEachIndexed { index, sub ->
        LabeledCard(label = "Group ${index + 1} (${sub.match})") {
            Column(Modifier.padding(8.dp)) {
                Row {
                    TextButton(onClick = { sub.setMatch("all") }) { Text(if (sub.match == "all") "ALL ✓" else "ALL") }
                    TextButton(onClick = { sub.setMatch("any") }) { Text(if (sub.match == "any") "ANY ✓" else "ANY") }
                    if (group.groups.size > 1) {
                        TextButton(onClick = { group.groups.removeAt(index) }) { Text("×") }
                    }
                }
                GroupEditor(sub, depth + 1)
            }
        }
    }
}

@Composable
private fun RuleEditor(rule: RuleDraft, onRemove: () -> Unit) {
    var fieldOpen by remember { mutableStateOf(false) }
    var opOpen by remember { mutableStateOf(false) }
    val ops = SmartRules.fields[rule.field].orEmpty()
    Column(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { fieldOpen = true }) { Text(rule.field) }
            DropdownMenu(expanded = fieldOpen, onDismissRequest = { fieldOpen = false }) {
                SmartRules.fields.keys.forEach { field ->
                    DropdownMenuItem(text = { Text(field) }, onClick = {
                        rule.field = field
                        rule.op = SmartRules.fields.getValue(field).first()
                        fieldOpen = false
                    })
                }
            }
            TextButton(onClick = { opOpen = true }) { Text(rule.op) }
            DropdownMenu(expanded = opOpen, onDismissRequest = { opOpen = false }) {
                ops.forEach { op ->
                    DropdownMenuItem(text = { Text(op) }, onClick = { rule.op = op; opOpen = false })
                }
            }
            OutlinedTextField(
                value = rule.value,
                onValueChange = { rule.value = it },
                singleLine = true,
                modifier = Modifier.weight(1f).testTag("smart-rule-value"),
            )
            if (rule.op == "between") {
                OutlinedTextField(
                    value = rule.secondValue,
                    onValueChange = { rule.secondValue = it },
                    singleLine = true,
                    modifier = Modifier.weight(1f).testTag("smart-rule-value-2"),
                )
            }
            TextButton(onClick = onRemove) { Text("×") }
        }
    }
}

@Composable
private fun SortPicker(selected: String, onSelected: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.smart_playlist_limit_by))
        TextButton(onClick = { open = true }, modifier = Modifier.testTag("smart-playlist-limit-by")) { Text(selected) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SmartRules.limitOrders.forEach { order ->
                DropdownMenuItem(text = { Text(order) }, onClick = { onSelected(order); open = false })
            }
        }
    }
}

private fun GroupDraft.setMatch(value: String) { match = value }

/** Loads a persisted rule tree back into the editable drafts. */
private fun SmartRuleGroup.toDraft(): GroupDraft = GroupDraft(match).also { draft ->
    rules.forEach { rule ->
        val values = when (val value = rule.value) {
            is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.content }
            is JsonPrimitive -> listOfNotNull(value.content)
            else -> emptyList()
        }
        draft.rules += RuleDraft(
            field = rule.field,
            op = rule.op,
            value = values.firstOrNull().orEmpty(),
            secondValue = values.getOrNull(1).orEmpty(),
        )
    }
    groups.forEach { draft.groups += it.toDraft() }
}

private fun GroupDraft.toGroup(): SmartRuleGroup = SmartRuleGroup(
    match = match,
    rules = rules.map { draft -> draft.toRule() },
    groups = groups.map { it.toGroup() },
)

private fun RuleDraft.toRule(): SmartRule {
    val value = if (field in setOf("year", "bpm", "play_count", "duration", "bitrate", "energy", "danceability", "brightness", "rating")) {
        if (op == "between") JsonArray(listOf(JsonPrimitive(value.toDoubleOrNull() ?: 0.0), JsonPrimitive(secondValue.toDoubleOrNull() ?: 0.0)))
        else JsonPrimitive(value.toDoubleOrNull() ?: 0.0)
    } else if (field == "added_at") {
        JsonPrimitive(value.toDoubleOrNull() ?: 0.0)
    } else if (field == "is_favorite") {
        JsonPrimitive(value.equals("true", ignoreCase = true))
    } else {
        JsonPrimitive(value)
    }
    return SmartRule(field, op, value)
}
