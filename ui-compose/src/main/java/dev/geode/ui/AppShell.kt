package dev.geode.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowWidthSizeClass
import dev.geode.presentation.EntryStage
import dev.geode.presentation.GeodeDestination
import dev.geode.presentation.SearchPhase
import dev.geode.presentation.ShellAction
import dev.geode.presentation.ShellOverlay
import dev.geode.presentation.ShellState
import dev.geode.render.VisualizerView
import dev.geode.resources.R
import dev.geode.ui.theme.StoneIcon
import dev.geode.ui.theme.StoneIconArt

/**
 * A destination paired with the resolved label and icon the navigation surfaces draw for it.
 *
 * Built from [GeodeDestination.entries], so the bar and the rail cannot drift out of step with
 * the screens they switch between, and selection compares destinations rather than positions.
 */
private data class NavEntry(
    val destination: GeodeDestination,
    val item: CrystalNavItem,
)

@Composable
fun AppRoot() {
    val viewModel: PlayerViewModel = geodeViewModel()
    val settingsViewModel: SettingsViewModel = geodeViewModel()
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
    val themePack by settingsViewModel.theme.collectAsStateWithLifecycle()
    val gui by settingsViewModel.guiPrefs.collectAsStateWithLifecycle()
    val systemDark = isSystemInDarkTheme()
    val effectiveTheme =
        if (gui.followSystemDark && !systemDark) {
            dev.geode.ui.theme.ThemePackCatalog.all
                .firstOrNull { it.isLight } ?: themePack
        } else {
            themePack
        }
    val externalDisplay = rememberExternalDisplay()
    val shell: ShellViewModel = geodeViewModel()
    val appState by shell.state.collectAsStateWithLifecycle()
    val crashText by shell.crashReport.collectAsStateWithLifecycle()
    val effectFailed by shell.effectFailed.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val onSecondScreen = gui.secondScreen && externalDisplay != null
    if (onSecondScreen) {
        SecondScreenCanvas(externalDisplay, visualizerView)
    }

    VisualizerEngineBindings(viewModel, visualizerView)
    androidx.activity.compose.BackHandler(enabled = appState.canGoBack) { shell.dispatch(ShellAction.Back) }
    CrystalMaterialTheme(
        pack = effectiveTheme,
        gui = gui,
    ) {
        val miniPlayer: @Composable () -> Unit = {
            MiniPlayer(
                title =
                    listOfNotNull(
                        state.title,
                        state.artist?.takeIf { it.isNotBlank() },
                    ).joinToString(" \u2014 ").ifBlank { null },
                isPlaying = state.isPlaying,
                hasMedia = state.hasMedia,
                progress =
                    if (state.durationMs > 0) {
                        state.positionMs / state.durationMs.toFloat()
                    } else {
                        0f
                    },
                compact = gui.compactPlayer,
                barOpacity = gui.barOpacity,
                onExpand = { shell.dispatch(ShellAction.Open(ShellOverlay.PLAYER)) },
                onPlayPause = viewModel::togglePlayPause,
                onPrevious = viewModel::previous,
                onNext = viewModel::next,
            )
        }
        // Someone who came here to listen does not get a render queue in their navigation bar.
        // First run no longer asks, so everyone starts with everything and narrows it in Settings.
        val showsStudio = gui.intent.showsStudio
        val navEntries =
            GeodeDestination.entries
                .filter { it != GeodeDestination.STUDIO || showsStudio }
                .map { NavEntry(it, CrystalNavItem(stringResource(it.labelRes), it.icon)) }
        val destinationContent: @Composable (twoPane: Boolean) -> Unit = { twoPane ->
            PlaybackNoticeBanner(viewModel)
            when (appState.destination) {
                GeodeDestination.PLAYER ->
                    PlayerScreen(
                        viewModel,
                        onOpenSearch = { shell.dispatch(ShellAction.Open(ShellOverlay.SEARCH)) },
                        onExpand = { shell.dispatch(ShellAction.Open(ShellOverlay.PLAYER)) },
                        onOpenLibrary = { shell.dispatch(ShellAction.Navigate(GeodeDestination.LIBRARY)) },
                    )
                GeodeDestination.LIBRARY ->
                    if (twoPane) {
                        Row(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f)) {
                                LibraryScreen(onOpenSearch = { shell.dispatch(ShellAction.Open(ShellOverlay.SEARCH)) })
                            }
                            Box(
                                Modifier
                                    .width(1.dp)
                                    .fillMaxHeight()
                                    .luminousHairline(MaterialTheme.colorScheme.primary),
                            )
                            Box(Modifier.weight(1f)) {
                                PlayerScreen(
                                    viewModel,
                                    onOpenSearch = { shell.dispatch(ShellAction.Open(ShellOverlay.SEARCH)) },
                                    onExpand = { shell.dispatch(ShellAction.Open(ShellOverlay.PLAYER)) },
                                    onOpenLibrary = {},
                                )
                            }
                        }
                    } else {
                        LibraryScreen(onOpenSearch = { shell.dispatch(ShellAction.Open(ShellOverlay.SEARCH)) })
                    }
                GeodeDestination.VISUALS ->
                    VisualsHub(
                        viewModel,
                        visualizerView,
                        onOpenNowPlaying = { shell.dispatch(ShellAction.Open(ShellOverlay.PLAYER)) },
                        liveBackdrop = gui.clearVisualsMenu && appState.overlay != ShellOverlay.PLAYER && !onSecondScreen,
                    )
                GeodeDestination.STUDIO -> StudioRoute()
                GeodeDestination.SETTINGS ->
                    SettingsScreen(
                        viewModel,
                        exportOpen = appState.overlay == ShellOverlay.EXPORT,
                        onOpenExport = { shell.dispatch(ShellAction.Open(ShellOverlay.EXPORT)) },
                        onStartTutorial = { shell.dispatch(ShellAction.StartTutorial) },
                    )
            }
        }
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            CrystalBackground(Modifier.fillMaxSize(), reducedMotion = gui.reducedMotion)
            val widthClass = currentWindowAdaptiveInfo().windowSizeClass.windowWidthSizeClass
            if (widthClass == WindowWidthSizeClass.COMPACT) {
                AppShellCompact(
                    navEntries = navEntries,
                    appState = appState,
                    onNavigate = { shell.dispatch(ShellAction.Navigate(it)) },
                    gui = gui,
                    hasMedia = state.hasMedia,
                    miniPlayer = miniPlayer,
                    content = { destinationContent(false) },
                )
            } else {
                AppShellExpanded(
                    navEntries = navEntries,
                    appState = appState,
                    onNavigate = { shell.dispatch(ShellAction.Navigate(it)) },
                    hasMedia = state.hasMedia,
                    miniPlayer = miniPlayer,
                    content = { destinationContent(true) },
                )
            }
            if (appState.overlay == ShellOverlay.SEARCH) {
                SearchScreen(onClose = { shell.dispatch(ShellAction.CloseOverlay) })
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
                            shell.dismissCrash()
                        }) { Text(stringResource(R.string.action_dismiss)) }
                    },
                )
            }
            if (appState.overlay == ShellOverlay.PLAYER) {
                VisualizerScreen(
                    viewModel = viewModel,
                    visualizerView = visualizerView,
                    externalDisplayName = if (onSecondScreen) externalDisplay?.name else null,
                    onCollapse = { shell.dispatch(ShellAction.CloseOverlay) },
                    onOpenVisuals = {
                        shell.dispatch(ShellAction.Navigate(GeodeDestination.VISUALS))
                    },
                )
            }
            if (appState.overlay == ShellOverlay.EXPORT) {
                ExportHost(viewModel, visualizerView) { shell.dispatch(ShellAction.CloseOverlay) }
            }
            when (appState.stage) {
                EntryStage.LOADING ->
                    Box(
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).clickable(onClick = {}),
                        contentAlignment = Alignment.Center,
                    ) { androidx.compose.material3.CircularProgressIndicator() }
                EntryStage.BOOT -> BootIntro(onDone = { shell.dispatch(ShellAction.BootFinished) })
                EntryStage.SAFETY -> SafetyConsent(onAcknowledge = { shell.dispatch(ShellAction.AcknowledgeSafety) })
                EntryStage.SETUP -> FirstRunGate(onDone = { shell.dispatch(ShellAction.FinishSetup) })
                EntryStage.TUTORIAL ->
                    TutorialOverlay(
                        steps = appState.tourStops.map { it.appearance() },
                        index = appState.tourStops.indexOf(appState.tourStop).coerceAtLeast(0),
                        dontShowAgain = !gui.tutorialOnFirstRun,
                        onDontShowAgainChange = { shell.dispatch(ShellAction.SetTutorialOffer(!it)) },
                        onBack = { shell.dispatch(ShellAction.PreviousTutorialStep) },
                        onNext = { shell.dispatch(ShellAction.NextTutorialStep) },
                        onDismiss = { shell.dispatch(ShellAction.DismissTutorial) },
                    )
                EntryStage.READY -> Unit
            }
            if (effectFailed) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = shell::dismissError,
                    text = { Text(stringResource(R.string.presentation_action_failed)) },
                    confirmButton = { CrystalButton(onClick = shell::dismissError) { Text(stringResource(R.string.action_dismiss)) } },
                )
            }
        }
    }
}

@Composable
private fun AppShellCompact(
    navEntries: List<NavEntry>,
    appState: ShellState,
    onNavigate: (GeodeDestination) -> Unit,
    gui: GuiPrefs,
    hasMedia: Boolean,
    miniPlayer: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            if (gui.playerPosition == PlayerPosition.TOP && hasMedia && !appState.onPlayer) {
                Box(Modifier.statusBarsPadding()) { miniPlayer() }
            }
        },
        bottomBar = {
            Column {
                if (gui.playerPosition == PlayerPosition.BOTTOM && !appState.onPlayer) miniPlayer()
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .luminousHairline(MaterialTheme.colorScheme.primary),
                )
                CrystalNavBar(
                    items = navEntries.map { it.item },
                    // A destination can be hidden while still being the current one — reaching
                    // Studio from a track menu, say. Fall back to the first tab rather than -1.
                    selected = navEntries.indexOfFirst { it.destination == appState.destination }.coerceAtLeast(0),
                    onSelect = { onNavigate(navEntries[it].destination) },
                    opacity = gui.barOpacity,
                )
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad)) { content() }
    }
}

@Composable
private fun AppShellExpanded(
    navEntries: List<NavEntry>,
    appState: ShellState,
    onNavigate: (GeodeDestination) -> Unit,
    hasMedia: Boolean,
    miniPlayer: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        NavigationRail(containerColor = Color.Transparent) {
            navEntries.forEach { (destination, item) ->
                NavigationRailItem(
                    selected = appState.destination == destination,
                    onClick = { onNavigate(destination) },
                    icon = { StoneIconArt(item.icon, item.label) },
                    label = { Text(item.label, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        Box(
            Modifier
                .width(1.dp)
                .fillMaxHeight()
                .luminousHairline(MaterialTheme.colorScheme.primary),
        )
        Column(Modifier.weight(1f)) {
            if (hasMedia && !appState.onPlayer) miniPlayer()
            Box(Modifier.weight(1f)) { content() }
        }
    }
}

@Composable
private fun MiniPlayer(
    title: String?,
    isPlaying: Boolean,
    hasMedia: Boolean,
    progress: Float,
    barOpacity: Float,
    compact: Boolean,
    onExpand: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    if (!hasMedia) return
    Column(
        Modifier
            .fillMaxWidth()
            .crystalPanel(
                barOpacity,
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.colorScheme.primary,
                corner = 0.dp,
                glowStrength = 0.6f,
                facets = 0.8f,
            ).clickable(onClick = onExpand),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = if (compact) 0.dp else 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.MusicNote,
                null,
                Modifier
                    .size(if (compact) 20.dp else 28.dp)
                    .softGlow(MaterialTheme.colorScheme.primary, 8.dp, if (isPlaying) 1f else 0.35f),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                title ?: stringResource(R.string.mini_player_idle),
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            IconButton(onClick = onPrevious) { StoneIconArt(StoneIcon.PREVIOUS, stringResource(R.string.action_previous)) }
            IconButton(onClick = onPlayPause) {
                StoneIconArt(
                    if (isPlaying) StoneIcon.PAUSE else StoneIcon.PLAY,
                    stringResource(R.string.action_play_pause),
                )
            }
            IconButton(onClick = onNext) { StoneIconArt(StoneIcon.NEXT, stringResource(R.string.action_next)) }
        }
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
        )
    }
}

@Composable
fun SettingsScreen(
    viewModel: PlayerViewModel,
    exportOpen: Boolean,
    onOpenExport: () -> Unit,
    onStartTutorial: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
            CrystalOverline(stringResource(R.string.app_name))
            GlowTitle(stringResource(R.string.nav_settings))
        }
        AppSettingsTab(
            viewModel,
            exportOpen = exportOpen,
            onOpenExport = onOpenExport,
            onStartTutorial = onStartTutorial,
        )
    }
}

@Composable
fun SearchScreen(onClose: () -> Unit) {
    val settingsViewModel: SettingsViewModel = geodeViewModel()
    val searchViewModel: SearchViewModel = geodeViewModel()
    val search by searchViewModel.state.collectAsStateWithLifecycle()
    val gui by settingsViewModel.guiPrefs.collectAsStateWithLifecycle()
    LaunchedEffect(searchViewModel) { searchViewModel.open() }
    val dismiss = rememberPredictiveDismiss(onDismiss = onClose)
    val trackResults = search.results?.tracks.orEmpty()
    val playlistResults = search.results?.playlists.orEmpty()
    val presetResults = search.results?.presets.orEmpty()

    Box(Modifier.fillMaxSize().dismissTransform(dismiss)) {
        CrystalBackground(Modifier.fillMaxSize(), reducedMotion = gui.reducedMotion)
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = search.query,
                    onValueChange = searchViewModel::setQuery,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.search_placeholder)) },
                    singleLine = true,
                    shape = crystalShardShape(14.dp, 5.dp),
                )
                IconButton(onClick = onClose) { StoneIconArt(StoneIcon.CLOSE, stringResource(R.string.search_close)) }
            }
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (search.phase == SearchPhase.IDLE) {
                    item {
                        Text(
                            stringResource(R.string.search_hint),
                            Modifier.padding(vertical = 16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (search.phase == SearchPhase.LOADING) {
                    item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                } else if (search.phase == SearchPhase.FAILED) {
                    item { Text(stringResource(R.string.search_failed)) }
                } else {
                    if (trackResults.isNotEmpty()) {
                        item {
                            CrystalOverline(
                                stringResource(R.string.search_heading_tracks, trackResults.size),
                                Modifier.padding(top = 8.dp),
                            )
                        }
                        items(trackResults, key = { "t:${it.uri}" }) { t ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (searchViewModel.playTrack(t.uri)) onClose()
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                    Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (t.subtitle.isNotBlank()) {
                                        Text(
                                            t.subtitle,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                IconButton(onClick = { searchViewModel.enqueue(t.uri) }) {
                                    StoneIconArt(StoneIcon.QUEUE, stringResource(R.string.action_add_to_queue))
                                }
                            }
                        }
                    }
                    if (playlistResults.isNotEmpty()) {
                        item {
                            CrystalOverline(
                                stringResource(R.string.search_heading_playlists, playlistResults.size),
                                Modifier.padding(top = 8.dp),
                            )
                        }
                        items(playlistResults) { pl ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (searchViewModel.playPlaylist(pl.name)) onClose()
                                    }.padding(vertical = 8.dp),
                            ) {
                                Text(pl.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    pluralStringResource(
                                        R.plurals.track_count,
                                        pl.trackUris.size,
                                        pl.trackUris.size,
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (presetResults.isNotEmpty()) {
                        item {
                            CrystalOverline(
                                stringResource(R.string.search_heading_presets, presetResults.size),
                                Modifier.padding(top = 8.dp),
                            )
                        }
                        items(presetResults) { p ->
                            Text(
                                p.name,
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (searchViewModel.applyPreset(p)) onClose()
                                    }.padding(vertical = 8.dp),
                            )
                        }
                    }
                    if (trackResults.isEmpty() && playlistResults.isEmpty() && presetResults.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.search_no_results, search.query),
                                Modifier.padding(vertical = 16.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaybackNoticeBanner(viewModel: PlayerViewModel) {
    val notice by viewModel.playbackNotice.collectAsStateWithLifecycle()
    val message = notice ?: return
    val dismissDescription = stringResource(R.string.notice_dismiss_description)

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
