package dev.geode.feature.customization.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.geode.design.forest.ForestGlass
import dev.geode.design.forest.ForestShapes
import dev.geode.design.forest.ForestSpacing
import dev.geode.design.forest.LocalForestColors
import dev.geode.feature.customization.api.CustomizationCommand
import dev.geode.feature.customization.api.Reaction
import dev.geode.feature.customization.api.ReactionSource
import dev.geode.feature.customization.api.SharedGroup
import dev.geode.feature.customization.api.Target

/**
 * The sections of the Advanced view (#12), in the order its tabs show them. Modulators have their own
 * screen, [ModulatorsAutomationScreen], beside the Automations.
 */
enum class AdvancedSection {
    REACTIONS,
    CAMERA,
    EFFECTS,
    EXTRAS,
    MACROS,
}

/**
 * Which page of the Customize panel is open: App UI state the screen keeps in `remember` (ADR-0003),
 * never part of the Customization.
 */
sealed interface CustomizePage {
    /** The basic view: the 6 Macros, effect toggles, palettes, presets and looks. */
    data object Basic : CustomizePage

    /**
     * The Advanced view at [section]. In the Macros section, [macro] is the one being edited; in the
     * Reactions section, [board] shows the patch-cable board instead of the link list.
     */
    data class Advanced(
        val section: AdvancedSection,
        val macro: Int? = null,
        val board: Boolean = false,
    ) : CustomizePage

    /**
     * Choosing what a new link reacts to: step 1 of "+", the start of Learn mode ([learn]), or "React
     * to…" on a slider whose [target] is already known.
     */
    data class ChooseSource(
        val target: Target? = null,
        val learn: Boolean = false,
    ) : CustomizePage

    /** Choosing what a new link from [source] moves (step 2 of "+"), or a new property for Macro [macro]. */
    data class ChooseTarget(
        val source: ReactionSource? = null,
        val macro: Int? = null,
    ) : CustomizePage

    /** Shaping a Reaction: the last step of a new link ([isNew]), or editing one from the list. */
    data class EditReaction(
        val reaction: Reaction,
        val isNew: Boolean,
    ) : CustomizePage

    /** The "choose which parts" save. */
    data object SavePreset : CustomizePage
}

/**
 * Customize, the whole panel (ADR-0003, #12): a semi-transparent sheet over the live Visual in
 * portrait, and a panel on the right in landscape and on tablets so the Visual keeps its full height.
 * The Visual itself is drawn by the place that opens Customize, behind this panel; nothing here is
 * ever drawn into a Render or Recording.
 *
 * Stateless: it shows [model] and reports everything the person does to [onAction]. Which page is open
 * and Learn mode are App UI state kept here; [start] is the page it opens on.
 */
@Composable
fun CustomizeScreen(
    model: CustomizeModel,
    onAction: (CustomizeAction) -> Unit,
    modifier: Modifier = Modifier,
    start: CustomizePage = CustomizePage.Basic,
) {
    var page by remember { mutableStateOf(start) }
    var learning by remember { mutableStateOf<ReactionSource?>(null) }
    val send = { command: CustomizationCommand -> onAction(CustomizeAction.Send(command)) }
    val panel =
        PanelState(
            model = model,
            page = page,
            learning = learning,
            open = { page = it },
            learn = { learning = it },
            send = send,
            onAction = onAction,
        )
    BoxWithConstraints(modifier.fillMaxSize()) {
        if (maxWidth > maxHeight) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(CustomizeDimens.sidePanelWidth)
                    .padding(ForestSpacing.Small),
            ) {
                Panel(panel, side = true)
            }
        } else {
            val sheet =
                if (page == CustomizePage.Basic) {
                    Modifier.heightIn(max = maxHeight * BASIC_SHEET_FRACTION)
                } else {
                    Modifier.fillMaxHeight(FULL_SHEET_FRACTION)
                }
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().then(sheet)) {
                Panel(panel, side = false)
            }
        }
    }
}

/** What every page of the panel works with. */
internal class PanelState(
    val model: CustomizeModel,
    val page: CustomizePage,
    val learning: ReactionSource?,
    val open: (CustomizePage) -> Unit,
    val learn: (ReactionSource?) -> Unit,
    val send: (CustomizationCommand) -> Unit,
    val onAction: (CustomizeAction) -> Unit,
) {
    /** A slider moved: linked to the learning source in Learn mode, otherwise set. */
    fun slide(
        target: Target,
        value: Float,
    ) {
        learnOrSet(learning, target, value)?.let(send)
        if (learning != null) learn(null)
    }

    /** A slider is being dragged: the Visual follows it, unless Learn mode waits to link it. */
    fun preview(
        target: Target,
        value: Float,
    ) {
        previewCommand(learning, target, value)?.let(send)
    }
}

@Composable
private fun Panel(
    panel: PanelState,
    side: Boolean,
) {
    val colors = LocalForestColors.current
    Surface(
        shape = if (side) ForestShapes.Card else ForestShapes.Sheet,
        color = ForestGlass.textGlass(colors),
        contentColor = colors.textPrimary,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = ForestSpacing.Large)) {
            if (!side) DragHandle(panel)
            TopBar(panel)
            val learning = panel.learning
            if (learning != null) LearningBanner(sourceName(learning, panel.model), onCancel = { panel.learn(null) })
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = ForestSpacing.ExtraLarge),
            ) {
                when (val page = panel.page) {
                    CustomizePage.Basic -> BasicView(panel)
                    is CustomizePage.Advanced -> AdvancedView(panel, page)
                    is CustomizePage.ChooseSource -> ChooseSource(panel, page)
                    is CustomizePage.ChooseTarget -> ChooseTarget(panel, page)
                    is CustomizePage.EditReaction -> ReactionEditor(panel, page)
                    CustomizePage.SavePreset -> SavePresetPage(panel)
                }
            }
        }
    }
}

/**
 * The sheet's handle: drag it up for the Advanced view, down for the basic view, and down again to hide
 * the panel and leave the Visual alone.
 */
@Composable
private fun DragHandle(panel: PanelState) {
    var dragged by remember { mutableFloatStateOf(0f) }
    val threshold = with(LocalDensity.current) { CustomizeDimens.dragThreshold.toPx() }
    val description = stringResource(R.string.customize_handle)
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = ForestSpacing.Small)
            .semantics { contentDescription = description }
            .draggable(
                rememberDraggableState { dragged += it },
                Orientation.Vertical,
                onDragStopped = {
                    when {
                        dragged < -threshold && panel.page == CustomizePage.Basic ->
                            panel.open(CustomizePage.Advanced(AdvancedSection.REACTIONS))
                        dragged > threshold && panel.page == CustomizePage.Basic -> panel.onAction(CustomizeAction.Hide)
                        dragged > threshold -> panel.open(CustomizePage.Basic)
                    }
                    dragged = 0f
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(CustomizeDimens.handleWidth, CustomizeDimens.handleHeight)
                .background(LocalForestColors.current.textSecondary, ForestShapes.Chip),
        )
    }
}

/** Undo and redo, Save preset, Surprise me, Share, and the way into and out of the Advanced view. */
@Composable
private fun TopBar(panel: PanelState) {
    val model = panel.model
    val basic = panel.page == CustomizePage.Basic
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (!basic) {
            BarButton(Icons.AutoMirrored.Filled.ArrowBack, R.string.customize_back) { panel.open(back(panel.page)) }
        }
        Text(
            stringResource(if (basic) R.string.customize_title else R.string.customize_advanced_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.weight(1f).padding(start = ForestSpacing.ExtraSmall),
        )
        BarButton(Icons.AutoMirrored.Filled.Undo, R.string.customize_undo, enabled = model.canUndo) {
            panel.send(CustomizationCommand.Undo)
        }
        BarButton(Icons.AutoMirrored.Filled.Redo, R.string.customize_redo, enabled = model.canRedo) {
            panel.send(CustomizationCommand.Redo)
        }
        BarButton(Icons.Filled.Save, R.string.customize_save) { panel.open(CustomizePage.SavePreset) }
        BarButton(Icons.Filled.AutoAwesome, R.string.customize_surprise) { panel.onAction(CustomizeAction.SurpriseMe) }
        BarButton(Icons.Filled.Share, R.string.customize_share) { panel.onAction(CustomizeAction.Share) }
        if (basic) {
            BarButton(Icons.Filled.Tune, R.string.customize_open_advanced) {
                panel.open(CustomizePage.Advanced(AdvancedSection.REACTIONS))
            }
        }
    }
}

/** Where Back goes from [page]: the editors return to the Advanced view, the Advanced view to the basic one. */
internal fun back(page: CustomizePage): CustomizePage =
    when (page) {
        CustomizePage.Basic, is CustomizePage.Advanced, CustomizePage.SavePreset -> CustomizePage.Basic
        is CustomizePage.ChooseSource ->
            if (page.target == null || page.learn) {
                CustomizePage.Advanced(AdvancedSection.REACTIONS)
            } else {
                CustomizePage.Advanced(sectionOf(page.target) ?: AdvancedSection.REACTIONS)
            }
        is CustomizePage.ChooseTarget ->
            if (page.macro != null) {
                CustomizePage.Advanced(AdvancedSection.MACROS, page.macro)
            } else {
                CustomizePage.Advanced(AdvancedSection.REACTIONS)
            }
        is CustomizePage.EditReaction -> CustomizePage.Advanced(AdvancedSection.REACTIONS)
    }

/** The Advanced section a slider for [target] lives in; null for a Modulator's, which live on [ModulatorsAutomationScreen]. */
internal fun sectionOf(target: Target): AdvancedSection? =
    when (target) {
        is Target.Shared ->
            if (target.property.group == SharedGroup.CAMERA) {
                AdvancedSection.CAMERA
            } else {
                AdvancedSection.EFFECTS
            }
        is Target.Extra -> AdvancedSection.EXTRAS
        is Target.MacroSlot -> AdvancedSection.MACROS
        is Target.ModulatorField -> null
    }

@Composable
private fun BarButton(
    icon: ImageVector,
    @StringRes label: Int,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = stringResource(label))
    }
}

/** Learn mode's reminder: the next slider moved is linked to the chosen source. */
@Composable
private fun LearningBanner(
    source: String,
    onCancel: () -> Unit,
) {
    val colors = LocalForestColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = ForestSpacing.ExtraSmall)
            .background(colors.accent, ForestShapes.Card)
            .padding(horizontal = ForestSpacing.Medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.customize_learning, source),
            color = colors.onAccent,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onCancel) {
            Text(stringResource(R.string.customize_learning_cancel), color = colors.onAccent)
        }
    }
}
