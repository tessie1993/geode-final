package dev.geode.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.geode.design.theme.CrystalTheme
import dev.geode.design.theme.ThemePack
import dev.geode.render.VisualizerView

/** The App UI's theme (:design's [CrystalTheme]) as the person set it up in Look settings. */
@Composable
internal fun CrystalMaterialTheme(
    pack: ThemePack,
    gui: GuiPrefs,
    content: @Composable () -> Unit,
) {
    CrystalTheme(
        pack = pack,
        shapes = gui.cornerStyle.shapes(),
        accentIntensity = gui.accentIntensity,
        backgroundDim = gui.backgroundDim,
        fontColorArgb = gui.fontColorOverride,
        textScale = gui.textScale,
        reducedMotion = gui.reducedMotion,
        content = content,
    )
}

/** Hosts the Visual's view in the App UI. The Visual itself stays out of :design. */
@Composable
fun VisualizerCanvasHost(
    view: VisualizerView,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = {
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            view
        },
        modifier = modifier,
    )
}
