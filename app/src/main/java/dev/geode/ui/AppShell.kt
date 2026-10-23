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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import dev.geode.design.CrystalBackground
import dev.geode.design.CrystalButton
import dev.geode.design.CrystalNavBar
import dev.geode.design.CrystalNavItem
import dev.geode.design.CrystalOverline
import dev.geode.design.GlowTitle
import dev.geode.design.luminousHairline
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
import dev.geode.render.VisualizerView
import dev.geode.ui.shell.AppUiCommand
import dev.geode.ui.shell.AppUiFact
import dev.geode.ui.shell.Banner
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
    DisposableEffect(lifecycleOwner, visualizerView) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> visualizerView.onResume()
                    Lifecycle.Event.ON_PAUSE -> visualizerView.onPause()
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
    VisualizerEngineBindings(viewModel, visualizerView)
    CrystalMaterialTheme(
        pack = dev.geode.design.theme.ThemePackCatalog.lapisLazuli,
        gui = gui,
    ) {
        val open: (Place) -> Unit = { appUi.send(AppUiCommand.Open(it)) }
        val miniPlayer: @Composable () -> Unit = {
            if (ui.playerBarShown(loaded)) {
                MiniPlayerRoute(shell.playback, elapsedNow = ::elapsedNow, onExpand = { appUi.send(AppUiCommand.EnterFullScreen) })
            }
        }
        // Set by Settings > Help to replay the tour after it has already been seen; the automatic
        // first-run showing is a function of the stored prefs instead, so this stays false there.
        var tutorialRunning by rememberSaveable { mutableStateOf(false) }
        // Offered once, to someone who has finished setup and has not turned the offer off.
        val offerTutorial = gui.setupDone && !gui.tutorialSeen && gui.tutorialOnFirstRun
        // Latched the moment the tour is offered, so that from then on its visibility depends on
        // `tutorialRunning` alone. Without this, ticking "don't show this next time" DURING the
        // tour clears `tutorialOnFirstRun`, which clears `offerTutorial`, which closes the tour
        // out from under the person who was reading it — the checkbox is about the NEXT install,
        // not this minute.
        LaunchedEffect(offerTutorial) { if (offerTutorial) tutorialRunning = true }
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
                                    .luminousHairline(MaterialTheme.colorScheme.primary),
                            )
                            Box(Modifier.weight(1f)) { PlayerControls(shell) }
                        }
                    } else {
                        MusicLibrary(shell)
                    }
                Place.VISUALS ->
                    VisualsHub(
                        viewModel,
                        visualizerView,
                        onOpenNowPlaying = { appUi.send(AppUiCommand.EnterFullScreen) },
                        liveBackdrop = gui.clearVisualsMenu && !ui.fullScreen,
                    )
                Place.STUDIO -> StudioRoute()
                Place.SETTINGS -> SettingsScreen(viewModel, visualizerView, onStartTutorial = { tutorialRunning = true })
            }
        }
        val content: @Composable (twoPane: Boolean) -> Unit = { twoPane ->
            Column {
                PlaybackNoticeBanner(viewModel)
                page(twoPane)
            }
        }
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            CrystalBackground(Modifier.fillMaxSize(), reducedMotion = gui.reducedMotion)
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
                        CrystalButton(onClick = {
                            val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                            cm.setPrimaryClip(android.content.ClipData.newPlainText(clipLabel, text))
                        }) { Text(stringResource(R.string.action_copy)) }
                    },
                    dismissButton = {
                        CrystalButton(filled = false, onClick = {
                            java.io.File(context.filesDir, "crash-latest.txt").delete()
                            crashText = null
                        }) { Text(stringResource(R.string.action_dismiss)) }
                    },
                )
            }
            if (ui.fullScreen) {
                VisualizerScreen(
                    viewModel = viewModel,
                    visualizerView = visualizerView,
                    onCollapse = { appUi.send(AppUiCommand.ExitFullScreen) },
                    onOpenVisuals = {
                        appUi.send(AppUiCommand.ExitFullScreen)
                        open(Place.VISUALS)
                    },
                )
            }
            PlayerSheet(shell, ui.sheet)
            ui.banner?.let { banner ->
                FailureBanner(banner, onDismiss = { appUi.send(AppUiCommand.DismissBanner) }, Modifier.statusBarsPadding())
            }
            if (!gui.safetyAcknowledged) {
                SafetyConsent(
                    onAcknowledge = { settingsViewModel.setGuiPrefs(gui.copy(safetyAcknowledged = true)) },
                )
            } else if (!gui.setupDone) {
                // Setup runs only after the notice is acknowledged, and only until it is done.
                FirstRunGate(
                    onDone = {
                        settingsViewModel.setGuiPrefs(gui.copy(setupDone = true))
                        open(Place.MUSIC)
                    },
                )
            } else if (tutorialRunning) {
                // Offered once setup is out of the way, so the tour walks a real app with a real
                // library rather than a set of empty tabs.
                TutorialOverlay(
                    steps = TutorialStep.entries,
                    dontShowAgain = !gui.tutorialOnFirstRun,
                    onDontShowAgainChange = { settingsViewModel.setGuiPrefs(gui.copy(tutorialOnFirstRun = !it)) },
                    onNavigate = open,
                    onDismiss = {
                        tutorialRunning = false
                        settingsViewModel.setGuiPrefs(gui.copy(tutorialSeen = true))
                    },
                )
            }
        }
    }
}

/** The monotonic clock Playback's marks use (ADR-0004). */
private fun elapsedNow(): Millis = Millis(SystemClock.elapsedRealtime())

private tailrec fun Context.findActivity(): Activity? =
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

/** The Queue or Tools sheet the App UI has open, if either; Customize opens from the Visuals. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSheet(
    shell: ShellViewModel,
    sheet: Sheet?,
) {
    if (sheet != Sheet.QUEUE && sheet != Sheet.LISTENING) return
    ModalBottomSheet(onDismissRequest = { shell.appUi.send(AppUiCommand.CloseSheet) }) {
        when (sheet) {
            Sheet.QUEUE -> QueueRoute(shell.playback)
            Sheet.LISTENING -> ListeningRoute(shell.playback, elapsedNow = ::elapsedNow)
            Sheet.CUSTOMIZE -> Unit
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
                        .luminousHairline(MaterialTheme.colorScheme.primary),
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
                .luminousHairline(MaterialTheme.colorScheme.primary),
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
    CrystalNavBar(
        items = Place.entries.map { CrystalNavItem(stringResource(it.label), it.icon) },
        selected = Place.entries.indexOf(selected),
        onSelect = { onOpen(Place.entries[it]) },
        opacity = opacity,
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
        CrystalOverline(stringResource(R.string.app_name))
        GlowTitle(stringResource(R.string.nav_home))
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
            CrystalButton(onClick = onResume) {
                StoneIconArt(StoneIcon.PLAY, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_resume_last_played))
            }
        }
    }
}

@Composable
fun SettingsScreen(
    viewModel: PlayerViewModel,
    visualizerView: VisualizerView,
    onStartTutorial: () -> Unit,
) {
    var showExport by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
            CrystalOverline(stringResource(R.string.app_name))
            GlowTitle(stringResource(R.string.nav_settings))
        }
        AppSettingsTab(
            viewModel,
            exportOpen = showExport,
            onOpenExport = { showExport = true },
            onStartTutorial = onStartTutorial,
        )
    }
    if (showExport) {
        ExportHost(viewModel, visualizerView) { showExport = false }
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
