package com.amwolfstein.sistrum.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.amwolfstein.sistrum.R
import com.amwolfstein.sistrum.library.artwork.ArtworkSettings
import com.amwolfstein.sistrum.ui.components.ActionList
import com.amwolfstein.sistrum.ui.components.ActionListContainerStyle
import com.amwolfstein.sistrum.ui.components.ActionListItem
import com.amwolfstein.sistrum.ui.components.LabeledCard
import com.amwolfstein.sistrum.ui.components.SistrumPillButton
import com.amwolfstein.sistrum.ui.components.SistrumPillButtonVariant
import com.amwolfstein.sistrum.ui.theme.LocalSistrumColors

/** `Settings → Integrations → Online Artwork`; directly below Lyrics. */
@Composable
internal fun OnlineArtworkContent(
    settings: ArtworkSettings,
    cacheBytes: Long,
    assetCount: Int,
    onDeezerChanged: (Boolean) -> Unit,
    onClearCache: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSistrumColors.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LabeledCard(label = stringResource(R.string.online_artwork_sources)) {
            ActionList(
                items = listOf(
                    ActionListItem(
                        R.string.online_artwork_deezer,
                        trailingContent = {
                            Switch(
                                checked = settings.deezerEnabled,
                                onCheckedChange = onDeezerChanged,
                                modifier = Modifier.testTag("deezer-artwork-switch"),
                            )
                        },
                        onClick = { onDeezerChanged(!settings.deezerEnabled) },
                    ),
                ),
                containerStyle = ActionListContainerStyle.Plain,
            )
            Text(
                text = stringResource(R.string.online_artwork_deezer_description),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        }
        LabeledCard(label = stringResource(R.string.online_artwork_cache)) {
            Text(
                text = stringResource(
                    R.string.online_artwork_cache_summary,
                    assetCount,
                    formatBytes(cacheBytes),
                ),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).testTag("artwork-cache-summary"),
                style = MaterialTheme.typography.bodyMedium,
            )
            SistrumPillButton(
                label = stringResource(R.string.online_artwork_clear_cache),
                onClick = onClearCache,
                variant = SistrumPillButtonVariant.Secondary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            )
        }
        Text(
            text = stringResource(R.string.online_artwork_precedence),
            modifier = Modifier.padding(horizontal = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textMuted,
        )
    }
}

internal fun formatBytes(bytes: Long): String {
    val kb = 1024.0
    val mb = kb * 1024.0
    return when {
        bytes >= mb -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / mb)
        bytes >= kb -> String.format(java.util.Locale.ROOT, "%.0f KB", bytes / kb)
        else -> "$bytes B"
    }
}
