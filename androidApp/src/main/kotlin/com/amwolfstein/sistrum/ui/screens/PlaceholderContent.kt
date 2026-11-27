package com.amwolfstein.sistrum.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.amwolfstein.sistrum.AppDestination
import com.amwolfstein.sistrum.ui.navigation.placeholderRes
import com.amwolfstein.sistrum.ui.theme.LocalSistrumColors

@Composable
internal fun PlaceholderContent(destination: AppDestination, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(destination.placeholderRes),
        modifier = modifier,
        style = MaterialTheme.typography.bodyLarge,
        color = LocalSistrumColors.current.textMuted,
    )
}
