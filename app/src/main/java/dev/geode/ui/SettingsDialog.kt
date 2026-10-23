package dev.geode.ui

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.geode.R
import dev.geode.data.ExportDefaults
import dev.geode.data.exportCodecLabel
import dev.geode.data.exportQualityLabel
import dev.geode.engine.types.Millis
import dev.geode.export.ExportAspect
import dev.geode.export.ExportCodec
import dev.geode.export.ExportQuality
import dev.geode.export.ExportRange
import dev.geode.export.ExportRatio
import dev.geode.export.LoudnessTarget

@Composable
fun SettingsDialog(
    export: ExportUiState,
    hasMedia: Boolean,
    bpm: Float,
    trackDuration: Millis,
    onStart: (ExportAspect, Int, Boolean, ExportRange?, ExportCodec) -> Unit,
    onStartToDestination: (ExportAspect, Int, Boolean, ExportRange?, ExportCodec) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val trackDurationMs = trackDuration.value
    val context = LocalContext.current
    val settingsViewModel: SettingsViewModel = geodeViewModel()
    val defaults = remember { settingsViewModel.exportDefaults() }
    var quality by rememberSaveable { mutableStateOf(defaults.quality) }
    var ratio by rememberSaveable { mutableStateOf(defaults.ratio) }
    var fps by rememberSaveable { mutableStateOf(defaults.fps) }
    var codec by rememberSaveable { mutableStateOf(defaults.codec) }
    // LoudnessTarget is a sealed interface, not a Saveable type on its own, so the persisted
    // choice is carried as its id and resolved back through LoudnessTarget.byId.
    var loudnessTargetId by rememberSaveable { mutableStateOf(defaults.loudnessTargetId) }
    val loudnessTarget = LoudnessTarget.byId(loudnessTargetId)
    // The tempo arrives from the export analysis after the dialog opens, so the choice follows it.
    val tempoKnown =
        dev.geode.analysis.BarTrim
            .barDurationUs(bpm) != null
    var loopSafe by remember(tempoKnown) { mutableStateOf(defaults.loopSafe && tempoKnown) }
    var segment by rememberSaveable { mutableStateOf(false) }
    var rangeStart by remember { mutableFloatStateOf(0f) }
    var rangeEnd by remember { mutableFloatStateOf(1f) }
    val range =
        if (!segment) {
            null
        } else {
            ExportRange.of(
                startMs = (rangeStart * trackDurationMs).toLong(),
                endMs = (rangeEnd * trackDurationMs).toLong(),
                trackDurationMs = trackDurationMs,
            )
        }

    fun persistDefaults() = settingsViewModel.saveExportDefaults(ExportDefaults(quality, fps, ratio, loopSafe, codec, loudnessTargetId))
    val chooserTitle = stringResource(R.string.export_upload_share_to)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.export_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (val phase = export.phase) {
                    is ExportPhase.Running -> {
                        val run by dev.geode.export.ExportRun.state
                            .collectAsStateWithLifecycle()
                        Text(
                            listOfNotNull(
                                stringResource(R.string.export_rendering_offline),
                                run.secondsRemaining?.let {
                                    dev.geode.export.RenderEta
                                        .describe(it)
                                },
                            ).joinToString(" · "),
                        )
                        LinearProgressIndicator(
                            progress = { phase.progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            stringResource(R.string.export_leave_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is ExportPhase.Done -> {
                        Text(
                            stringResource(
                                if (export.customDestination) {
                                    R.string.export_saved_folder
                                } else {
                                    R.string.export_saved_library
                                },
                            ),
                        )
                        export.loudnessAdvice?.let { advice ->
                            Text(advice.headline, style = MaterialTheme.typography.labelMedium)
                            Text(
                                advice.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                val share =
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "video/mp4"
                                        putExtra(Intent.EXTRA_STREAM, phase.resultUri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                context.startActivity(Intent.createChooser(share, chooserTitle))
                            }) {
                                Text(stringResource(R.string.export_upload_drive))
                            }
                        }
                    }
                    is ExportPhase.Failed -> {
                        Text(stringResource(R.string.export_failed, phase.message), color = MaterialTheme.colorScheme.error)
                    }
                    ExportPhase.Idle, ExportPhase.Loading -> {
                        Text(stringResource(R.string.export_quality), style = MaterialTheme.typography.labelMedium)
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            ExportQuality.entries.forEach { q ->
                                QualityChip(exportQualityLabel(q), quality == q) {
                                    quality = q
                                    persistDefaults()
                                }
                            }
                        }
                        Text(stringResource(R.string.export_frame_rate), style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            QualityChip(stringResource(R.string.export_fps_30), fps == 30) {
                                fps = 30
                                persistDefaults()
                            }
                            QualityChip(stringResource(R.string.export_fps_60), fps == 60) {
                                fps = 60
                                persistDefaults()
                            }
                        }
                        Text(stringResource(R.string.export_codec), style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ExportCodec.entries.forEach { c ->
                                QualityChip(exportCodecLabel(c), codec == c) {
                                    codec = c
                                    persistDefaults()
                                }
                            }
                        }
                        Text(stringResource(R.string.export_loudness_target), style = MaterialTheme.typography.labelMedium)
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            LoudnessTarget.ALL.forEach { target ->
                                QualityChip(target.label, loudnessTarget.id == target.id) {
                                    loudnessTargetId = target.id
                                    persistDefaults()
                                }
                            }
                        }
                        Text(
                            stringResource(R.string.export_loudness_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(stringResource(R.string.export_aspect_ratio), style = MaterialTheme.typography.labelMedium)
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            ExportRatio.entries.forEach { r ->
                                QualityChip(r.label, ratio == r) {
                                    ratio = r
                                    persistDefaults()
                                }
                            }
                        }
                        if (trackDurationMs > 0) {
                            Text(stringResource(R.string.export_length), style = MaterialTheme.typography.labelMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                QualityChip(stringResource(R.string.export_whole_track), !segment) { segment = false }
                                QualityChip(stringResource(R.string.export_segment), segment) { segment = true }
                            }
                            if (segment) {
                                RangeSlider(
                                    value = rangeStart..rangeEnd,
                                    onValueChange = { r ->
                                        rangeStart = r.start
                                        rangeEnd = r.endInclusive
                                    },
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                                Text(
                                    if (range == null) {
                                        stringResource(
                                            R.string.export_segment_hint,
                                            (ExportRange.MIN_DURATION_MS / 1000).toInt(),
                                        )
                                    } else {
                                        stringResource(
                                            R.string.export_segment_summary,
                                            formatClock(Millis(range.startMs)),
                                            formatClock(Millis(range.endMs)),
                                            formatClock(Millis(range.durationMs)),
                                        )
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        val barUs =
                            dev.geode.analysis.BarTrim
                                .barDurationUs(bpm)
                        Text(stringResource(R.string.export_looping), style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            QualityChip(stringResource(R.string.export_full_length), !loopSafe) {
                                loopSafe = false
                                persistDefaults()
                            }
                            QualityChip(stringResource(R.string.export_loop_safe), loopSafe, enabled = barUs != null) {
                                loopSafe = true
                                persistDefaults()
                            }
                        }
                        Text(
                            if (barUs != null) {
                                stringResource(
                                    R.string.export_loop_safe_bar_hint,
                                    "%.0f".format(bpm),
                                    "%.1f".format(barUs / 1_000_000f),
                                )
                            } else {
                                stringResource(R.string.export_loop_safe_needs_tempo)
                            },
                            style = MaterialTheme.typography.labelSmall,
                        )
                        if (quality == ExportQuality.UHD4K) {
                            Text(
                                stringResource(R.string.export_4k_fallback),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Button(
                            onClick = { onStart(ExportAspect.of(quality, ratio), fps, loopSafe, range, codec) },
                            enabled = hasMedia,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.export_render_button, quality.shortSide, ratio.label, fps))
                        }
                        OutlinedButton(
                            onClick = { onStartToDestination(ExportAspect.of(quality, ratio), fps, loopSafe, range, codec) },
                            enabled = hasMedia,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.export_render_to_folder))
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (export.phase.isRunning) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.export_cancel)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            }
        },
    )
}

@Composable
private fun QualityChip(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
    )
}
