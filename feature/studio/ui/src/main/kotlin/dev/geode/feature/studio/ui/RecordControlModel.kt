package dev.geode.feature.studio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.geode.design.glass.rememberPressing
import dev.geode.engine.types.Millis
import dev.geode.feature.studio.api.Project
import dev.geode.feature.studio.api.ProjectId
import dev.geode.feature.studio.api.RecordingState
import dev.geode.feature.studio.api.StudioState
import java.util.Locale

/** What the Record control shows (#317): Record, or the red dot with the time recorded and Stop. */
@Immutable
data class RecordControlModel(
    val recording: Boolean,
    /** Starting or finishing: the control waits. */
    val busy: Boolean,
    /** The time recorded so far, as `m:ss` or `h:mm:ss`. */
    val elapsed: String,
    val lowStorage: Boolean,
)

/** The Record control for [state]; null while a stopped Recording waits for its Project. */
fun recordControlModel(state: RecordingState): RecordControlModel? =
    when (state) {
        RecordingState.Idle -> RecordControlModel(recording = false, busy = false, elapsed = ZERO_CLOCK, lowStorage = false)
        RecordingState.Starting -> RecordControlModel(recording = false, busy = true, elapsed = ZERO_CLOCK, lowStorage = false)
        is RecordingState.Recording ->
            RecordControlModel(recording = true, busy = state.stopping, elapsed = clockOf(state.elapsed), lowStorage = state.lowStorage)
        is RecordingState.ChooseProject -> null
    }

/** [length] as a clock: `m:ss` under an hour, `h:mm:ss` from one. */
fun clockOf(length: Millis): String {
    val seconds = (length.value / MILLIS_PER_SECOND).coerceAtLeast(0L)
    val hours = seconds / SECONDS_PER_HOUR
    val minutes = seconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
    val rest = seconds % SECONDS_PER_MINUTE
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, rest)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, rest)
    }
}

private const val ZERO_CLOCK = "0:00"
private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L

/** The red of the recording dot: a state colour, not a theme one. */
private val RecordingRed = Color(0xFFE53935)

/**
 * The Record control on the live Visual (#317): Record when idle; while recording a red dot, the time
 * recorded and Stop, with a warning while storage runs low. Stateless (ADR-0003).
 */
@Composable
fun RecordControl(
    model: RecordControlModel,
    onRecord: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(StudioDimens.gap)) {
        FilledTonalButton(onClick = rememberPressing(if (model.recording) onStop else onRecord), enabled = !model.busy) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(StudioDimens.gap)) {
                Box(Modifier.size(DOT).background(RecordingRed, CircleShape))
                Text(
                    when {
                        model.recording -> stringResource(R.string.studio_record_stop, model.elapsed)
                        model.busy -> stringResource(R.string.studio_record_starting)
                        else -> stringResource(R.string.studio_record)
                    },
                )
            }
        }
        if (model.lowStorage) {
            // On its own calm error fill: it sits on the live Visual, which could be any colour.
            Text(
                stringResource(R.string.studio_record_low_storage),
                modifier =
                    Modifier
                        .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.small)
                        .padding(horizontal = StudioDimens.gap, vertical = StudioDimens.gap / 2),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

private val DOT = 10.dp

/**
 * Asks which Project a stopped Recording goes into (#317). Dismissing it puts the Recording into the
 * default Project ([onSave] with null); with no Projects yet, one can be made here. Stateless apart
 * from the choice being made (ADR-0003).
 */
@Composable
fun RecordingProjectDialog(
    state: StudioState,
    onSave: (ProjectId?) -> Unit,
    onCreate: (String) -> Unit,
) {
    val waiting = state.recording as? RecordingState.ChooseProject ?: return
    var chosen by remember { mutableStateOf(state.defaultProject ?: state.projects.firstOrNull()?.id) }
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { onSave(null) },
        title = { Text(stringResource(R.string.studio_record_choose_project)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(StudioDimens.gap)) {
                if (waiting.stoppedAtLimit) Text(stringResource(R.string.studio_record_stopped_itself))
                ProjectChoices(state.projects, chosen ?: state.projects.firstOrNull()?.id) { chosen = it }
                if (state.projects.isEmpty()) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.studio_project_name)) },
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            if (state.projects.isEmpty()) {
                TextButton(
                    onClick = rememberPressing { onCreate(name) },
                    enabled = name.isNotBlank(),
                ) { Text(stringResource(R.string.studio_create)) }
            } else {
                val into = chosen ?: state.projects.first().id
                TextButton(onClick = rememberPressing { onSave(into) }) { Text(stringResource(R.string.studio_save)) }
            }
        },
    )
}

@Composable
private fun ProjectChoices(
    projects: List<Project>,
    chosen: ProjectId?,
    onChoose: (ProjectId) -> Unit,
) {
    LazyColumn(Modifier.heightIn(max = CHOICES_HEIGHT)) {
        items(projects, key = { it.id.value }) { project ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = project.id == chosen,
                        role = Role.RadioButton,
                        onClick = rememberPressing { onChoose(project.id) },
                    ).padding(vertical = StudioDimens.gap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = project.id == chosen, onClick = null)
                Text(project.name, Modifier.padding(start = StudioDimens.gap))
            }
        }
    }
}

private val CHOICES_HEIGHT = 280.dp
