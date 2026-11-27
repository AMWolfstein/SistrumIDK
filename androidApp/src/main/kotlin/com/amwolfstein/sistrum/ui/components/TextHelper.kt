package com.amwolfstein.sistrum.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.amwolfstein.sistrum.ui.theme.LocalSistrumColors

/** Muted, wrapping annotation aligned with section labels below a settings card. */
@Composable
fun TextHelper(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(horizontal = 18.dp),
        style = MaterialTheme.typography.bodySmall,
        color = LocalSistrumColors.current.textMuted,
    )
}
