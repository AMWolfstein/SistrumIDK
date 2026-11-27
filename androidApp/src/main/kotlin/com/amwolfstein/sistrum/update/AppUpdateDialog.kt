package com.amwolfstein.sistrum.update

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.amwolfstein.sistrum.R
import com.amwolfstein.sistrum.ui.components.SistrumDialog

@Composable
internal fun AppUpdateDialog(update: AppUpdate, onDismiss: () -> Unit, onUpdate: (String) -> Unit) {
    SistrumDialog(
        title = stringResource(R.string.app_update_title),
        description = stringResource(R.string.app_update_version, update.version),
        dismissLabel = stringResource(R.string.app_update_later),
        onDismiss = onDismiss,
        confirmLabel = stringResource(R.string.app_update_action),
        onConfirm = {
            onDismiss()
            onUpdate(update.releaseUrl)
        },
        content = {
            ReleaseNotesMarkdown(
                markdown = update.notes.ifBlank { stringResource(R.string.app_update_no_notes) },
                version = update.version,
                onOpenUrl = onUpdate,
            )
        },
    )
}
