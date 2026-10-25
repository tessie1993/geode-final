package dev.geode.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import dev.geode.design.element.ElementTheme
import dev.geode.design.theme.ProvideReducedMotion
import dev.geode.feature.settings.api.Element
import dev.geode.feature.settings.ui.tokens
import dev.geode.ui.GuiPrefs

/**
 * The App UI in the Element chosen in Settings (#103, #193): one token set for every screen, so a new
 * Element recomposes the whole App UI at once. The text size and reduced motion from Settings apply on
 * top of whichever Element it is.
 */
@Composable
fun GeodeTheme(
    element: Element,
    gui: GuiPrefs,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val scaled = Density(density.density, density.fontScale * gui.textScale)
    ElementTheme(element.tokens()) {
        CompositionLocalProvider(LocalDensity provides scaled) {
            ProvideReducedMotion(appPreference = gui.reducedMotion, content = content)
        }
    }
}
