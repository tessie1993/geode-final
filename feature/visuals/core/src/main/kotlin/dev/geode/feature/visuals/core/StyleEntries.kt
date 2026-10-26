package dev.geode.feature.visuals.core

import dev.geode.engine.types.MilkdropStyles
import dev.geode.engine.types.SceneId
import dev.geode.feature.customization.api.StyleFamily
import dev.geode.feature.customization.api.StyleManifest
import dev.geode.feature.visuals.api.MilkdropPreset
import dev.geode.feature.visuals.api.StyleEntry
import dev.geode.feature.visuals.api.StyleGroup

/** The Visuals tab's list of Styles, made from the manifests of the Styles this version draws. */
object StyleEntries {
    /** One entry per manifest, group by group in [StyleGroup]'s order, each group in the manifests' order. */
    fun from(manifests: List<StyleManifest>): List<StyleEntry> =
        manifests
            .map { StyleEntry(it.id, name(it.id), group(it.family)) }
            .sortedBy { it.group.ordinal }

    /**
     * [styles] with every one of [presets] listed as a Style of its own in the MilkDrop group (#305), by
     * the name the MilkDrop list shows and in its order. They take the place of the MilkDrop Styles
     * listed before: the presets of an earlier listing, and the plain MilkDrop Style, which plays no
     * preset of its own. With no presets at all, only an earlier listing's presets go.
     */
    fun withMilkdrop(
        styles: List<StyleEntry>,
        presets: List<MilkdropPreset>,
    ): List<StyleEntry> {
        if (presets.isEmpty()) return styles.filter { MilkdropStyles.file(it.id) == null }
        val milkdrop = presets.map { StyleEntry(MilkdropStyles.of(it.file), it.name, StyleGroup.MILKDROP) }
        // The MilkDrop group is the last one listed (StyleGroup's order), so the presets go at the end.
        return styles.filterNot { it.group == StyleGroup.MILKDROP } + milkdrop
    }

    /**
     * The name a Style is shown by, made from its id ("fluid_ink" is "Fluid Ink") until the Style
     * packs carry names of their own.
     */
    fun name(id: SceneId): String =
        id.value
            .split('_')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { word -> word.replaceFirstChar { it.uppercaseChar() } }

    private fun group(family: StyleFamily): StyleGroup =
        when (family) {
            StyleFamily.SHADER -> StyleGroup.SHADERS
            StyleFamily.LIFE -> StyleGroup.LIFE
            StyleFamily.MYCELIUM -> StyleGroup.MYCELIUM
            StyleFamily.ACID -> StyleGroup.ACID
            StyleFamily.FLUID -> StyleGroup.FLUIDS
            StyleFamily.CURL_FLOW -> StyleGroup.CURL_FLOW
            StyleFamily.WATER -> StyleGroup.WATER
            StyleFamily.CYMATICS -> StyleGroup.CYMATICS
            StyleFamily.MILKDROP -> StyleGroup.MILKDROP
        }
}
