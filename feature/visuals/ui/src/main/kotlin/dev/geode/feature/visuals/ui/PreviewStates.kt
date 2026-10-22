package dev.geode.feature.visuals.ui

import dev.geode.engine.types.SceneId
import dev.geode.feature.visuals.api.Folder
import dev.geode.feature.visuals.api.FolderId
import dev.geode.feature.visuals.api.StyleEntry
import dev.geode.feature.visuals.api.StyleGroup
import dev.geode.feature.visuals.api.StyleLibrary
import dev.geode.feature.visuals.api.VisualsState

/** The Visuals states the previews and screenshot tests show. */
internal object PreviewStates {
    private val tunnel = StyleEntry(SceneId("tunnel"), "Tunnel", StyleGroup.SHADERS)
    private val bars = StyleEntry(SceneId("bars"), "Bars", StyleGroup.SHADERS)
    private val ink = StyleEntry(SceneId("fluid_ink"), "Fluid Ink", StyleGroup.FLUIDS)
    private val aurora = StyleEntry(SceneId("fluid_aurora"), "Fluid Aurora", StyleGroup.FLUIDS)
    private val chladni = StyleEntry(SceneId("chladni_sand"), "Chladni Sand", StyleGroup.CYMATICS)
    private val milk = StyleEntry(SceneId("milkdrop"), "Milkdrop", StyleGroup.MILKDROP)

    private val calm = Folder(FolderId(1), "Calm evenings", null, listOf(aurora.id, chladni.id))
    private val deep = Folder(FolderId(2), "Deep blue", calm.id, listOf(ink.id))
    private val party = Folder(FolderId(3), "Party", null, emptyList())

    val ready =
        VisualsState(
            styles = listOf(tunnel, bars, ink, aurora, chladni, milk),
            current = ink.id,
            library =
                StyleLibrary(
                    favourites = listOf(milk.id, ink.id),
                    recent = listOf(ink.id, tunnel.id),
                    folders = listOf(calm, deep, party),
                ),
        )

    val loading = ready.copy(current = null, library = null)

    val calmFolder: FolderId = calm.id
}
