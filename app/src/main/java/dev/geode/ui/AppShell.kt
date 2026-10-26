package dev.geode.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowWidthSizeClass
import dev.geode.R
import dev.geode.design.element.ElementNavBar
import dev.geode.design.element.ElementNavItem
import dev.geode.design.theme.StoneIcon
import dev.geode.design.theme.StoneIconArt
import dev.geode.engine.types.Millis
import dev.geode.feature.library.api.LibraryCommand
import dev.geode.feature.library.ui.LibraryRoute
import dev.geode.feature.playback.api.PlayerState
import dev.geode.feature.playback.api.currentQueue
import dev.geode.feature.playback.ui.ListeningRoute
import dev.geode.feature.playback.ui.MiniPlayerRoute
import dev.geode.feature.playback.ui.PlayerAction
import dev.geode.feature.playback.ui.PlayerRoute
import dev.geode.feature.playback.ui.QueueRoute
import dev.geode.feature.settings.api.Element
import dev.geode.feature.settings.api.FolderCommand
import dev.geode.feature.settings.api.FolderUri
import dev.geode.feature.settings.api.SettingsState
import dev.geode.feature.studio.ui.StudioRoute
import dev.geode.feature.visuals.ui.FullScreenVisualRoute
import dev.geode.render.VisualizerView
import dev.geode.ui.shell.AppUiCommand
import dev.geode.ui.shell.AppUiFact
import dev.geode.ui.shell.Banner
import dev.geode.ui.shell.GeodeTheme
import dev.geode.ui.shell.Place
import dev.geode.ui.shell.Sheet
import dev.geode.ui.shell.ShellViewModel
import dev.geode.ui.shell.icon
import dev.geode.ui.shell.label
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val CRASH_REPORT_MAX_BYTES = 64 * 1024

/**
 * The app's root: the App UI shell (#17). The App UI reducer (#182) decides where the person is and
 * what covers it, and this draws that: the Bar (Music, Visuals, Home, Studio, Settings, for everyone),
 * the Mini-player on every page but Home, the full-screen Visual, the Queue and Tools sheets, and the
 * failure banner. Pages whose feature has not switched over yet still show their old screen.
 */
@Composable
fun AppRoot() {
    val viewModel: PlayerViewModel = geodeViewModel()
    val settingsViewModel: SettingsViewModel = geodeViewModel()
    val shell: ShellViewModel = geodeViewModel()
    val appUi = shell.appUi
    val context = LocalContext.current
    val visualizerView = remember { VisualizerView(context) }
    // GLSurfaceView renders continuously and only ever stops when its host says so. Without this
    // the render thread keeps drawing whenever the app is visible but not resumed — behind a
    // permission dialog, in split-screen, partially obscured — burning battery on frames nobody
    // sees. GLSurfaceView's contract is that the owner forwards these two.
    val lifecycleOwner = LocalLifecycleOwner.current
    val settingsState by shell.settings.state.collectAsStateWithLifecycle()
    val settingsValues = (settingsState as? SettingsState.Ready)?.values
    // Settings' Visuals off (#17): while it is on, the Visual is neither drawn nor hosted anywhere.
    val visualsOff = settingsValues?.performance?.visualsOff ?: false
    val drawVisual by rememberUpdatedState(!visualsOff)
    LaunchedEffect(visualsOff) {
        if (visualsOff) {
            visualizerView.onPause()
        } else if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            visualizerView.onResume()
        }
    }
    DisposableEffect(lifecycleOwner, visualizerView) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> if (drawVisual) visualizerView.onResume()
                    // In picture-in-picture (#287) the activity is paused but still on screen, so the
                    // Visual goes on drawing; it stops when the window closes and the activity stops.
                    Lifecycle.Event.ON_PAUSE -> if (context.findActivity()?.isInPictureInPictureMode != true) visualizerView.onPause()
                    Lifecycle.Event.ON_STOP -> visualizerView.onPause()
                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            visualizerView.onPause()
        }
    }
    val gui by settingsViewModel.guiPrefs.collectAsStateWithLifecycle()
    val ui by appUi.state.collectAsStateWithLifecycle()
    val playing by shell.playback.state.collectAsStateWithLifecycle()
    val loaded = playing.player != PlayerState.Idle
    // Leaving the full-screen Visual's app shrinks it into a picture-in-picture window (#287).
    val inPip = rememberPictureInPicture(PictureInPicture.allowed(ui.fullScreen, visualsOff), shell.playback)
    LaunchedEffect(inPip) { if (inPip && drawVisual) visualizerView.onResume() }
    // Back closes what is on top, then goes Home, then leaves the app (#182). The sheets, the search
    // and the full-screen Visual handle their own Back above this one.
    BackHandler { appUi.send(AppUiCommand.Back) }
    LaunchedEffect(appUi) {
        appUi.events.collect { fact ->
            when (fact) {
                AppUiFact.LeaveApp -> context.findActivity()?.moveTaskToBack(true)
            }
        }
    }
    var crashText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        crashText =
            withContext(Dispatchers.IO) {
                val file = java.io.File(context.filesDir, "crash-latest.txt")
                if (file.exists()) {
                    file.inputStream().use { stream ->
                        val buf = ByteArray(CRASH_REPORT_MAX_BYTES)
                        var read = 0
                        while (read < buf.size) {
                            val n = stream.read(buf, read, buf.size - read)
                            if (n < 0) break
                            read += n
                        }
                        String(buf, 0, read, Charsets.UTF_8)
                    }
                } else {
                    null
                }
            }
    }
    if (!visualsOff) LiveVisualBindings(visualizerView, shell.customization, shell.snapshots, shell.settings)
    // Freeze reactions (#366): live only, so it is never saved and starts off with every launch.
    var reactionsHeld by remember { mutableStateOf(false) }
    LaunchedEffect(reactionsHeld) { visualizerView.visualizerRenderer.reactionsHeld = reactionsHeld }
    val element = settingsValues?.look?.element ?: Element.Default
    val keepScreenOn = settingsValues?.playback?.keepScreenOn ?: false
    // The one live Visual, hosted by whichever place shows it; nothing at all while Visuals are off.
    val visual: @Composable () -> Unit = {
        if (!visualsOff) VisualizerCanvasHost(visualizerView, Modifier.fillMaxSize())
    }
    GeodeTheme(element, gui) {
        val open: (Place) -> Unit = { appUi.send(AppUiCommand.Open(it)) }
        val miniPlayer: @Composable () -> Unit = {
            if (ui.playerBarShown(loaded)) {
                MiniPlayerRoute(shell.playback, elapsedNow = ::elapsedNow, onExpand = { appUi.send(AppUiCommand.EnterFullScreen) })
            }
        }
        // Set by Settings > Help to replay the intro tour; first-run setup shows it by itself (#187).
        var tutorialRunning by rememberSaveable { mutableStateOf(false) }
        val page: @Composable (twoPane: Boolean) -> Unit = { twoPane ->
            when (ui.place) {
                Place.HOME -> HomeScreen(shell, loaded, onResume = viewModel::resumeLastPlayed)
                Place.MUSIC ->
                    if (twoPane) {
                        Row(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f)) { MusicLibrary(shell) }
                            Box(
                                Modifier
                                    .width(1.dp)
                                    .fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.outlineVariant),
                            )
                            Box(Modifier.weight(1f)) { PlayerControls(shell) }
                        }
                    } else {
                        MusicLibrary(shell)
                    }
                Place.VISUALS ->
                    // Full-screen mode hosts the Visual itself, so it is never in two places at once.
                    VisualsPlace(shell, onEnterFullScreen = { appUi.send(AppUiCommand.EnterFullScreen) }) {
                        if (!ui.fullScreen) visual()
                    }
                Place.STUDIO -> StudioPlace(shell)
                Place.SETTINGS -> SettingsPlace(shell, onStartTutorial = { tutorialRunning = true })
            }
        }
        val content: @Composable (twoPane: Boolean) -> Unit = { twoPane ->
            Column {
                PlaybackNoticeBanner(viewModel)
                page(twoPane)
            }
        }
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            val widthClass = currentWindowAdaptiveInfo().windowSizeClass.windowWidthSizeClass
            if (widthClass == WindowWidthSizeClass.COMPACT) {
                AppShellCompact(ui.place, open, gui, miniPlayer, content = { content(false) })
            } else {
                AppShellExpanded(ui.place, open, miniPlayer, content = { content(true) })
            }
            val clipLabel = stringResource(R.string.crash_clip_label)
            crashText?.let { text ->
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = {},
                    title = { Text(stringResource(R.string.crash_dialog_title)) },
                    text = {
                        Text(
                            stringResource(R.string.crash_dialog_body, text.take(600)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    confirmButton = {
                        Button(onClick = {
                            val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                            cm.setPrimaryClip(android.content.ClipData.newPlainText(clipLabel, text))
                        }) { Text(stringResource(R.string.action_copy)) }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            java.io.File(context.filesDir, "crash-latest.txt").delete()
                            crashText = null
                        }) { Text(stringResource(R.string.action_dismiss)) }
                    },
                )
            }
            if (ui.fullScreen) {
                FullScreenVisual(shell, keepScreenOn, visual, inPip, reactionsHeld, onHeldChange = { reactionsHeld = it })
            }
            // The picture-in-picture window shows the Visual alone; what covered it comes back with the app.
            if (!inPip) {
                PlayerSheet(shell, ui.sheet)
                ui.banner?.let { banner ->
                    FailureBanner(banner, onDismiss = { appUi.send(AppUiCommand.DismissBanner) }, Modifier.statusBarsPadding())
                }
                DeveloperOverlayHost(shell.diagnostics, Modifier.statusBarsPadding())
            }
            // First-run setup, and the Save folder step again whenever the folder is lost (#187).
            FirstRunSetup(
                shell.settings,
                replayTour = tutorialRunning,
                onReplayOver = { tutorialRunning = false },
                onNavigate = open,
            )
        }
    }
}

/** The monotonic clock Playback's marks use (ADR-0004). */
private fun elapsedNow(): Millis = Millis(SystemClock.elapsedRealtime())

internal tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

/** Music: the Library feature's own place, with its own search (#17). */
@Composable
private fun MusicLibrary(shell: ShellViewModel) {
    LibraryRoute(shell.library, readPermission = audioPermission)
}

/**
 * Studio: the Studio feature's own library place (#17), with the old export entry re-anchored as a
 * sheet until Studio's own export lands. The save folder is a Settings value, picked here as in the
 * first-run step.
 */
@Composable
private fun StudioPlace(shell: ShellViewModel) {
    Box(Modifier.fillMaxSize()) {
        StudioRoute(
            shell.studio,
            onSaveFolderPicked = { shell.settings.send(FolderCommand.SetVideoFolder(FolderUri(it))) },
        )
        TextButton(
            onClick = { shell.appUi.send(AppUiCommand.OpenSheet(Sheet.EXPORT)) },
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
        ) { Text(stringResource(R.string.export_title)) }
    }
}

/**
 * Playback's full Player controls, as the owner placed them (#17): the Queue and Tools buttons open
 * their sheets, and the heart is Library's favourite for the playing song.
 */
@Composable
private fun PlayerControls(
    shell: ShellViewModel,
    modifier: Modifier = Modifier,
) {
    val playing by shell.playback.state.collectAsStateWithLifecycle()
    val library by shell.library.state.collectAsStateWithLifecycle()
    val song =
        playing.player.currentQueue
            ?.current
            ?.id
    PlayerRoute(
        shell.playback,
        elapsedNow = ::elapsedNow,
        modifier = modifier,
        favourite = song?.let { it in library.favourites },
        onOther = { action ->
            when (action) {
                PlayerAction.OpenQueue -> shell.appUi.send(AppUiCommand.OpenSheet(Sheet.QUEUE))
                PlayerAction.OpenTools -> shell.appUi.send(AppUiCommand.OpenSheet(Sheet.LISTENING))
                PlayerAction.ToggleFavourite -> song?.let { shell.library.send(LibraryCommand.ToggleFavourite(it)) }
                else -> Unit
            }
        },
    )
}

/**
 * The sheet the App UI has open, if any: the Queue, Tools, or Customize over full-screen mode (owner
 * decision on #17: Customize is both a Visuals sub-tab and this sheet).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSheet(
    shell: ShellViewModel,
    sheet: Sheet?,
) {
    if (sheet == null) return
    val close = { shell.appUi.send(AppUiCommand.CloseSheet) }
    ModalBottomSheet(onDismissRequest = close) {
        when (sheet) {
            Sheet.QUEUE -> QueueRoute(shell.playback)
            Sheet.LISTENING -> ListeningRoute(shell.playback, elapsedNow = ::elapsedNow)
            Sheet.CUSTOMIZE -> CustomizePanel(shell, onHide = close, onBrowsePresets = close)
            Sheet.CAPTURE -> CaptureRoute(shell.capture)
            Sheet.EXPORT -> ExportRoute()
        }
    }
}

/**
 * Full-screen visual mode (#17): the one live Visual under Visuals' own overlay, and the way into the
 * Customize sheet. Leaving it is the App UI's Back. In picture-in-picture ([inPip], #287) the window
 * shows the Visual alone, with no App UI over it. The freeze switch ([reactionsHeld], #366) holds the
 * Sound signals while the picture keeps moving.
 */
@Composable
private fun FullScreenVisual(
    shell: ShellViewModel,
    keepScreenOn: Boolean,
    visual: @Composable () -> Unit,
    inPip: Boolean,
    reactionsHeld: Boolean,
    onHeldChange: (Boolean) -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        visual()
        if (!inPip) {
            FullScreenVisualRoute(shell.visuals, keepScreenOn = keepScreenOn, onExit = { shell.appUi.send(AppUiCommand.ExitFullScreen) })
            TextButton(
                onClick = { shell.appUi.send(AppUiCommand.OpenSheet(Sheet.CUSTOMIZE)) },
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
            ) { Text(stringResource(R.string.visuals_open_customize)) }
            TextButton(
                onClick = { shell.appUi.send(AppUiCommand.OpenSheet(Sheet.CAPTURE)) },
                modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(16.dp),
            ) { Text(stringResource(R.string.visuals_open_capture)) }
            TextButton(
                onClick = { onHeldChange(!reactionsHeld) },
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
            ) {
                Text(stringResource(if (reactionsHeld) R.string.visuals_reactions_unfreeze else R.string.visuals_reactions_freeze))
            }
        }
    }
}

/** The App UI's banner (#182): one failure at a time, over everything, until it times out or is dismissed. */
@Composable
private fun FailureBanner(
    banner: Banner,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dismissDescription = stringResource(R.string.notice_dismiss_description)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.errorContainer)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = banner.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.notice_dismiss),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier =
                Modifier
                    .clickable(onClick = onDismiss)
                    .semantics { contentDescription = dismissDescription }
                    .padding(8.dp),
        )
    }
}

@Composable
private fun AppShellCompact(
    place: Place,
    onOpen: (Place) -> Unit,
    gui: GuiPrefs,
    miniPlayer: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            if (gui.playerPosition == PlayerPosition.TOP) {
                Box(Modifier.statusBarsPadding()) { miniPlayer() }
            }
        },
        bottomBar = {
            Column {
                if (gui.playerPosition == PlayerPosition.BOTTOM) miniPlayer()
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                PlaceBar(place, onOpen, gui.barOpacity)
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad)) { content() }
    }
}

@Composable
private fun AppShellExpanded(
    place: Place,
    onOpen: (Place) -> Unit,
    miniPlayer: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        PlaceRail(place, onOpen)
        Box(
            Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Column(Modifier.weight(1f)) {
            miniPlayer()
            Box(Modifier.weight(1f)) { content() }
        }
    }
}

/** The Bar: every place, in its one order, for everyone (PR #186). */
@Composable
private fun PlaceBar(
    selected: Place,
    onOpen: (Place) -> Unit,
    opacity: Float,
) {
    ElementNavBar(
        items = Place.entries.map { ElementNavItem(stringResource(it.label), it.icon) },
        selected = Place.entries.indexOf(selected),
        onSelect = { onOpen(Place.entries[it]) },
        modifier = Modifier.alpha(opacity),
    )
}

/** The wide-screen twin of [PlaceBar]: the same places, order and selection. */
@Composable
private fun PlaceRail(
    selected: Place,
    onOpen: (Place) -> Unit,
) {
    NavigationRail(containerColor = Color.Transparent) {
        Place.entries.forEach { place ->
            val label = stringResource(place.label)
            NavigationRailItem(
                selected = selected == place,
                onClick = { onOpen(place) },
                icon = { StoneIconArt(place.icon, label) },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
            )
        }
    }
}

/**
 * Home: a reserved slot for the Element's Hero scene, with the now-playing card on it (glossary). With
 * nothing loaded, the card offers the song played last.
 */
@Composable
private fun HomeScreen(
    shell: ShellViewModel,
    loaded: Boolean,
    onResume: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.labelMedium)
        Text(stringResource(R.string.nav_home), style = MaterialTheme.typography.headlineMedium)
        // ponytail: an empty slot until the Hero scene lands (#110).
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        )
        if (loaded) {
            MiniPlayerRoute(
                shell.playback,
                elapsedNow = ::elapsedNow,
                onExpand = { shell.appUi.send(AppUiCommand.EnterFullScreen) },
                modifier = Modifier.clip(RoundedCornerShape(16.dp)),
            )
        } else {
            Button(onClick = onResume) {
                StoneIconArt(StoneIcon.PLAY, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_resume_last_played))
            }
        }
    }
}

@Composable
private fun PlaybackNoticeBanner(viewModel: PlayerViewModel) {
    val notice by viewModel.playbackNotice.collectAsStateWithLifecycle()
    val message = notice ?: return
    val dismissDescription = stringResource(R.string.notice_dismiss_description)

    LaunchedEffect(message) {
        kotlinx.coroutines.delay(NOTICE_VISIBLE_MS)
        viewModel.clearPlaybackNotice()
    }

    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .clickable { viewModel.clearPlaybackNotice() }
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.notice_dismiss),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier =
                    Modifier
                        .clickable { viewModel.clearPlaybackNotice() }
                        .semantics { contentDescription = dismissDescription }
                        .padding(8.dp),
            )
        }
    }
}

private const val NOTICE_VISIBLE_MS = 8_000L
