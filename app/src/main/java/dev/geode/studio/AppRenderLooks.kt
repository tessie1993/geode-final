package dev.geode.studio

import dev.geode.feature.customization.api.CustomizationFeature
import dev.geode.feature.customization.core.PresetRules
import dev.geode.feature.customization.core.StyleCatalogue
import dev.geode.feature.customization.handlers.PresetLibrary
import dev.geode.feature.studio.api.RenderLook
import dev.geode.feature.studio.handlers.FrozenLook
import dev.geode.feature.studio.handlers.RenderLooks
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Freezes a Render's look as it is queued (#338): the Customization as it is now, read through
 * Customization's interface, or a saved preset applied over it by Customization's own rules, encoded
 * whole, Automations included, so nothing that changes afterwards changes the Render.
 */
class AppRenderLooks(
    private val customization: CustomizationFeature,
    private val presets: PresetLibrary,
    private val styles: StyleCatalogue,
    private val io: CoroutineDispatcher,
) : RenderLooks {
    override suspend fun freeze(look: RenderLook): FrozenLook {
        val current = customization.state.value.current
        val frozen =
            when (look) {
                RenderLook.Current -> current
                is RenderLook.Preset -> PresetRules.apply(current, withContext(io) { presets.load(look.preset) }, styles)
            }
        return FrozenLook(RenderLookFormat.encode(frozen))
    }
}
