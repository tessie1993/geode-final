package dev.geode.feature.diagnostics.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.geode.design.glass.rememberPressing
import dev.geode.design.glass.rememberToggling
import dev.geode.feature.diagnostics.api.DiagnosticsCommand

/** Spacing of the Diagnostics section; replaced by the `:design` tokens once that module lands (#61). */
private object SectionSpacing {
    val padding = 16.dp
    val gap = 8.dp
    val rowPadding = 12.dp

    /** The text beside a control takes the room the control leaves. */
    const val FILL = 1f
}

/**
 * The Diagnostics section of Settings: "Share diagnostics", what went wrong with the last share, the
 * version (tapping it [UNLOCK_TAPS][dev.geode.feature.diagnostics.api.UNLOCK_TAPS] times unlocks the
 * developer options) and, once unlocked, the developer overlay's switch. Stateless (ADR-0003): every
 * action goes to [onAction] as a Diagnostics command.
 */
@Composable
fun DiagnosticsScreen(
    model: DiagnosticsSectionModel,
    onAction: (DiagnosticsCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(SectionSpacing.padding),
        verticalArrangement = Arrangement.spacedBy(SectionSpacing.gap),
    ) {
        Text(stringResource(R.string.diagnostics_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.diagnostics_explanation),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = rememberPressing { onAction(DiagnosticsCommand.Share) },
            enabled = model.share != ShareUi.WORKING,
        ) {
            Text(stringResource(if (model.share == ShareUi.WORKING) R.string.diagnostics_preparing else R.string.diagnostics_share))
        }
        problemText(model.share)?.let { problem ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(problem),
                    modifier = Modifier.weight(SectionSpacing.FILL),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = rememberPressing { onAction(DiagnosticsCommand.DismissProblem) }) {
                    Text(stringResource(R.string.diagnostics_dismiss))
                }
            }
        }
        Text(
            stringResource(R.string.diagnostics_version, model.version),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { onAction(DiagnosticsCommand.TapVersion) }
                    .padding(vertical = SectionSpacing.rowPadding),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (model.developerUnlocked) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(SectionSpacing.FILL)) {
                    Text(stringResource(R.string.diagnostics_developer_overlay), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.diagnostics_developer_overlay_detail),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = model.overlayOn,
                    onCheckedChange = rememberToggling { onAction(DiagnosticsCommand.SetOverlay(visible = it)) },
                )
            }
        }
    }
}

private fun problemText(share: ShareUi): Int? =
    when (share) {
        ShareUi.READY, ShareUi.WORKING -> null
        ShareUi.FAILED_WRITE -> R.string.diagnostics_failed_write
        ShareUi.FAILED_NO_TARGET -> R.string.diagnostics_failed_no_target
        ShareUi.FAILED_TIMED_OUT -> R.string.diagnostics_failed_timed_out
    }

internal val previewSection =
    DiagnosticsSectionModel(version = "1.8.0", share = ShareUi.READY, developerUnlocked = false, overlayOn = false)

@Composable
internal fun SectionPreviewFrame(content: @Composable () -> Unit) {
    MaterialTheme {
        Surface(content = content)
    }
}

@Preview
@Composable
internal fun DiagnosticsReadyPreview() {
    SectionPreviewFrame { DiagnosticsScreen(previewSection, onAction = {}) }
}

@Preview
@Composable
internal fun DiagnosticsPreparingPreview() {
    SectionPreviewFrame { DiagnosticsScreen(previewSection.copy(share = ShareUi.WORKING), onAction = {}) }
}

@Preview
@Composable
internal fun DiagnosticsFailedPreview() {
    SectionPreviewFrame { DiagnosticsScreen(previewSection.copy(share = ShareUi.FAILED_NO_TARGET), onAction = {}) }
}

@Preview
@Composable
internal fun DiagnosticsDeveloperPreview() {
    SectionPreviewFrame {
        DiagnosticsScreen(previewSection.copy(developerUnlocked = true, overlayOn = true), onAction = {})
    }
}
