package dev.geode.visuals

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.geode.R
import dev.geode.design.CrystalButton
import dev.geode.design.CrystalTabs
import dev.geode.feature.customization.api.CustomizationFeature
import dev.geode.feature.customization.ui.CustomizeAction
import dev.geode.feature.customization.ui.CustomizeRoute
import dev.geode.feature.customization.ui.LookChip
import dev.geode.feature.customization.ui.ModulatorsAutomationScreen
import dev.geode.feature.customization.ui.PresetChip
import dev.geode.feature.customization.ui.toCustomizeModel
import dev.geode.feature.visuals.ui.FullScreenVisualRoute
import dev.geode.feature.visuals.ui.MilkdropRoute
import dev.geode.feature.visuals.ui.PresetsRoute
import dev.geode.feature.visuals.ui.VisualsRoute
import dev.geode.playback.VisualFeed
import dev.geode.render.VisualizerView
import dev.geode.ui.shell.ShellViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import dev.geode.feature.customization.ui.R as CustomizationR
import dev.geode.feature.visuals.ui.R as VisualsR

/**
 * Binds the app's one live Visual to the engine (#17): it draws the Style Customization shows, takes
 * each Customization's snapshot through its own exchange, and, while [running], reads the sound straight
 * from the playback engine on its own thread. When it stops running (Visuals off, #196, or no place
 * shows it) nothing feeds it and nothing analyses for it.
 */
@Composable
fun LiveVisualBindings(
    view: VisualizerView,
    shell: ShellViewModel,
    running: Boolean,
    reducedMotion: Boolean,
) {
    val renderer = view.visualizerRenderer
    val context = LocalContext.current
    val feed = remember(view) { VisualFeed(context) }
    DisposableEffect(view, shell.snapshots) {
        val exchange = shell.snapshots.open()
        renderer.snapshots = exchange
        onDispose {
            renderer.snapshots = null
            shell.snapshots.release(exchange)
        }
    }
    DisposableEffect(view, running) {
        if (running) feed.attach(renderer)
        onDispose { feed.detach(renderer) }
    }
    LaunchedEffect(view, shell.customization) {
        shell.customization.state
            .map { it.current.style }
            .distinctUntilChanged()
            .collect { renderer.requestedSceneId = it }
    }
    LaunchedEffect(view, reducedMotion) { renderer.reducedMotion = reducedMotion }
}

/** The Visuals place's pages, in the order its tabs show them. */
enum class VisualsTab {
    STYLES,
    PRESETS,
    MILKDROP,
    MODULATORS,
}

/**
 * The Visuals place (#17): the feature's Styles, Presets and MilkDrop pages and Customization's
 * Modulators & Automation page, over the live Visual, with Customize a tap away.
 */
@Composable
fun VisualsPage(
    shell: ShellViewModel,
    tab: VisualsTab,
    onTab: (VisualsTab) -> Unit,
    onCustomize: () -> Unit,
    onFullScreen: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CrystalTabs(
                titles =
                    listOf(
                        stringResource(R.string.visuals_tab_styles),
                        stringResource(VisualsR.string.presets_title),
                        stringResource(VisualsR.string.milkdrop_title),
                        stringResource(CustomizationR.string.modulators_automation_title),
                    ),
                selected = tab.ordinal,
                onSelect = { onTab(VisualsTab.entries[it]) },
                modifier = Modifier.weight(1f),
            )
            CrystalButton(onClick = onCustomize, compact = true) { Text(stringResource(CustomizationR.string.customize_title)) }
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                VisualsTab.STYLES -> VisualsRoute(shell.visuals, onEnterFullScreen = onFullScreen)
                VisualsTab.PRESETS ->
                    PresetsRoute(
                        shell.visuals,
                        onShare = { share -> scope.launch { shell.sharing.share(context, share) } },
                        onBack = { onTab(VisualsTab.STYLES) },
                    )
                VisualsTab.MILKDROP -> MilkdropRoute(shell.visuals)
                VisualsTab.MODULATORS -> ModulatorsAutomation(shell.customization)
            }
        }
    }
}

/** Customization's Modulators & Automation page, over its store. */
@Composable
private fun ModulatorsAutomation(customization: CustomizationFeature) {
    val state by customization.state.collectAsStateWithLifecycle()
    ModulatorsAutomationScreen(
        state.toCustomizeModel(),
        onAction = { action -> if (action is CustomizeAction.Send) customization.send(action.command) },
    )
}

/**
 * The full-screen place (#17): the live Visual behind the feature's own controls and the Player's.
 * With Visuals off (#196) it is a still backdrop with the Player controls always shown; Back leaves it
 * either way ([onExit]).
 */
@Composable
fun VisualFullScreen(
    shell: ShellViewModel,
    visualsOff: Boolean,
    keepScreenOn: Boolean,
    onExit: () -> Unit,
    playerControls: @Composable (Modifier) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        if (visualsOff) {
            BackHandler(onBack = onExit)
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        } else {
            FullScreenVisualRoute(shell.visuals, keepScreenOn = keepScreenOn, onExit = onExit)
        }
        playerControls(Modifier.align(Alignment.BottomCenter).fillMaxWidth())
    }
}

/**
 * Customize, over the live Visual (#12): Customization's own panel, offered the Visuals' Styles as
 * looks and this Style's saved presets. Browsing presets opens the Visuals place's Presets page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomizeSheet(
    shell: ShellViewModel,
    onClose: () -> Unit,
    onBrowsePresets: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val visuals by shell.visuals.state.collectAsStateWithLifecycle()
    val current by shell.customization.state.collectAsStateWithLifecycle()
    val style = current.current.style
    val looks = visuals.styles.map { LookChip(it.id, it.name) }
    val presets =
        visuals.presets
            ?.saved
            .orEmpty()
            .filter { it.style == style }
            .map { PresetChip(it.id, it.id.value) }
    val name = visuals.styles.firstOrNull { it.id == style }?.name ?: style.value
    ModalBottomSheet(onDismissRequest = onClose) {
        CustomizeRoute(
            shell.customization,
            onShare = { customization -> scope.launch { shell.sharing.share(context, name, customization) } },
            onHide = onClose,
            looks = looks,
            presets = presets,
            onBrowsePresets = onBrowsePresets,
        )
    }
}
