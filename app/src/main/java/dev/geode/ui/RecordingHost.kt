package dev.geode.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.geode.feature.playback.api.currentQueue
import dev.geode.feature.studio.api.RecordingCommand
import dev.geode.feature.studio.api.StudioCommand
import dev.geode.feature.studio.ui.RecordControl
import dev.geode.feature.studio.ui.RecordingProjectDialog
import dev.geode.feature.studio.ui.recordControlModel
import dev.geode.ui.shell.ShellViewModel

/**
 * The Record control over the live Visual (#317): Studio's own control, with the playing song's title
 * for the Recording's name. Nothing while a stopped Recording waits for its Project.
 */
@Composable
fun RecordControlHost(
    shell: ShellViewModel,
    modifier: Modifier = Modifier,
) {
    val studio by shell.studio.state.collectAsStateWithLifecycle()
    val playing by shell.playback.state.collectAsStateWithLifecycle()
    val model = recordControlModel(studio.recording) ?: return
    val song =
        playing.player.currentQueue
            ?.current
            ?.title
            .orEmpty()
    RecordControl(
        model,
        onRecord = { shell.studio.send(RecordingCommand.Start(song)) },
        onStop = { shell.studio.send(RecordingCommand.Stop) },
        modifier = modifier,
    )
}

/**
 * Asks which Project a stopped Recording goes into, over whatever is on screen (#317): dismissed, it
 * goes into the default Project. With no Project yet, the one made here is where it is saved.
 */
@Composable
fun RecordingProjectHost(shell: ShellViewModel) {
    val studio by shell.studio.state.collectAsStateWithLifecycle()
    RecordingProjectDialog(
        studio,
        onSave = { shell.studio.send(RecordingCommand.Save(it)) },
        // Made the default, so the Recording, still waiting, can be saved into it.
        onCreate = { shell.studio.send(StudioCommand.CreateProject(it, makeDefault = true)) },
    )
}
