package dev.geode.feature.customization.core

import dev.geode.engine.types.MilkdropStyles
import dev.geode.engine.types.SceneId
import dev.geode.feature.customization.api.Customization
import dev.geode.feature.customization.api.ReactionId
import dev.geode.feature.customization.api.SharedProperty
import dev.geode.feature.customization.api.SignalTrim
import dev.geode.feature.customization.api.SoundSignal
import dev.geode.feature.customization.api.StyleFamily
import dev.geode.feature.customization.api.StyleManifest

/**
 * The Style manifests the reducer knows, by id. A MilkDrop preset Style ([MilkdropStyles]) is known
 * whenever the MilkDrop family is: it gets that family's manifest under its own id, since every preset
 * plays through the one MilkDrop scene with the Shared core only (#305).
 */
class StyleCatalogue(
    val styles: List<StyleManifest>,
) {
    private val byId = styles.associateBy { it.id }

    private val milkdrop = styles.firstOrNull { it.family == StyleFamily.MILKDROP }

    operator fun get(id: SceneId): StyleManifest? = byId[id] ?: MilkdropStyles.file(id)?.let { milkdrop?.copy(id = id) }

    /**
     * [id]'s manifest, or a bare one (Shared core only, the shared Macros, no Extras or Reactions) for
     * a Style this version does not know, such as a removed one.
     */
    fun manifestOf(id: SceneId): StyleManifest =
        get(id) ?: StyleManifest(
            id = id,
            family = StyleFamily.SHADER,
            ownFlight = false,
            extras = emptyList(),
            shared = emptyMap(),
            macros = DefaultMacros.forStyle(StyleFamily.SHADER, ownFlight = false, extras = emptySet()),
            reactions = emptyList(),
        )
}

/** A Style's own starting Customization, as its manifest declares it. */
object Defaults {
    fun customization(manifest: StyleManifest): Customization =
        Customization(
            style = manifest.id,
            shared = SharedProperty.entries.associateWith { Rules.shared(it, manifest.shared[it] ?: it.default) },
            extras = manifest.extras.associate { it.kind to Rules.unit(it.default) },
            macros = manifest.macros,
            reactions = manifest.reactions.mapIndexed { index, reaction -> Rules.reaction(reaction.copy(id = ReactionId(index + 1))) },
            modulators = emptyList(),
            signals = SoundSignal.entries.associateWith { SignalTrim.NEUTRAL },
        )
}
