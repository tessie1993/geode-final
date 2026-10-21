package dev.geode.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import dev.geode.R
import dev.geode.design.CrystalButton
import dev.geode.design.CrystalOverline
import dev.geode.design.CrystalPlayButton
import dev.geode.design.GlowTitle
import dev.geode.design.crystalPanel
import dev.geode.design.theme.StoneIcon
import dev.geode.design.theme.StoneIconArt
import dev.geode.design.theme.accentTextColor
import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import kotlinx.coroutines.delay

@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    onOpenSearch: () -> Unit,
    onExpand: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val viz by viewModel.vizState.collectAsStateWithLifecycle()
    val mic by viewModel.micState.collectAsStateWithLifecycle()
    val waveform by viewModel.waveform.collectAsStateWithLifecycle()
    val abLoop by viewModel.abLoop.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val favourites by viewModel.favourites.collectAsStateWithLifecycle()
    val tick by viewModel.historyTick.collectAsStateWithLifecycle()
    val sleepRemaining by viewModel.sleepTimerRemaining.collectAsStateWithLifecycle()
    val canShuffle = remember(tick) { viewModel.recentlyPlayed().isNotEmpty() }
    var showQueue by rememberSaveable { mutableStateOf(true) }
    val upNext = remember(queue) { queue.tracks.drop(queue.index + 1).take(3) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding =
            androidx.compose.foundation.layout
                .PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    CrystalOverline(stringResource(R.string.app_name))
                    GlowTitle(stringResource(R.string.nav_player))
                }
                IconButton(onClick = onOpenSearch) { StoneIconArt(StoneIcon.SEARCH, stringResource(R.string.action_search)) }
            }
        }

        item {
            PlayerHero(
                viewModel = viewModel,
                state = state,
                styleLabel = sceneDisplayLabel(viz.sceneId),
                micActive = mic.active,
                favourites = favourites,
                canResume = canShuffle,
                onExpand = onExpand,
                onOpenLibrary = onOpenLibrary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        item {
            TransportCard(
                viewModel = viewModel,
                state = state,
                waveform = waveform,
                abLoop = abLoop,
                queueSize = queue.tracks.size,
                queueOpen = showQueue,
                onToggleQueue = { showQueue = !showQueue },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        item {
            LiveSpectrum(
                viewModel,
                live = state.isPlaying || mic.active,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(44.dp),
            )
        }

        if (showQueue && upNext.isNotEmpty()) {
            item {
                QueuePreview(
                    upNext = upNext,
                    onExpand = onExpand,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        item {
            QuickActions(
                viewModel = viewModel,
                micActive = mic.active,
                sleepRunning = sleepRemaining != null,
                canShuffle = canShuffle,
            )
        }
    }
}

@Composable
private fun PlayerHero(
    viewModel: PlayerViewModel,
    state: PlayerUiState,
    styleLabel: String,
    micActive: Boolean,
    favourites: Set<TrackId>,
    canResume: Boolean,
    onExpand: () -> Unit,
    onOpenLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uri = remember(state.title, state.artist, state.queueIndex) { viewModel.currentTrackUri() }
    val hasSource = micActive || state.hasMedia
    val isFavourite = uri != null && uri in favourites
    Column(
        modifier
            .fillMaxWidth()
            .crystalPanel(
                0.42f,
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.colorScheme.primary,
                corner = 24.dp,
                glowStrength = if (state.isPlaying || micActive) 1.2f else 0.7f,
            ).clickable(enabled = hasSource, onClick = onExpand)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TrackArtwork(
            if (micActive) null else uri,
            Modifier.fillMaxWidth().aspectRatio(1f),
            corner = 18.dp,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                CrystalOverline(
                    when {
                        micActive -> stringResource(R.string.source_live_input)
                        state.isPlaying -> stringResource(R.string.state_now_playing)
                        state.hasMedia -> stringResource(R.string.state_paused)
                        else -> stringResource(R.string.state_nothing_playing)
                    },
                )
                Text(
                    when {
                        micActive -> stringResource(R.string.title_the_room)
                        state.hasMedia -> state.title ?: stringResource(R.string.title_untitled)
                        else -> stringResource(R.string.title_pick_something)
                    },
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        micActive -> stringResource(R.string.subtitle_microphone_hears)
                        state.hasMedia ->
                            state.artist?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.subtitle_unknown_artist)
                        else -> stringResource(R.string.subtitle_nothing_playing)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (state.hasMedia) {
                IconButton(onClick = { viewModel.toggleFavourite() }) {
                    StoneIconArt(
                        StoneIcon.FAVORITE,
                        stringResource(
                            if (isFavourite) R.string.action_favourite_remove else R.string.action_favourite_add,
                        ),
                        tint =
                            if (isFavourite) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SceneChip(styleLabel)
            Box(Modifier.weight(1f))
        }
        if (!hasSource) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CrystalButton(enabled = canResume, onClick = viewModel::resumeLastPlayed) {
                    Text(stringResource(R.string.action_resume_last_played))
                }
                CrystalButton(filled = false, onClick = onOpenLibrary) { Text(stringResource(R.string.action_open_library)) }
            }
        }
    }
}

@Composable
private fun SceneChip(label: String) {
    Box(
        Modifier
            .crystalPanel(
                0.25f,
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.colorScheme.primary,
                corner = 12.dp,
                glowStrength = 0.4f,
            ).padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = accentTextColor(),
            maxLines = 1,
        )
    }
}

@Composable
private fun TransportCard(
    viewModel: PlayerViewModel,
    state: PlayerUiState,
    waveform: FloatArray?,
    abLoop: AbLoop?,
    queueSize: Int,
    queueOpen: Boolean,
    onToggleQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .crystalPanel(
                0.35f,
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.colorScheme.primary,
                corner = 24.dp,
                glowStrength = 0.8f,
            ).padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(formatClock(state.position), style = MaterialTheme.typography.labelSmall)
            WaveformSeekBar(
                waveform = waveform,
                position = state.position,
                duration = state.duration,
                loopStart = abLoop?.start,
                loopEnd = abLoop?.end,
                onSeek = viewModel::seekTo,
                modifier = Modifier.weight(1f).height(40.dp),
            )
            Text(formatClock(state.duration), style = MaterialTheme.typography.labelSmall)
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            IconButton(onClick = viewModel::toggleShuffle) {
                StoneIconArt(
                    StoneIcon.SHUFFLE,
                    stringResource(R.string.action_shuffle),
                    tint =
                        if (state.shuffle) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
            IconButton(onClick = viewModel::previous, enabled = state.hasMedia) {
                StoneIconArt(StoneIcon.PREVIOUS, stringResource(R.string.action_previous))
            }
            CrystalPlayButton(
                icon = if (state.isPlaying) StoneIcon.PAUSE else StoneIcon.PLAY,
                contentDescription = stringResource(if (state.isPlaying) R.string.action_pause else R.string.action_play),
                onClick = viewModel::togglePlayPause,
                enabled = state.hasMedia,
            )
            IconButton(onClick = viewModel::next, enabled = state.hasMedia) {
                StoneIconArt(StoneIcon.NEXT, stringResource(R.string.action_next))
            }
            IconButton(onClick = viewModel::cycleRepeatMode) {
                StoneIconArt(
                    StoneIcon.REPEAT,
                    stringResource(R.string.action_repeat),
                    tint =
                        if (state.repeatMode != Player.REPEAT_MODE_OFF) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = viewModel::cycleAbLoop, enabled = state.hasMedia) {
                Text(
                    when {
                        abLoop == null -> stringResource(R.string.ab_loop_idle)
                        abLoop.end == null -> stringResource(R.string.ab_loop_set_b)
                        else -> stringResource(R.string.ab_loop_looping)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color =
                        if (abLoop != null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
            TextButton(onClick = onToggleQueue) {
                Text(
                    if (queueSize > 1) {
                        stringResource(R.string.queue_with_count, queueSize)
                    } else {
                        stringResource(R.string.queue)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color =
                        if (queueOpen) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
        }
    }
}

@Composable
private fun QueuePreview(
    upNext: List<QueueTrack>,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .crystalPanel(
                0.3f,
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.colorScheme.primary,
                corner = 20.dp,
                glowStrength = 0.5f,
            ).padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val untitled = stringResource(R.string.title_untitled)
        CrystalOverline(stringResource(R.string.queue_up_next))
        upNext.forEach { t ->
            Text(
                t.title.ifBlank { untitled },
                Modifier.fillMaxWidth().clickable(onClick = onExpand),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onExpand) {
                Text(stringResource(R.string.action_open_queue), style = MaterialTheme.typography.labelMedium, color = accentTextColor())
            }
        }
    }
}

@Composable
private fun LiveSpectrum(
    viewModel: PlayerViewModel,
    live: Boolean,
    modifier: Modifier = Modifier,
) {
    val bars by produceState(initialValue = FloatArray(BARS), live) {
        driveSpectrum(live, { viewModel.features.value.bands }) { value = it }
    }
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    androidx.compose.foundation.Canvas(modifier) {
        val gap = size.width / (BARS * 6f)
        val barWidth = (size.width - gap * (BARS - 1)) / BARS
        val brush =
            Brush.verticalGradient(
                listOf(secondary.copy(alpha = 0.95f), primary.copy(alpha = 0.75f)),
            )
        for (i in 0 until BARS) {
            val v = bars.getOrElse(i) { 0f }.coerceIn(0f, 1f)
            val h = (size.height * (0.06f + 0.94f * v)).coerceAtLeast(2f)
            drawRoundRect(
                brush = brush,
                topLeft =
                    androidx.compose.ui.geometry
                        .Offset(i * (barWidth + gap), size.height - h),
                size =
                    androidx.compose.ui.geometry
                        .Size(barWidth, h),
                cornerRadius =
                    androidx.compose.ui.geometry
                        .CornerRadius(barWidth / 2f),
            )
        }
    }
}

internal const val BARS = 24

internal const val SPECTRUM_TICK_MS = 50L

internal suspend fun driveSpectrum(
    live: Boolean,
    bands: () -> FloatArray,
    emit: (FloatArray) -> Unit,
) {
    if (!live) {
        emit(FloatArray(BARS))
        return
    }
    val smoothed = FloatArray(BARS)
    while (true) {
        val current = bands()
        for (i in 0 until BARS) {
            val target =
                if (current.isEmpty()) {
                    0f
                } else {
                    val from = i * current.size / BARS
                    val to = ((i + 1) * current.size / BARS).coerceAtLeast(from + 1)
                    var acc = 0f
                    for (b in from until minOf(to, current.size)) acc += current[b]
                    acc / (minOf(to, current.size) - from)
                }
            smoothed[i] =
                if (target > smoothed[i]) target else smoothed[i] + (target - smoothed[i]) * 0.35f
        }
        emit(smoothed.copyOf())
        delay(SPECTRUM_TICK_MS)
    }
}

@Composable
private fun QuickActions(
    viewModel: PlayerViewModel,
    micActive: Boolean,
    sleepRunning: Boolean,
    canShuffle: Boolean,
) {
    val micPermission =
        androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts
                .RequestPermission(),
        ) { granted -> if (granted) viewModel.setMicEnabled(true) }
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding =
            androidx.compose.foundation.layout
                .PaddingValues(horizontal = 16.dp),
    ) {
        item {
            QuickAction(
                StoneIcon.MICROPHONE,
                stringResource(if (micActive) R.string.quick_room_on else R.string.source_live_input),
                active = micActive,
            ) {
                if (micActive) {
                    viewModel.setMicEnabled(false)
                } else if (viewModel.hasMicPermission()) {
                    viewModel.setMicEnabled(true)
                } else {
                    micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                }
            }
        }
        item {
            QuickAction(
                Icons.Filled.Bedtime,
                stringResource(if (sleepRunning) R.string.quick_sleep_on else R.string.quick_sleep_30m),
                active = sleepRunning,
            ) {
                if (sleepRunning) viewModel.cancelSleepTimer() else viewModel.startSleepTimer(30)
            }
        }
        item {
            QuickAction(StoneIcon.SHUFFLE, stringResource(R.string.quick_shuffle_all), enabled = canShuffle) {
                viewModel.shuffleAllHistory()
            }
        }
    }
}

@Composable
private fun QuickAction(
    icon: StoneIcon,
    label: String,
    enabled: Boolean = true,
    active: Boolean = false,
    onClick: () -> Unit,
) = QuickActionShell(label, enabled, active, onClick) { StoneIconArt(icon, null, Modifier.size(22.dp), tint = it) }

@Composable
private fun QuickAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    active: Boolean = false,
    onClick: () -> Unit,
) = QuickActionShell(label, enabled, active, onClick) { Icon(icon, null, Modifier.size(22.dp), tint = it) }

@Composable
private fun QuickActionShell(
    label: String,
    enabled: Boolean,
    active: Boolean,
    onClick: () -> Unit,
    icon: @Composable (Color) -> Unit,
) {
    val tint =
        when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            active -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurface
        }
    Column(
        Modifier
            .width(84.dp)
            .crystalPanel(
                if (active) 0.5f else 0.28f,
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.colorScheme.primary,
                corner = 18.dp,
                glowStrength = if (active) 1.1f else 0.45f,
                prismatic = active,
            ).clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        icon(tint)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun formatClock(time: Millis): String {
    val total = (time.value / 1000).coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
