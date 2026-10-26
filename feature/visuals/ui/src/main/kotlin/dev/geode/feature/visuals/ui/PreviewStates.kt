package dev.geode.feature.visuals.ui

import dev.geode.engine.types.PresetId
import dev.geode.engine.types.SceneId
import dev.geode.feature.visuals.api.BrokenFile
import dev.geode.feature.visuals.api.BuiltInPreset
import dev.geode.feature.visuals.api.Folder
import dev.geode.feature.visuals.api.FolderId
import dev.geode.feature.visuals.api.ImportReport
import dev.geode.feature.visuals.api.MilkdropImportProblem
import dev.geode.feature.visuals.api.MilkdropKind
import dev.geode.feature.visuals.api.MilkdropPreset
import dev.geode.feature.visuals.api.MilkdropShelf
import dev.geode.feature.visuals.api.PackLimit
import dev.geode.feature.visuals.api.PackProblem
import dev.geode.feature.visuals.api.PresetFolderPath
import dev.geode.feature.visuals.api.PresetPlayback
import dev.geode.feature.visuals.api.PresetShelf
import dev.geode.feature.visuals.api.SavedPreset
import dev.geode.feature.visuals.api.SkippedEntry
import dev.geode.feature.visuals.api.StyleEntry
import dev.geode.feature.visuals.api.StyleGroup
import dev.geode.feature.visuals.api.StyleLibrary
import dev.geode.feature.visuals.api.VisualsState

/** The Visuals states the previews show. */
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

    private val calmPresets = PresetFolderPath("Calm")
    private val rainy = PresetFolderPath("Calm/Rainy days")
    private val partyPresets = PresetFolderPath("Party")

    /** Built-in presets for three Styles, saved ones at the top and in nested folders. */
    val shelf =
        PresetShelf(
            builtIn =
                listOf(
                    BuiltInPreset(PresetId("Fluid Ink · Calm wash"), ink.id),
                    BuiltInPreset(PresetId("Fluid Ink · Neon hits"), ink.id),
                    BuiltInPreset(PresetId("Tunnel · Chill"), tunnel.id),
                    BuiltInPreset(PresetId("Tunnel · Punchy"), tunnel.id),
                    BuiltInPreset(PresetId("Bars · Retro"), bars.id),
                ),
            saved =
                listOf(
                    SavedPreset(PresetId("Bass heavy"), tunnel.id, partyPresets),
                    SavedPreset(PresetId("Late night drive"), ink.id),
                    SavedPreset(PresetId("Rain on glass"), ink.id, rainy),
                    SavedPreset(PresetId("Slow burn"), chladni.id, calmPresets),
                    SavedPreset(PresetId("Sunday morning"), aurora.id),
                ),
            folders = listOf(calmPresets, rainy, partyPresets),
        )

    val calmPresetFolder: PresetFolderPath = calmPresets

    val tunnelStyle: SceneId = tunnel.id

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
            presets = shelf,
        )

    val loading = ready.copy(current = null, library = null, presets = null)

    val calmFolder: FolderId = calm.id

    private val milkdropShelf =
        MilkdropShelf(
            presets =
                listOf(
                    MilkdropPreset("Geiss - Artifact Plasma.milk", "Artifact Plasma", "Geiss", builtIn = true),
                    MilkdropPreset("aurora_veil.milk", "Aurora Veil", "Geode", builtIn = true),
                    MilkdropPreset("Beach at dusk.milk", "Beach at dusk", null, builtIn = false),
                    MilkdropPreset("martin - deep blue.milk", "Deep Blue", "martin", builtIn = true),
                ),
            textures = listOf("clouds.png", "worms.jpg"),
            lastImport =
                ImportReport(
                    MilkdropKind.PRESETS,
                    imported = listOf("Beach at dusk"),
                    broken =
                        listOf(
                            BrokenFile("shopping list.milk", MilkdropImportProblem.NOT_A_PRESET),
                            BrokenFile("aurora_veil.milk", MilkdropImportProblem.ALREADY_THERE),
                        ),
                ),
        )

    /** MilkDrop listed, just after an import that left two files out. */
    val milkdrop = ready.copy(milkdrop = milkdropShelf)

    /** MilkDrop just after a zip pack came in (#399): a texture along with its presets, a readme passed over, and its size limit reached. */
    val milkdropPack =
        ready.copy(
            milkdrop =
                milkdropShelf.copy(
                    lastImport =
                        ImportReport(
                            MilkdropKind.PRESETS,
                            imported = listOf("Beach at dusk"),
                            broken = listOf(BrokenFile("Mix/aurora_veil.milk", MilkdropImportProblem.ALREADY_THERE)),
                            playback = mapOf("Beach at dusk" to PresetPlayback.Full),
                            textures = listOf("worms.jpg"),
                            skipped = listOf(SkippedEntry("Mix/readme.txt", PackProblem.NOT_MILKDROP)),
                            stopped = PackLimit.SIZE,
                        ),
                ),
        )

    /** MilkDrop before its folder has been listed. */
    val milkdropLoading = ready

    /** An import running, with no textures yet. */
    val milkdropImporting = ready.copy(milkdrop = milkdropShelf.copy(textures = emptyList(), importing = true, lastImport = null))
}
