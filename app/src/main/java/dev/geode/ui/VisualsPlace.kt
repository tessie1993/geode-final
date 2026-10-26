package dev.geode.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.geode.R
import dev.geode.design.glass.GlassBackdrop
import dev.geode.design.glass.GlassButton
import dev.geode.design.glass.GlassCrossfade
import dev.geode.design.glass.GlassEdge
import dev.geode.design.glass.GlassKind
import dev.geode.design.glass.GlassSpacing
import dev.geode.design.glass.glassSurface
import dev.geode.feature.customization.api.Customization
import dev.geode.feature.customization.api.PresetPart
import dev.geode.feature.customization.core.IgnoredControls
import dev.geode.feature.customization.core.PresetRules
import dev.geode.feature.customization.handlers.PresetFormat
import dev.geode.feature.customization.handlers.PresetLibrary
import dev.geode.feature.customization.ui.CustomizeRoute
import dev.geode.feature.customization.ui.LookChip
import dev.geode.feature.customization.ui.ModulatorsAutomationRoute
import dev.geode.feature.customization.ui.PresetChip
import dev.geode.feature.customization.ui.StyleFolderChip
import dev.geode.feature.visuals.api.PresetShare
import dev.geode.feature.visuals.api.StyleGroup
import dev.geode.feature.visuals.core.PresetLinks
import dev.geode.feature.visuals.ui.MilkdropRoute
import dev.geode.feature.visuals.ui.PresetsRoute
import dev.geode.feature.visuals.ui.VisualsRoute
import dev.geode.render.scene.StylePacks
import dev.geode.ui.shell.ShellViewModel
import dev.geode.visuals.StyleTargets
import java.io.File

/** The Visuals tab's three sub-tabs (owner decision on #17, 2026-10-22). */
private enum class VisualsTab(
    @StringRes val label: Int,
) {
    STYLE(R.string.visuals_tab_style),
    CUSTOMIZE(R.string.visuals_tab_customize),
    MODULATORS(R.string.visuals_tab_modulators),
}

/** A page opened over the sub-tabs: the Presets browser (#144) or the MilkDrop import (#145). */
private enum class VisualsPage { TABS, PRESETS, MILKDROP }

/**
 * The Visuals place (#17): Style, Customize, and Modulators & Automation, each from its own feature's
 * `:ui` (ADR-0005), over the one live Visual ([visual], left out when Settings turn the Visual off).
 * Which sub-tab and page are open is App-UI-only state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisualsPlace(
    shell: ShellViewModel,
    onEnterFullScreen: () -> Unit,
    onReloadMilkPreset: () -> Unit,
    visual: @Composable () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(VisualsTab.STYLE) }
    var page by rememberSaveable { mutableStateOf(VisualsPage.TABS) }
    val context = LocalContext.current
    Box(Modifier.fillMaxSize()) {
        visual()
        // Panels over the live Visual fade from one to the next, never slide (#355); the Visual itself
        // stays as it is underneath.
        GlassCrossfade(page, label = "visualsPage") { shown ->
            when (shown) {
                VisualsPage.PRESETS ->
                    PresetsRoute(shell.visuals, onShare = { sharePreset(context, it) }, onBack = { page = VisualsPage.TABS })
                VisualsPage.MILKDROP -> {
                    BackHandler { page = VisualsPage.TABS }
                    MilkdropRoute(shell.visuals, onReload = onReloadMilkPreset)
                }
                VisualsPage.TABS ->
                    Column(Modifier.fillMaxSize()) {
                        // Over the live Visual: matte glass until the engine frosts it (#349).
                        PrimaryTabRow(
                            selectedTabIndex = tab.ordinal,
                            modifier = Modifier.glassSurface(GlassKind.BAR, RectangleShape, GlassBackdrop.Visual, GlassEdge.NONE),
                            containerColor = Color.Transparent,
                        ) {
                            VisualsTab.entries.forEach { entry ->
                                Tab(selected = entry == tab, onClick = { tab = entry }, text = { Text(stringResource(entry.label)) })
                            }
                        }
                        GlassCrossfade(tab, Modifier.weight(1f), label = "visualsTab") { open ->
                            VisualsTabPanel(
                                shell,
                                open,
                                onEnterFullScreen = onEnterFullScreen,
                                onOpenMilkdrop = { page = VisualsPage.MILKDROP },
                                onOpenPresets = { page = VisualsPage.PRESETS },
                                onHideCustomize = { tab = VisualsTab.STYLE },
                            )
                        }
                    }
            }
        }
    }
}

/** What one of the Visuals tab's sub-tabs shows under the tab row. */
@Composable
private fun VisualsTabPanel(
    shell: ShellViewModel,
    tab: VisualsTab,
    onEnterFullScreen: () -> Unit,
    onOpenMilkdrop: () -> Unit,
    onOpenPresets: () -> Unit,
    onHideCustomize: () -> Unit,
) {
    when (tab) {
        VisualsTab.STYLE ->
            Column(Modifier.fillMaxSize()) {
                VisualsRoute(shell.visuals, onEnterFullScreen = onEnterFullScreen, modifier = Modifier.weight(1f))
                GlassButton(
                    onClick = onOpenMilkdrop,
                    filled = false,
                    modifier = Modifier.padding(GlassSpacing.Small),
                ) { Text(stringResource(R.string.visuals_milkdrop)) }
            }
        VisualsTab.CUSTOMIZE -> CustomizePanel(shell, onHide = onHideCustomize, onBrowsePresets = onOpenPresets)
        VisualsTab.MODULATORS -> AutomationPanel(shell)
    }
}

/**
 * Modulators & Automation, with the Visuals feature's Styles to name and its folders for Style
 * switching to switch within (#308).
 */
@Composable
private fun AutomationPanel(shell: ShellViewModel) {
    val visuals by shell.visuals.state.collectAsStateWithLifecycle()
    val library = visuals.library
    ModulatorsAutomationRoute(
        shell.customization,
        looks = visuals.styles.map { LookChip(it.id, it.name) },
        styleFolders =
            library
                ?.folders
                .orEmpty()
                .filter { it.styles.isNotEmpty() }
                .map { StyleFolderChip(it.name, it.styles) },
    )
}

/**
 * Customize over the live Visual, as the Visuals tab and the sheet over full-screen mode show it: its
 * look picker offers the Visuals feature's Styles and its preset row this Style's saved presets. The
 * controls this Style's family ignores, as its Style pack declares them, are greyed out (#365). Both
 * hosts get its drag down to a peek bar, to watch the Visual while changing it (#412).
 */
@Composable
fun CustomizePanel(
    shell: ShellViewModel,
    onHide: () -> Unit,
    onBrowsePresets: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val visuals by shell.visuals.state.collectAsStateWithLifecycle()
    val customization by shell.customization.state.collectAsStateWithLifecycle()
    val style = customization.current.style
    CustomizeRoute(
        shell.customization,
        onShare = { shareCustomization(context, it) },
        onHide = onHide,
        modifier = modifier,
        looks = visuals.styles.map { LookChip(it.id, it.name, canBeLayer = it.group != StyleGroup.MILKDROP) },
        presets =
            visuals.presets
                ?.saved
                .orEmpty()
                .filter { it.style == style }
                .map { PresetChip(it.id, it.id.value) },
        ignored =
            IgnoredControls.targets(
                customization.current,
                IgnoredControls.properties(StylePacks.find(StyleTargets.sceneOf(style))?.ignores.orEmpty()),
            ),
        onBrowsePresets = onBrowsePresets,
    )
}

/** Shares what is on screen as a preset link (Visuals' [PresetLinks]), through the share sheet. */
private fun shareCustomization(
    context: Context,
    customization: Customization,
) {
    val name = context.getString(R.string.preset_shared_name)
    val text = PresetFormat.encode(PresetRules.capture(name, customization, PresetPart.entries.toSet()))
    share(context, Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, PresetLinks.encode(text)))
}

/** Shares a saved preset as Visuals prepared it: a link, or its file when it is too big for one. */
private fun sharePreset(
    context: Context,
    preset: PresetShare,
) {
    val send =
        when (preset) {
            is PresetShare.Link ->
                Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.preset_share_subject, preset.id.value))
                    .putExtra(Intent.EXTRA_TEXT, preset.link)
            is PresetShare.File -> {
                val name = PresetLibrary.fileNameOf(preset.id.value)
                val file =
                    File(context.filesDir, PRESET_FOLDER).walkTopDown().firstOrNull { it.isFile && it.name == name } ?: return
                Intent(Intent.ACTION_SEND)
                    .setType("application/json")
                    .putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(context, context.packageName + ".presets", file))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    share(context, send)
}

private fun share(
    context: Context,
    send: Intent,
) {
    runCatching { context.startActivity(Intent.createChooser(send, context.getString(R.string.preset_share_title))) }
}

private const val PRESET_FOLDER = "presets"
