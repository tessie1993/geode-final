package dev.geode.feature.visuals.handlers

import dev.geode.engine.types.SceneId
import dev.geode.feature.customization.api.StyleFamily
import dev.geode.feature.visuals.api.BrokenFile
import dev.geode.feature.visuals.api.ImportProblem
import dev.geode.feature.visuals.api.ImportReport
import dev.geode.feature.visuals.api.MilkdropKind
import dev.geode.feature.visuals.api.MilkdropPreset
import dev.geode.feature.visuals.api.MilkdropShelf
import dev.geode.feature.visuals.api.VisualsCommand
import dev.geode.feature.visuals.core.MilkdropFiles
import dev.geode.feature.visuals.core.PickedFile
import dev.geode.feature.visuals.core.VISUALS_FEATURE
import dev.geode.feature.visuals.core.WorkFailure
import dev.geode.kernel.FeatureFailure
import dev.geode.kernel.inPlaceRuntime
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * MilkDrop import end to end (#145) over a folder in memory and fake picked files: the folder listed
 * beside the built-in presets, good files written and listed with them, broken ones reported, and
 * nothing already there touched.
 */
class MilkdropStoreTest {
    private val milk = SceneId("milkdrop")
    private val preset = "[preset00]\nfDecay=0.97\n".toByteArray()
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())

    private val aurora = MilkdropPreset("aurora_veil.milk", "Aurora Veil", "Geode", builtIn = true)
    private val tidal = MilkdropPreset("tidal_drift.milk", "Tidal Drift", "Geode", builtIn = true)

    private val folder = FakeMilkdropFolder(presets = listOf("aurora_veil.milk", "beach.milk"), textures = listOf("clouds.png"))
    private val failures = mutableListOf<FeatureFailure>()

    private fun store(picked: PickedFiles = FakePickedFiles(emptyMap())) =
        visualsStore(
            FakeCustomization(milk),
            FakeVisualsStorage(),
            listOf(manifest(milk, StyleFamily.MILKDROP)),
            folder,
            picked,
            listOf(tidal, aurora),
            inPlaceRuntime(failures = failures),
        )

    private fun own(name: String) = MilkdropPreset("$name.milk", name, null, builtIn = false)

    @Test
    fun `the store lists the built-in presets with the person's own, and the textures`() {
        val visuals = store()

        assertEquals(MilkdropShelf(listOf(aurora, own("beach"), tidal), listOf("clouds.png")), visuals.state.value.milkdrop)
    }

    @Test
    fun `imported presets appear with the built-in ones, and broken ones are reported`() {
        val picked =
            FakePickedFiles(
                mapOf(
                    "content://1" to PickedFile.Read("Ocean.milk", preset),
                    "content://2" to PickedFile.Read("notes.milk", "just notes".toByteArray()),
                    "content://3" to PickedFile.Read("tidal_drift.milk", preset),
                    "content://4" to PickedFile.TooLarge("huge.milk"),
                    "content://5" to PickedFile.Read("Zen.milk", preset),
                ),
                throwing = setOf("content://6"),
            )
        val visuals = store(picked)

        visuals.send(VisualsCommand.ImportMilkdrop(MilkdropKind.PRESETS, (1..7).map { "content://$it" }))

        val shelf = checkNotNull(visuals.state.value.milkdrop)
        assertEquals(listOf(aurora, own("beach"), own("Ocean"), tidal, own("Zen")), shelf.presets)
        assertEquals(
            ImportReport(
                MilkdropKind.PRESETS,
                imported = listOf("Ocean", "Zen"),
                broken =
                    listOf(
                        BrokenFile("notes.milk", ImportProblem.NOT_A_PRESET),
                        BrokenFile("tidal_drift.milk", ImportProblem.ALREADY_THERE),
                        BrokenFile("huge.milk", ImportProblem.TOO_LARGE),
                        BrokenFile("6", ImportProblem.UNREADABLE),
                        BrokenFile("content://7", ImportProblem.UNREADABLE),
                    ),
            ),
            shelf.lastImport,
        )
        assertEquals(false, shelf.importing)
        assertArrayEquals(preset, folder.stored.getValue(MilkdropKind.PRESETS)["Ocean.milk"])
        assertEquals(setOf("aurora_veil.milk", "beach.milk", "Ocean.milk", "Zen.milk"), folder.stored.getValue(MilkdropKind.PRESETS).keys)
        assertEquals(List(7) { MilkdropFiles.MAX_PRESET_BYTES }, picked.limits)
        assertEquals(emptyList<FeatureFailure>(), failures)
    }

    @Test
    fun `imported textures appear in the list, and one already there is left alone`() {
        val picked =
            FakePickedFiles(
                mapOf(
                    "content://1" to PickedFile.Read("my sky.png", png),
                    "content://2" to PickedFile.Read("Clouds.png", png),
                    "content://3" to PickedFile.Read("fake.png", "text".toByteArray()),
                ),
            )
        val visuals = store(picked)

        visuals.send(VisualsCommand.ImportMilkdrop(MilkdropKind.TEXTURES, listOf("content://1", "content://2", "content://3")))

        val shelf = checkNotNull(visuals.state.value.milkdrop)
        assertEquals(listOf("clouds.png", "my_sky_ad221c51.png"), shelf.textures)
        assertEquals(
            ImportReport(
                MilkdropKind.TEXTURES,
                imported = listOf("my_sky_ad221c51.png"),
                broken = listOf(BrokenFile("Clouds.png", ImportProblem.ALREADY_THERE), BrokenFile("fake.png", ImportProblem.NOT_AN_IMAGE)),
            ),
            shelf.lastImport,
        )
        assertEquals(
            0,
            folder.stored
                .getValue(MilkdropKind.TEXTURES)
                .getValue("clouds.png")
                .size,
        )
        assertEquals(List(3) { MilkdropFiles.MAX_TEXTURE_BYTES }, picked.limits)
    }

    @Test
    fun `a file that cannot be written is reported, and the others still come in`() {
        folder.failWrite += "Ocean.milk"
        val picked =
            FakePickedFiles(
                mapOf("content://1" to PickedFile.Read("Ocean.milk", preset), "content://2" to PickedFile.Read("Zen.milk", preset)),
            )
        val visuals = store(picked)

        visuals.send(VisualsCommand.ImportMilkdrop(MilkdropKind.PRESETS, listOf("content://1", "content://2")))

        val report =
            visuals.state.value.milkdrop
                ?.lastImport
        assertEquals(listOf("Zen"), report?.imported)
        assertEquals(listOf(BrokenFile("Ocean.milk", ImportProblem.NOT_SAVED)), report?.broken)
    }

    @Test
    fun `a folder that cannot be listed is reported, and nothing can be imported`() {
        folder.failList = true
        val picked = FakePickedFiles(mapOf("content://1" to PickedFile.Read("Ocean.milk", preset)))
        val visuals = store(picked)

        visuals.send(VisualsCommand.ImportMilkdrop(MilkdropKind.PRESETS, listOf("content://1")))

        assertNull(visuals.state.value.milkdrop)
        assertEquals(emptyList<Int>(), picked.limits)
        assertEquals(listOf(FeatureFailure(VISUALS_FEATURE, WorkFailure.MILKDROP_NOT_LISTED.message)), failures)
    }

    @Test
    fun `an import that fails as a whole is reported, and the next one may start`() {
        val picked = FakePickedFiles(mapOf("content://1" to PickedFile.Read("Ocean.milk", preset)))
        val visuals = store(picked)
        folder.failList = true

        visuals.send(VisualsCommand.ImportMilkdrop(MilkdropKind.PRESETS, listOf("content://1")))

        assertEquals(
            false,
            visuals.state.value.milkdrop
                ?.importing,
        )
        assertEquals(listOf(FeatureFailure(VISUALS_FEATURE, WorkFailure.MILKDROP_NOT_IMPORTED.message)), failures)
        folder.failList = false
        visuals.send(VisualsCommand.ImportMilkdrop(MilkdropKind.PRESETS, listOf("content://1")))
        assertEquals(
            listOf("Ocean"),
            visuals.state.value.milkdrop
                ?.lastImport
                ?.imported,
        )
    }
}
