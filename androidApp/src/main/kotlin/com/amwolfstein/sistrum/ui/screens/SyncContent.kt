package com.amwolfstein.sistrum.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import com.amwolfstein.sistrum.R
import com.amwolfstein.sistrum.SyncUiState
import com.amwolfstein.sistrum.LocalNetworkAccess
import com.amwolfstein.sistrum.pairing.PairingFailure
import com.amwolfstein.sistrum.sync.AndroidSyncState
import com.amwolfstein.sistrum.ui.components.SistrumDialog
import com.amwolfstein.sistrum.ui.components.SistrumPillButton
import com.amwolfstein.sistrum.ui.components.SistrumPillButtonVariant
import com.amwolfstein.sistrum.ui.components.ActionList
import com.amwolfstein.sistrum.ui.components.ActionListContainerStyle
import com.amwolfstein.sistrum.ui.components.ActionListItem
import com.amwolfstein.sistrum.ui.components.Card
import com.amwolfstein.sistrum.ui.components.HeroCard
import com.amwolfstein.sistrum.ui.components.MaterialSymbols
import com.amwolfstein.sistrum.ui.theme.LocalSistrumColors

private const val MobileSyncHelpUrl = "https://sistrum.pages.dev/faq/mobile-sync"

@Composable
internal fun SyncContent(
    syncUiState: SyncUiState,
    onUnpair: () -> Unit,
    onOpenExternalUrl: (String) -> Unit = {},
    onScreenVisible: () -> Unit = {},
    onScreenHidden: () -> Unit = {},
    modifier: Modifier = Modifier,
    onRequestLocalNetworkAccess: () -> Unit = {},
) {
    DisposableEffect(Unit) {
        onScreenVisible()
        onDispose(onScreenHidden)
    }
    val colors = LocalSistrumColors.current
    val isSyncRunning = syncUiState.librarySync is AndroidSyncState.Running
    var showRevokeConfirmation by remember { mutableStateOf(false) }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (syncUiState.localNetworkAccess != LocalNetworkAccess.Granted) {
            HeroCard(
                symbol = MaterialSymbols.Info,
                title = stringResource(R.string.sync_local_network_title),
                description = stringResource(R.string.sync_local_network_description),
                bottomContent = {
                    SistrumPillButton(
                        label = stringResource(
                            if (syncUiState.localNetworkAccess == LocalNetworkAccess.Settings) R.string.sync_local_network_settings
                            else R.string.sync_local_network_allow,
                        ),
                        onClick = onRequestLocalNetworkAccess,
                        variant = SistrumPillButtonVariant.Primary,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    )
                },
            )
        }
        when {
            syncUiState.desktop != null -> {
                HeroCard(
                    symbol = MaterialSymbols.DesktopWindows,
                    title = syncUiState.desktop.displayName,
                    description = stringResource(
                        if (syncUiState.isMqttConnected) R.string.sync_paired_device_description
                        else R.string.sync_ready_to_connect_description,
                    ),
                    belowTitle = {
                        MqttConnectionBadge(
                            isConnected = syncUiState.isMqttConnected,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    },
                    bottomContent = {
                        SistrumPillButton(
                            label = stringResource(R.string.sync_revoke),
                            onClick = { showRevokeConfirmation = true },
                            variant = SistrumPillButtonVariant.Destructive,
                            enabled = !isSyncRunning,
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp),
                        )
                    },
                )
            }
            syncUiState.isPairing -> HeroCard(
                symbol = MaterialSymbols.Sync,
                title = stringResource(R.string.sync_waiting_title),
                description = stringResource(R.string.sync_waiting_description),
            )
            else -> HeroCard(
                symbol = MaterialSymbols.Power,
                title = stringResource(R.string.sync_empty_title),
                description = stringResource(R.string.sync_empty_description),
            )
        }
        syncUiState.failure?.let { failure ->
            Text(
                text = stringResource(failure.messageRes()),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textMuted,
            )
        }
        ActionList(
            items = listOf(
                ActionListItem(
                    labelRes = R.string.sync_help,
                    leadingSymbol = MaterialSymbols.Info,
                    onClick = { onOpenExternalUrl(MobileSyncHelpUrl) },
                ),
            ),
            containerStyle = ActionListContainerStyle.Card,
        )
        SyncProgressCard(
            syncState = syncUiState.librarySync,
            lastSyncedAtMillis = syncUiState.lastSyncedAtMillis,
        )
        if (syncUiState.libraryAnalysisEnabled) LibraryAnalysisCard(syncUiState.libraryAnalysis)
    }
    if (showRevokeConfirmation && !isSyncRunning) {
        SistrumDialog(
            title = stringResource(R.string.sync_revoke_confirm_title),
            description = stringResource(R.string.sync_revoke_confirm_description),
            dismissLabel = stringResource(R.string.cancel),
            onDismiss = { showRevokeConfirmation = false },
            confirmLabel = stringResource(R.string.sync_revoke),
            onConfirm = { showRevokeConfirmation = false; onUnpair() },
            confirmVariant = SistrumPillButtonVariant.Destructive,
        )
    }
}

@Composable
private fun LibraryAnalysisCard(progress: com.amwolfstein.sistrum.sync.LibraryAnalysisProgress) {
    if (progress.totalTracks == 0) return
    Card(contentPadding = PaddingValues(20.dp)) {
        Text(stringResource(R.string.sync_library_analysis), style = MaterialTheme.typography.labelMedium, color = LocalSistrumColors.current.textMuted)
        Text(
            stringResource(R.string.insight_percent_value, progress.analyzedTracks * 100 / progress.totalTracks),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = LocalSistrumColors.current.textMain,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun MqttConnectionBadge(isConnected: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalSistrumColors.current
    val label = stringResource(if (isConnected) R.string.sync_status_online else R.string.sync_status_offline)
    val statusColor = if (isConnected) colors.success else colors.primary
    Row(
        modifier = modifier
            .background(colors.buttonSecondary, CircleShape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Spacer(
            modifier = Modifier
                .size(8.dp)
                .background(statusColor, CircleShape),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = statusColor,
        )
    }
}

private fun PairingFailure.messageRes(): Int = when (this) {
    PairingFailure.AlreadyPaired -> R.string.sync_error_already_paired
    is PairingFailure.InvalidQr -> R.string.sync_error_invalid_qr
    is PairingFailure.Transport -> R.string.sync_error_transport
    PairingFailure.TimedOut -> R.string.sync_error_timeout
    PairingFailure.Rejected -> R.string.sync_error_rejected
    PairingFailure.Expired -> R.string.sync_error_expired
    PairingFailure.InvalidResponse -> R.string.sync_error_invalid_response
}

@Composable
private fun SyncProgressCard(
    syncState: AndroidSyncState,
    lastSyncedAtMillis: Long?,
    modifier: Modifier = Modifier,
) {
    if (syncState is AndroidSyncState.Idle) return

    val colors = LocalSistrumColors.current

    val statusText = when (syncState) {
        is AndroidSyncState.Running -> {
            if (syncState.total == 0) {
                stringResource(R.string.sync_progress_connecting)
            } else {
                stringResource(R.string.sync_progress_syncing)
            }
        }
        is AndroidSyncState.Completed -> stringResource(R.string.sync_notification_complete)
        is AndroidSyncState.Failed -> stringResource(R.string.sync_notification_failed)
        AndroidSyncState.Idle -> ""
    }

    val progressFloat: Float? = when (syncState) {
        is AndroidSyncState.Running -> {
            if (syncState.total == 0) null
            else (syncState.completed.toFloat() / syncState.total.toFloat()).coerceIn(0f, 1f)
        }
        is AndroidSyncState.Completed -> 1.0f
        is AndroidSyncState.Failed -> 0f
        AndroidSyncState.Idle -> null
    }

    val percentageText = progressFloat?.let { "${(it * 100).toInt()}%" }

    Card(
        modifier = modifier,
        contentPadding = PaddingValues(20.dp),
    ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = colors.textMain,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    percentageText?.let { pct ->
                        Text(
                            text = pct,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = colors.textMain,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }

                SistrumProgressBar(
                    progress = progressFloat,
                    color = colors.textMain,
                    trackColor = colors.buttonSecondary,
                )
                Text(
                    text = lastSyncedAtMillis?.let { timestamp ->
                        stringResource(
                            R.string.sync_last_synced,
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp)),
                        )
                    } ?: stringResource(R.string.sync_never_synced),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
    }
}

@Composable
private fun SistrumProgressBar(
    progress: Float?,
    color: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
) {
    if (progress == null) {
        LinearProgressIndicator(
            modifier = modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape),
            color = color,
            trackColor = trackColor,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
        )
    } else {
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape),
            color = color,
            trackColor = trackColor,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
}
