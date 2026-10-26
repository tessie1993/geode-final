package dev.geode.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowWidthSizeClass
import dev.geode.R
import dev.geode.design.glass.ArtworkBackdrop
import dev.geode.design.glass.CalmBackdrop
import dev.geode.design.glass.GlassBackdrop
import dev.geode.design.glass.GlassButton
import dev.geode.design.glass.GlassCard
import dev.geode.design.glass.GlassDialog
import dev.geode.design.glass.GlassEdge
import dev.geode.design.glass.GlassKind
import dev.geode.design.glass.GlassMessage
import dev.geode.design.glass.GlassNavItem
import dev.geode.design.glass.GlassNavigationBar
import dev.geode.design.glass.GlassNavigationRail
import dev.geode.design.glass.GlassNotice
import dev.geode.design.glass.GlassSectionHeader
import dev.geode.design.glass.GlassSheet
import dev.geode.design.glass.GlassSource
import dev.geode.design.glass.GlassSpacing
import dev.geode.design.glass.GlassTone
import dev.geode.design.glass.GlassTopBar
import dev.geode.design.glass.glassSource
import dev.geode.design.glass.glassSurface
import dev.geode.design.glass.rememberGlassSource
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
import dev.geode.feature.settings.api.BehaviorSettings
import dev.geode.feature.settings.api.FolderCommand
import dev.geode.feature.settings.api.FolderUri
import dev.geode.feature.settings.api.SettingsState
import dev.geode.feature.settings.api.ThemeMode
import dev.geode.feature.settings.api.TiltAmount
import dev.geode.feature.settings.api.VisualSurface
import dev.geode.feature.studio.ui.StudioRoute
import dev.geode.feature.visuals.ui.FullScreenVisualRoute
import dev.geode.render.VisualizerView
import dev.geode.studio.renderChoicesOf
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
    val surface = if (inPip) VisualSurface.PICTURE_IN_PICTURE else VisualSurface.VISUAL
    if (!visualsOff) LiveVisualBindings(visualizerView, shell.customization, shell.snapshots, shell.settings, surface)
    // Freeze reactions (#366): live only, so it is never saved and starts off with every launch.
    var reactionsHeld by remember { mutableStateOf(false) }
    LaunchedEffect(reactionsHeld) { visualizerView.visualizerRenderer.reactionsHeld = reactionsHeld }
    val look = settingsValues?.look
    val keepScreenOn = settingsValues?.playback?.keepScreenOn ?: false
    // The one live Visual, hosted by whichever place shows it; nothing at all while Visuals are off.
    // Full-screen mode hands the host the double tap that shows and hides its controls; the Visuals
    // tab, where the tab UI is the thing being touched, passes none.
    // Settings' Tilt as the weight the engine fades to: Subtle is half, Full all. Reduce motion stills
    // it whatever the setting says (#315); the host also stops it under Battery Saver.
    val tilt = settingsValues?.behavior?.let(::tiltWeight) ?: 0f
    val visual: @Composable (onDoubleTap: (() -> Unit)?) -> Unit = { onDoubleTap ->
        if (!visualsOff) VisualizerCanvasHost(visualizerView, Modifier.fillMaxSize(), onDoubleTap, tilt)
    }
    GeodeTheme(look?.themeMode ?: ThemeMode.SYSTEM, look?.reduceTransparency ?: false, visualsOff, gui) {
        val open: (Place) -> Unit = { appUi.send(AppUiCommand.Open(it)) }
        // What the bars and sheets frost: the page and its backdrop (#324).
        val source = rememberGlassSource()
        val artwork = rememberNowPlayingArtwork(shell)
        val miniPlayer: (@Composable () -> Unit)? =
            if (ui.playerBarShown(loaded)) {
                { MiniPlayerRoute(shell.playback, elapsedNow = ::elapsedNow, onExpand = { appUi.send(AppUiCommand.EnterFullScreen) }) }
            } else {
                null
            }
        // Set by Settings > Help to replay the intro tour; first-run setup shows it by itself (#187).
        var tutorialRunning by rememberSaveable { mutableStateOf(false) }
        val page: @Composable (twoPane: Boolean) -> Unit = { twoPane ->
            when (ui.place) {
                Place.HOME -> HomeScreen(shell, loaded, onResume = viewModel::resumeLastPlayed, onOpen = open)
                Place.MUSIC ->
                    if (twoPane) {
                        Row(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f)) { MusicLibrary(shell) }
                            VerticalDivider()
                            Box(Modifier.weight(1f)) { PlayerControls(shell) }
                        }
                    } else {
                        MusicLibrary(shell)
                    }
                Place.VISUALS ->
                    // Full-screen mode hosts the Visual itself, so it is never in two places at once.
                    VisualsPlace(shell, onEnterFullScreen = { appUi.send(AppUiCommand.EnterFullScreen) }) {
                        if (!ui.fullScreen) visual(null)
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
        Box(Modifier.fillMaxSize()) {
            val widthClass = currentWindowAdaptiveInfo().windowSizeClass.windowWidthSizeClass
            val backdrop: @Composable () -> Unit = { PlaceBackdrop(ui.place, artwork) }
            if (widthClass == WindowWidthSizeClass.COMPACT) {
                AppShellCompact(ui.place, open, gui, miniPlayer, source, backdrop, content = { content(false) })
            } else {
                AppShellExpanded(ui.place, open, miniPlayer, backdrop, content = { content(true) })
            }
            val clipLabel = stringResource(R.string.crash_clip_label)
            crashText?.let { text ->
                GlassDialog(
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
                // With Visuals off, the mini-player opens the plain player instead (glossary).
                if (visualsOff) {
                    PlainPlayer(shell, artwork)
                } else {
                    FullScreenVisual(shell, keepScreenOn, visual, inPip, reactionsHeld, onHeldChange = { reactionsHeld = it })
                }
            }
            // The picture-in-picture window shows the Visual alone; what covered it comes back with the app.
            if (!inPip) {
                val sheetOverVisual = ui.place == Place.VISUALS || (ui.fullScreen && !visualsOff)
                PlayerSheet(shell, ui.sheet, if (sheetOverVisual) GlassBackdrop.Visual else GlassBackdrop.Live(source))
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

/** How strongly tilt moves the Visual for these Settings, 0 when it must not move it at all. */
internal fun tiltWeight(behavior: BehaviorSettings): Float =
    when {
        behavior.reducedMotion -> 0f
        else ->
            when (behavior.tilt) {
                TiltAmount.OFF -> 0f
                TiltAmount.SUBTLE -> TILT_SUBTLE
                TiltAmount.FULL -> 1f
            }
    }

/** Subtle tilt: half of Full. */
private const val TILT_SUBTLE = 0.5f

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
 * Studio: the Studio feature's own library place (#17), where Renders are set up and queued (#316)
 * from the Library's Tracks and playlists and the saved presets. The old editors stay reachable as a
 * sheet until they move into Studio (#334). The save folder is a Settings value, picked here as in the
 * first-run step.
 */
@Composable
private fun StudioPlace(shell: ShellViewModel) {
    val library by shell.library.state.collectAsStateWithLifecycle()
    val visuals by shell.visuals.state.collectAsStateWithLifecycle()
    val choices = remember(library.songs, library.playlists, visuals.presets) { renderChoicesOf(library, visuals.presets) }
    Box(Modifier.fillMaxSize()) {
        StudioRoute(
            shell.studio,
            onSaveFolderPicked = { shell.settings.send(FolderCommand.SetVideoFolder(FolderUri(it))) },
            renderChoices = choices,
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
@Composable
private fun PlayerSheet(
    shell: ShellViewModel,
    sheet: Sheet?,
    backdrop: GlassBackdrop,
) {
    if (sheet == null) return
    val close = { shell.appUi.send(AppUiCommand.CloseSheet) }
    GlassSheet(onDismissRequest = close, backdrop = backdrop) {
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
    visual: @Composable (onDoubleTap: (() -> Unit)?) -> Unit,
    inPip: Boolean,
    reactionsHeld: Boolean,
    onHeldChange: (Boolean) -> Unit,
) {
    // Whether the overlay's controls show is App-UI-only state, and it outlives a rotation. The double
    // tap that toggles them is the Visual host's own, so every other touch draws in the Visual.
    var controlsShown by rememberSaveable { mutableStateOf(true) }
    val toggleControls = { controlsShown = !controlsShown }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        visual(if (inPip) null else toggleControls)
        if (!inPip) {
            FullScreenVisualRoute(
                shell.visuals,
                controlsShown = controlsShown,
                keepScreenOn = keepScreenOn,
                onToggleControls = toggleControls,
                onExit = { shell.appUi.send(AppUiCommand.ExitFullScreen) },
            )
            // Tonal, so their labels sit on an opaque fill and read over any Visual.
            GlassButton(
                onClick = { shell.appUi.send(AppUiCommand.OpenSheet(Sheet.CUSTOMIZE)) },
                filled = false,
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(GlassSpacing.Large),
            ) { Text(stringResource(R.string.visuals_open_customize)) }
            GlassButton(
                onClick = { shell.appUi.send(AppUiCommand.OpenSheet(Sheet.CAPTURE)) },
                filled = false,
                modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(GlassSpacing.Large),
            ) { Text(stringResource(R.string.visuals_open_capture)) }
            GlassButton(
                onClick = { onHeldChange(!reactionsHeld) },
                filled = false,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(GlassSpacing.Large),
            ) {
                Text(stringResource(if (reactionsHeld) R.string.visuals_reactions_unfreeze else R.string.visuals_reactions_freeze))
            }
        }
    }
}

/**
 * The plain player, opened from the mini-player while Visuals off is on: the full Player controls over
 * the playing song's art, blurred. Back or the close button goes back to the page.
 */
@Composable
private fun PlainPlayer(
    shell: ShellViewModel,
    artwork: ImageBitmap?,
) {
    Box(Modifier.fillMaxSize()) {
        ArtworkBackdrop(artwork, Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            GlassTopBar(
                title = stringResource(R.string.home_now_playing),
                navigation = {
                    IconButton(onClick = { shell.appUi.send(AppUiCommand.ExitFullScreen) }) {
                        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(R.string.player_close))
                    }
                },
            )
            PlayerControls(shell, Modifier.weight(1f))
        }
    }
}

/** What lies behind each place: the playing song's art behind Home, the calm backdrop elsewhere. */
@Composable
private fun PlaceBackdrop(
    place: Place,
    artwork: ImageBitmap?,
) {
    if (place == Place.HOME) ArtworkBackdrop(artwork, Modifier.fillMaxSize()) else CalmBackdrop()
}

/** The playing song's embedded art, loaded off the main thread and cached; null without a song or art. */
@Composable
private fun rememberNowPlayingArtwork(shell: ShellViewModel): ImageBitmap? {
    val context = LocalContext.current
    val playing by shell.playback.state.collectAsStateWithLifecycle()
    val track =
        playing.player.currentQueue
            ?.current
            ?.id
    val artwork by produceState(track?.let(ArtworkCache::peek), track) {
        value = track?.let { ArtworkCache.load(context, it) }
    }
    return artwork
}

/** The App UI's banner (#182): one failure at a time, over everything, until it times out or is dismissed. */
@Composable
private fun FailureBanner(
    banner: Banner,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassNotice(
        banner.message,
        modifier.padding(horizontal = GlassSpacing.Medium, vertical = GlassSpacing.Small),
        tone = GlassTone.ERROR,
        actionLabel = stringResource(R.string.notice_dismiss),
        onAction = onDismiss,
    )
}

/**
 * The phone layout: the page over its backdrop, the mini-player at the top or bottom as the person
 * placed it, and the Bar on one slab of glass with the mini-player when it is at the bottom. The Bar
 * frosts the page live, except over the live Visual, which it never covers.
 */
@Composable
private fun AppShellCompact(
    place: Place,
    onOpen: (Place) -> Unit,
    gui: GuiPrefs,
    miniPlayer: (@Composable () -> Unit)?,
    source: GlassSource,
    backdrop: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val live = place != Place.VISUALS
    val top = miniPlayer.takeIf { gui.playerPosition == PlayerPosition.TOP }
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            if (top != null) {
                Box(Modifier.fillMaxWidth().glassSurface(GlassKind.BAR, RectangleShape, edge = GlassEdge.NONE).statusBarsPadding()) {
                    top()
                }
            }
        },
        bottomBar = {
            PlaceBar(
                place,
                onOpen,
                backdrop = if (live) GlassBackdrop.Live(source) else GlassBackdrop.Baked,
                above = miniPlayer.takeIf { gui.playerPosition == PlayerPosition.BOTTOM },
            )
        },
    ) { pad ->
        // The backdrop and the page are what the Bar and the sheets frost; the live Visual is left out.
        Box(Modifier.fillMaxSize().then(if (live) Modifier.glassSource(source) else Modifier)) {
            backdrop()
            Box(Modifier.padding(pad)) { content() }
        }
    }
}

/** The wide layout: the rail of places down the side on glass, the mini-player on glass above the page. */
@Composable
private fun AppShellExpanded(
    place: Place,
    onOpen: (Place) -> Unit,
    miniPlayer: (@Composable () -> Unit)?,
    backdrop: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        backdrop()
        Row(Modifier.fillMaxSize()) {
            GlassNavigationRail(
                items = Place.entries.map { GlassNavItem(stringResource(it.label), it.icon) },
                selected = Place.entries.indexOf(place),
                onSelect = { onOpen(Place.entries[it]) },
            )
            Column(Modifier.weight(1f).statusBarsPadding().navigationBarsPadding()) {
                if (miniPlayer != null) {
                    Box(
                        Modifier
                            .padding(GlassSpacing.Small)
                            .glassSurface(GlassKind.BAR, MaterialTheme.shapes.large),
                    ) { miniPlayer() }
                }
                Box(Modifier.weight(1f)) { content() }
            }
        }
    }
}

/** The Bar: every place, in its one order, for everyone (PR #186), on glass. */
@Composable
private fun PlaceBar(
    selected: Place,
    onOpen: (Place) -> Unit,
    backdrop: GlassBackdrop,
    above: (@Composable () -> Unit)?,
) {
    GlassNavigationBar(
        items = Place.entries.map { GlassNavItem(stringResource(it.label), it.icon) },
        selected = Place.entries.indexOf(selected),
        onSelect = { onOpen(Place.entries[it]) },
        backdrop = backdrop,
        above = above,
    )
}

/**
 * Home: the playing song's art, blurred, behind a now-playing card, with a way into Visuals and
 * Studio. With nothing loaded, the card offers the song played last.
 */
@Composable
private fun HomeScreen(
    shell: ShellViewModel,
    loaded: Boolean,
    onResume: () -> Unit,
    onOpen: (Place) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = GlassSpacing.Large, vertical = GlassSpacing.ExtraLarge),
        verticalArrangement = Arrangement.spacedBy(GlassSpacing.Large),
    ) {
        // Straight on the art, so in the primary text colour and large (legible over any art).
        Text(stringResource(R.string.nav_home), style = MaterialTheme.typography.headlineMedium)
        if (loaded) {
            GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = GlassSpacing.Medium)) {
                GlassSectionHeader(stringResource(R.string.home_now_playing), Modifier.padding(horizontal = GlassSpacing.Large))
                MiniPlayerRoute(
                    shell.playback,
                    elapsedNow = ::elapsedNow,
                    onExpand = { shell.appUi.send(AppUiCommand.EnterFullScreen) },
                )
            }
        } else {
            GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
                GlassMessage(
                    title = stringResource(R.string.home_nothing_playing),
                    body = stringResource(R.string.home_nothing_playing_hint),
                    icon = Icons.Rounded.MusicNote,
                    actionLabel = stringResource(R.string.action_resume_last_played),
                    onAction = onResume,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(GlassSpacing.Medium)) {
            Shortcut(Place.VISUALS, R.string.home_shortcut_visuals, R.string.home_shortcut_visuals_hint, onOpen, Modifier.weight(1f))
            Shortcut(Place.STUDIO, R.string.home_shortcut_studio, R.string.home_shortcut_studio_hint, onOpen, Modifier.weight(1f))
        }
    }
}

/** A card on Home that opens [place]. */
@Composable
private fun Shortcut(
    place: Place,
    @StringRes title: Int,
    @StringRes hint: Int,
    onOpen: (Place) -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier, onClick = { onOpen(place) }, verticalArrangement = Arrangement.spacedBy(GlassSpacing.Small)) {
        Icon(place.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PlaybackNoticeBanner(viewModel: PlayerViewModel) {
    val notice by viewModel.playbackNotice.collectAsStateWithLifecycle()
    val message = notice ?: return

    LaunchedEffect(message) {
        kotlinx.coroutines.delay(NOTICE_VISIBLE_MS)
        viewModel.clearPlaybackNotice()
    }

    GlassNotice(
        message,
        Modifier.padding(horizontal = GlassSpacing.Medium, vertical = GlassSpacing.Small),
        tone = GlassTone.ERROR,
        actionLabel = stringResource(R.string.notice_dismiss),
        onAction = viewModel::clearPlaybackNotice,
    )
}

private const val NOTICE_VISIBLE_MS = 8_000L
