package dev.geode.feature.studio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.geode.design.glass.GlassCard

/**
 * Studio's own first-run step (owner decision on #21): where Studio saves its videos, picked with the
 * system folder picker. One folder for every Project; it is a Settings value (Settings > Default
 * paths), where it can be changed later. The switch-over (#17) puts this step into first run and
 * leaves the old first-run screen untouched. Stateless (ADR-0003).
 */
@Composable
fun StudioSetupScreen(
    model: StudioSetupModel,
    onAction: (StudioSetupAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().padding(StudioDimens.padding),
        verticalArrangement = Arrangement.spacedBy(StudioDimens.gap),
    ) {
        Text(stringResource(R.string.studio_setup_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.studio_setup_body))
        GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
            Column(Modifier.padding(StudioDimens.padding), verticalArrangement = Arrangement.spacedBy(StudioDimens.gap)) {
                Text(stringResource(R.string.studio_setup_folder), style = MaterialTheme.typography.titleSmall)
                Text(model.folder ?: stringResource(R.string.studio_setup_no_folder))
                OutlinedButton(onClick = { onAction(StudioSetupAction.PickFolder) }) {
                    Text(stringResource(if (model.folder == null) R.string.studio_choose_folder else R.string.studio_change_folder))
                }
            }
        }
        Text(
            stringResource(R.string.studio_setup_later),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = { onAction(StudioSetupAction.Continue) }, enabled = model.folder != null) {
            Text(stringResource(R.string.studio_setup_continue))
        }
    }
}
