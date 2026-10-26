package dev.geode.feature.studio.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.geode.feature.studio.api.Project
import dev.geode.feature.studio.api.RenderAspect
import dev.geode.feature.studio.api.RenderCodec
import dev.geode.feature.studio.api.RenderFormat
import dev.geode.feature.studio.api.RenderJob
import dev.geode.feature.studio.api.RenderLength
import dev.geode.feature.studio.api.RenderLook
import dev.geode.feature.studio.api.RenderPhase
import dev.geode.feature.studio.api.RenderPreset
import dev.geode.feature.studio.api.RenderProblem
import dev.geode.feature.studio.api.RenderQuality
import dev.geode.feature.studio.api.RenderSize
import dev.geode.feature.studio.api.RenderStatus
import dev.geode.feature.studio.api.StudioCommand

/**
 * The render queue (#338, #325): each Track, or each playlist with its overall progress, with how far
 * every Render has got, the time left, and Cancel; and Clear finished. Stateless (ADR-0003).
 */
@Composable
fun RenderQueue(
    model: RenderQueueModel,
    onAction: (StudioAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (model.batches.isEmpty()) return
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(StudioDimens.padding), verticalArrangement = Arrangement.spacedBy(StudioDimens.gap)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.studio_render_queue),
                    Modifier.weight(StudioDimens.FILL),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (model.canClear) {
                    TextButton(onClick = { onAction(StudioAction.Send(StudioCommand.ClearFinishedRenders)) }) {
                        Text(stringResource(R.string.studio_render_clear))
                    }
                }
            }
            model.batches.forEach { batch -> RenderBatch(batch, onAction) }
        }
    }
}

@Composable
private fun RenderBatch(
    batch: RenderBatchRow,
    onAction: (StudioAction) -> Unit,
) {
    val playlist = batch.renders.size > 1
    if (playlist) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                pluralStringResource(R.plurals.studio_render_playlist, batch.renders.size, batch.renders.size),
                Modifier.weight(StudioDimens.FILL),
                style = MaterialTheme.typography.labelLarge,
            )
            if (batch.cancellable.size > 1) {
                TextButton(onClick = { onAction(StudioAction.Send(StudioCommand.CancelRenders(batch.cancellable))) }) {
                    Text(stringResource(R.string.studio_render_cancel_rest))
                }
            }
        }
        LinearProgressIndicator(progress = { batch.progress }, modifier = Modifier.fillMaxWidth())
    }
    batch.renders.forEach { job -> RenderRow(job, job.id in batch.cancellable, onAction) }
}

@Composable
private fun RenderRow(
    job: RenderJob,
    cancellable: Boolean,
    onAction: (StudioAction) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(StudioDimens.FILL)) {
                Text(job.title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    statusText(job.status),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (cancellable) {
                TextButton(onClick = { onAction(StudioAction.Send(StudioCommand.CancelRenders(setOf(job.id)))) }) {
                    Text(stringResource(R.string.studio_cancel))
                }
            }
        }
        val running = job.status as? RenderStatus.Running
        if (running != null) LinearProgressIndicator(progress = { running.progress }, modifier = Modifier.fillMaxWidth())
    }
}

/** "Rendering · 40% · about 3 min left", "Waiting", "Saved", "Failed: …". */
@Composable
private fun statusText(status: RenderStatus): String =
    when (status) {
        RenderStatus.Queued -> stringResource(R.string.studio_render_waiting)
        is RenderStatus.Running -> runningText(status)
        RenderStatus.Done -> stringResource(R.string.studio_render_done)
        RenderStatus.Cancelled -> stringResource(R.string.studio_render_cancelled)
        is RenderStatus.Failed -> stringResource(R.string.studio_render_failed, problemText(status.problem))
    }

@Composable
private fun runningText(status: RenderStatus.Running): String {
    if (status.cancelling) return stringResource(R.string.studio_render_cancelling)
    val phase =
        when (status.phase) {
            RenderPhase.PREPARING -> stringResource(R.string.studio_render_preparing)
            RenderPhase.ANALYSING -> stringResource(R.string.studio_render_analysing)
            RenderPhase.RENDERING -> stringResource(R.string.studio_render_rendering)
            RenderPhase.SAVING -> stringResource(R.string.studio_render_saving)
        }
    val percent = stringResource(R.string.studio_render_percent, phase, (status.progress * PERCENT).toInt())
    val left = status.secondsLeft ?: return percent
    val minutes = ((left + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE).toInt()
    return stringResource(
        R.string.studio_render_with_time,
        percent,
        pluralStringResource(R.plurals.studio_render_minutes_left, minutes, minutes),
    )
}

@Composable
private fun problemText(problem: RenderProblem): String =
    stringResource(
        when (problem) {
            RenderProblem.LOOK_UNAVAILABLE -> R.string.studio_render_problem_look
            RenderProblem.TRACK_UNREADABLE -> R.string.studio_render_problem_track
            RenderProblem.ENCODER_REFUSED -> R.string.studio_render_problem_encoder
            RenderProblem.NOT_SAVED -> R.string.studio_render_problem_saved
            RenderProblem.ENGINE_FAILED -> R.string.studio_render_problem_engine
        },
    )

/**
 * The Render setup (#22, #316, #325): a Track or a playlist, the look, a platform preset or the shape,
 * size, frame rate, quality and codec by hand, the length, and the Project; then Render queues it.
 * Stateless: the route keeps the [draft] (ADR-0003).
 */
@Composable
fun RenderSetupDialog(
    draft: RenderDraft,
    choices: RenderChoices,
    projects: List<Project>,
    onDraft: (RenderDraft) -> Unit,
    onRender: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(StudioDimens.padding), shape = MaterialTheme.shapes.large) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(StudioDimens.padding),
                verticalArrangement = Arrangement.spacedBy(StudioDimens.gap),
            ) {
                val title = if (draft.again == null) R.string.studio_render_title else R.string.studio_render_again_title
                Text(stringResource(title), style = MaterialTheme.typography.headlineSmall)
                if (draft.again == null) {
                    SourcePicker(draft, choices, onDraft)
                    LookPicker(draft, choices, onDraft)
                } else {
                    Note(stringResource(R.string.studio_render_again_note, draft.againName))
                }
                MilkdropPicker(draft, choices, onDraft)
                FormatPicker(draft, onDraft)
                ProjectPicker(draft, projects, onDraft)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.studio_cancel)) }
                    Button(onClick = onRender, enabled = draft.ready) { Text(stringResource(R.string.studio_render_start)) }
                }
            }
        }
    }
}

@Composable
private fun SourcePicker(
    draft: RenderDraft,
    choices: RenderChoices,
    onDraft: (RenderDraft) -> Unit,
) {
    var playlists by remember { mutableStateOf(draft.playlist != null) }
    var filter by remember { mutableStateOf("") }
    Heading(stringResource(R.string.studio_render_source))
    Row(horizontalArrangement = Arrangement.spacedBy(StudioDimens.gap)) {
        FilterChip(
            selected = !playlists,
            onClick = { playlists = false },
            label = { Text(stringResource(R.string.studio_render_source_track)) },
        )
        FilterChip(
            selected = playlists,
            onClick = { playlists = true },
            label = { Text(stringResource(R.string.studio_render_source_playlist)) },
        )
    }
    if (playlists) {
        if (choices.playlists.isEmpty()) Note(stringResource(R.string.studio_render_no_playlists))
        LazyColumn(Modifier.heightIn(max = StudioDimens.pickerHeight)) {
            // Playlist names need not be unique, so the list keeps no keys.
            items(choices.playlists) { playlist ->
                Choice(
                    text =
                        pluralStringResource(
                            R.plurals.studio_render_playlist_row,
                            playlist.tracks.size,
                            playlist.name,
                            playlist.tracks.size,
                        ),
                    selected = draft.playlist == playlist.name,
                    onClick = { onDraft(draft.playlist(playlist)) },
                )
            }
        }
        if (draft.playlist != null) Note(stringResource(R.string.studio_render_one_per_track))
    } else {
        OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.studio_render_find)) })
        val shown = choices.tracks.filter { it.title.contains(filter.trim(), ignoreCase = true) }
        if (shown.isEmpty()) Note(stringResource(R.string.studio_render_no_tracks))
        LazyColumn(Modifier.heightIn(max = StudioDimens.pickerHeight)) {
            items(shown, key = { it.id.value }) { track ->
                Choice(
                    text = "${track.title} · ${track.length.clock()}",
                    selected = draft.playlist == null && draft.spec.tracks == listOf(track),
                    onClick = { onDraft(draft.track(track)) },
                )
            }
        }
    }
}

@Composable
private fun LookPicker(
    draft: RenderDraft,
    choices: RenderChoices,
    onDraft: (RenderDraft) -> Unit,
) {
    val spec = draft.spec
    Heading(stringResource(R.string.studio_render_look))
    Choice(stringResource(R.string.studio_render_look_current), spec.look == RenderLook.Current) {
        onDraft(draft.copy(spec = spec.copy(look = RenderLook.Current)))
    }
    choices.presets.forEach { choice ->
        val look = RenderLook.Preset(choice.preset)
        Choice(choice.name, spec.look == look) { onDraft(draft.copy(spec = spec.copy(look = look))) }
    }
}

/** The MilkDrop preset the Render plays if its look is MilkDrop (#341); kept with the Render. */
@Composable
private fun MilkdropPicker(
    draft: RenderDraft,
    choices: RenderChoices,
    onDraft: (RenderDraft) -> Unit,
) {
    if (choices.milkdrop.isEmpty()) return
    Heading(stringResource(R.string.studio_render_milkdrop))
    Note(stringResource(R.string.studio_render_milkdrop_note))
    LazyColumn(Modifier.heightIn(max = StudioDimens.pickerHeight)) {
        item {
            Choice(stringResource(R.string.studio_render_milkdrop_none), draft.spec.milkdrop == null) { onDraft(draft.milkdrop(null)) }
        }
        items(choices.milkdrop, key = { it.file }) { choice ->
            Choice(choice.name, draft.spec.milkdrop == choice.file) { onDraft(draft.milkdrop(choice.file)) }
        }
    }
}

@Composable
private fun FormatPicker(
    draft: RenderDraft,
    onDraft: (RenderDraft) -> Unit,
) {
    val spec = draft.spec
    val format = spec.format
    val set = { changed: RenderFormat -> onDraft(draft.custom(spec.copy(format = changed))) }
    Heading(stringResource(R.string.studio_render_preset))
    Chips(RenderPreset.entries + listOf(null), { draft.preset == it }, { presetLabel(it) }) { preset ->
        onDraft(if (preset == null) draft.custom(spec) else draft.preset(preset))
    }
    Heading(stringResource(R.string.studio_render_shape))
    Chips(RenderAspect.entries, { format.aspect == it }, { stringResource(R.string.studio_render_ratio, it.wide, it.high) }) {
        set(format.copy(aspect = it))
    }
    Heading(stringResource(R.string.studio_render_size))
    Chips(RenderSize.entries, { format.size == it }, { sizeLabel(it) }) { set(format.copy(size = it)) }
    Heading(stringResource(R.string.studio_render_fps))
    Chips(RenderFormat.OFFERED_FPS, {
        format.fps == it
    }, { stringResource(R.string.studio_render_fps_value, it) }) { set(format.copy(fps = it)) }
    Heading(stringResource(R.string.studio_render_quality))
    Chips(RenderQuality.entries, { format.quality == it }, { qualityLabel(it) }) { set(format.copy(quality = it)) }
    Heading(stringResource(R.string.studio_render_codec))
    Chips(RenderCodec.entries, { format.codec == it }, { codecLabel(it) }) { set(format.copy(codec = it)) }
    Heading(stringResource(R.string.studio_render_length))
    val lengths = listOf(RenderLength.WholeTrack, FIRST_MINUTE, FIRST_SECTION, RenderLength.Loop, AN_HOUR)
    Chips(lengths, { lengthChosen(it, spec.length) }, { lengthLabel(it) }) {
        onDraft(draft.custom(spec.copy(length = it)))
    }
    val section = spec.length as? RenderLength.Section
    if (section != null && isOwnSection(section)) SectionFields(draft, section, onDraft)
    val minutes = spec.length as? RenderLength.Minutes
    if (minutes != null) MinutesField(draft, minutes, onDraft)
    if (spec.length == RenderLength.Loop) Note(stringResource(R.string.studio_render_loop_note))
}

/** Whether the chip for [option] shows [length]: "A section" and "N minutes" stand for any of theirs. */
private fun lengthChosen(
    option: RenderLength,
    length: RenderLength,
): Boolean =
    when (option) {
        FIRST_SECTION -> isOwnSection(length)
        is RenderLength.Minutes -> length is RenderLength.Minutes
        else -> option == length
    }

/** How many minutes the video runs; a number it cannot be keeps the last good one. */
@Composable
private fun MinutesField(
    draft: RenderDraft,
    minutes: RenderLength.Minutes,
    onDraft: (RenderDraft) -> Unit,
) {
    var text by remember { mutableStateOf(minutes.minutes.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            text = typed
            parseMinutes(typed)?.let { onDraft(draft.custom(draft.spec.copy(length = RenderLength.Minutes(it)))) }
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.studio_render_minutes_field)) },
        isError = parseMinutes(text) == null,
        singleLine = true,
    )
    Note(stringResource(R.string.studio_render_minutes_note))
}

/** The section's start and length as `m:ss`; a field that does not read as a time keeps the last good one. */
@Composable
private fun SectionFields(
    draft: RenderDraft,
    section: RenderLength.Section,
    onDraft: (RenderDraft) -> Unit,
) {
    var start by remember { mutableStateOf(section.start.clock()) }
    var length by remember { mutableStateOf(section.length.clock()) }
    Row(horizontalArrangement = Arrangement.spacedBy(StudioDimens.gap)) {
        OutlinedTextField(
            value = start,
            onValueChange = { text ->
                start = text
                parseClock(text)?.let { onDraft(draft.section(it, section.length)) }
            },
            modifier = Modifier.weight(StudioDimens.FILL),
            label = { Text(stringResource(R.string.studio_render_section_from)) },
            isError = parseClock(start) == null,
            singleLine = true,
        )
        OutlinedTextField(
            value = length,
            onValueChange = { text ->
                length = text
                parseClock(text)?.let { onDraft(draft.section(section.start, it)) }
            },
            modifier = Modifier.weight(StudioDimens.FILL),
            label = { Text(stringResource(R.string.studio_render_section_length)) },
            isError = parseClock(length) == null,
            singleLine = true,
        )
    }
    Note(stringResource(R.string.studio_render_section_note))
}

@Composable
private fun ProjectPicker(
    draft: RenderDraft,
    projects: List<Project>,
    onDraft: (RenderDraft) -> Unit,
) {
    Heading(stringResource(R.string.studio_render_project))
    if (projects.isEmpty()) Note(stringResource(R.string.studio_render_no_project))
    projects.forEach { project ->
        Choice(project.name, draft.spec.project == project.id) { onDraft(draft.copy(spec = draft.spec.copy(project = project.id))) }
    }
}

@Composable
private fun <T> Chips(
    options: List<T>,
    selected: (T) -> Boolean,
    label: @Composable (T) -> String,
    onPick: (T) -> Unit,
) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(StudioDimens.gap)) {
        options.forEach { option ->
            FilterChip(selected = selected(option), onClick = { onPick(option) }, label = { Text(label(option)) })
        }
    }
}

@Composable
private fun Choice(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = StudioDimens.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text, Modifier.padding(start = StudioDimens.gap))
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun presetLabel(preset: RenderPreset?): String =
    stringResource(
        when (preset) {
            RenderPreset.TIKTOK_AND_SHORTS -> R.string.studio_preset_tiktok
            RenderPreset.YOUTUBE -> R.string.studio_preset_youtube
            RenderPreset.FEED -> R.string.studio_preset_feed
            RenderPreset.SQUARE -> R.string.studio_preset_square
            RenderPreset.MASTER -> R.string.studio_preset_master
            null -> R.string.studio_preset_custom
        },
    )

@Composable
private fun sizeLabel(size: RenderSize): String =
    if (size ==
        RenderSize.UHD4K
    ) {
        stringResource(R.string.studio_render_size_4k)
    } else {
        stringResource(R.string.studio_render_size_p, size.shortSide)
    }

@Composable
private fun qualityLabel(quality: RenderQuality): String =
    stringResource(
        when (quality) {
            RenderQuality.STANDARD -> R.string.studio_render_quality_standard
            RenderQuality.HIGH -> R.string.studio_render_quality_high
            RenderQuality.BEST -> R.string.studio_render_quality_best
        },
    )

@Composable
private fun codecLabel(codec: RenderCodec): String =
    stringResource(if (codec == RenderCodec.HEVC) R.string.studio_render_codec_hevc else R.string.studio_render_codec_h264)

@Composable
private fun lengthLabel(length: RenderLength): String =
    stringResource(
        when (length) {
            RenderLength.WholeTrack -> R.string.studio_render_whole
            FIRST_MINUTE -> R.string.studio_render_first_minute
            RenderLength.Loop -> R.string.studio_render_loop
            is RenderLength.Minutes -> R.string.studio_render_minutes
            else -> R.string.studio_render_section
        },
    )

private const val PERCENT = 100
private const val SECONDS_PER_MINUTE = 60L
