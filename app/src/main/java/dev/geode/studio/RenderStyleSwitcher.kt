package dev.geode.studio

import dev.geode.engine.types.SceneId
import dev.geode.feature.customization.api.Customization
import dev.geode.feature.customization.api.StyleMark
import dev.geode.feature.customization.core.Automation
import dev.geode.feature.customization.core.Edits
import dev.geode.feature.customization.core.StyleCatalogue

/**
 * What a Render's Style switching picks from beside its Customization, frozen when it is queued (#343):
 * the Visualizer styles, the person's marks and their Favourites, as Customization had them then. A
 * Render made again picks from the same, whatever the person changed since.
 */
data class SwitchingContext(
    val visualizer: List<SceneId> = emptyList(),
    val marks: Map<SceneId, StyleMark> = emptyMap(),
    val favourites: List<SceneId> = emptyList(),
)

/**
 * A Render's Style switching replayed in its export loop (#343): each time the engine says switching is
 * due, the next Style by Customization's own pure rules ([Automation.nextStyle] seeded by the frozen
 * switching, then [Edits.switchStyle]), counting the switches as the live reducer does. The same frozen
 * look therefore switches to the same Styles at the same moments in every Render of it.
 */
class RenderStyleSwitcher(
    start: Customization,
    private val context: SwitchingContext,
    private val styles: StyleCatalogue,
) {
    /** The Customization the Render draws now. */
    var current: Customization = start
        private set

    private var played = 0

    /** The Customization after one due switch, or null when switching is off or has nowhere to go. */
    fun next(): Customization? {
        val shown = current
        val switching = shown.automations.style ?: return null
        val pick =
            Automation.nextStyle(
                switching,
                shown.style,
                context.visualizer,
                styles.styles.map { it.id },
                played,
                context.marks,
                context.favourites,
            ) ?: return null
        played = pick.played
        current = Edits.switchStyle(shown, styles.manifestOf(shown.style), styles.manifestOf(pick.style))
        return current
    }
}
