package dev.geode.feature.visuals.handlers

import dev.geode.engine.types.MilkdropStyles
import dev.geode.engine.types.SceneId
import dev.geode.feature.customization.api.StyleFamily
import dev.geode.feature.visuals.api.BrokenFile
import dev.geode.feature.visuals.api.Folder
import dev.geode.feature.visuals.api.FolderId
import dev.geode.feature.visuals.api.ImportReport
import dev.geode.feature.visuals.api.MilkdropImportProblem
import dev.geode.feature.visuals.api.MilkdropKind
import dev.geode.feature.visuals.api.MilkdropPackType
import dev.geode.feature.visuals.api.PackLimit
import dev.geode.feature.visuals.api.PackProblem
import dev.geode.feature.visuals.api.PresetPlayback
import dev.geode.feature.visuals.api.SkippedEntry
import dev.geode.feature.visuals.api.VisualsCommand
import dev.geode.feature.visuals.core.MilkdropFiles
import dev.geode.kernel.FeatureFailure
import dev.geode.kernel.inPlaceRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * A whole pack of MilkDrop presets end to end (#399), over a folder in memory and fake packs: its
 * presets and textures in, the rest passed over and said, its presets filed in folders of the library.
 */
class MilkdropPackStoreTest {
    private val milk = SceneId("milkdrop")
    private val preset = "[preset00]\nfDecay=0.97\n".toByteArray()
    private val sampling = "[preset00]\nwarp_1=`ret = tex2D(sampler_worms, uv).xyz;\n".toByteArray()
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())

    private val folder = FakeMilkdropFolder(presets = listOf("beach.milk"), textures = listOf("clouds.png"))
    private val failures = mutableListOf<FeatureFailure>()

    private fun store(packs: PickedPacks) =
        visualsStore(
            FakeCustomization(milk),
            FakeVisualsStorage(),
            FakePresetFiles(),
            listOf(manifest(milk, StyleFamily.MILKDROP)),
            folder,
            FakePickedFiles(emptyMap()),
            packs,
            emptyList(),
            inPlaceRuntime(failures = failures),
        )

    private val zip = "content://downloads/1"
    private val mix =
        FakePack(
            "Mix.zip",
            listOf(
                "Mix/" to null,
                "Mix/readme.txt" to "hello".toByteArray(),
                "Mix/Geiss/worms.milk" to sampling,
                "Mix/top.milk" to preset,
                "Mix/textures/worms.png" to png,
                "../evil.milk" to preset,
                "Mix/.DS_Store" to null,
                "Mix/beach.milk" to preset,
                "Mix/notes.milk" to "just notes".toByteArray(),
                "Mix/gone.milk" to null,
            ),
        )

    @Test
    fun `a pack's presets and textures come in, and everything else is said`() {
        val packs = FakePickedPacks(mapOf(zip to mix))
        val visuals = store(packs)

        visuals.send(VisualsCommand.ImportMilkdropPack(MilkdropPackType.ZIP, zip))

        val shelf = checkNotNull(visuals.state.value.milkdrop)
        assertEquals(
            ImportReport(
                MilkdropKind.PRESETS,
                listOf("worms", "top"),
                listOf(
                    BrokenFile("Mix/beach.milk", MilkdropImportProblem.ALREADY_THERE),
                    BrokenFile("Mix/notes.milk", MilkdropImportProblem.NOT_A_PRESET),
                    BrokenFile("Mix/gone.milk", MilkdropImportProblem.UNREADABLE),
                ),
                // The texture came after the preset that samples it, and still counts.
                mapOf("worms" to PresetPlayback.Full, "top" to PresetPlayback.Full),
                textures = listOf("worms.png"),
                skipped =
                    listOf(
                        SkippedEntry("Mix/readme.txt", PackProblem.NOT_MILKDROP),
                        SkippedEntry("../evil.milk", PackProblem.UNSAFE_PATH),
                    ),
            ),
            shelf.lastImport,
        )
        assertFalse(shelf.importing)
        assertEquals(
            listOf("beach.milk", "worms.milk", "top.milk"),
            folder.stored
                .getValue(MilkdropKind.PRESETS)
                .keys
                .toList(),
        )
        assertEquals(listOf("clouds.png", "worms.png"), shelf.textures)
        assertEquals(1, folder.batches)
        // Each file was read within its own kind's limit.
        assertEquals(
            listOf(MilkdropFiles.MAX_PRESET_BYTES, MilkdropFiles.MAX_PRESET_BYTES, MilkdropFiles.MAX_TEXTURE_BYTES),
            packs.limits.take(3),
        )
    }

    @Test
    fun `its presets are filed in folders named for the pack's`() {
        val visuals = store(FakePickedPacks(mapOf(zip to mix)))

        visuals.send(VisualsCommand.ImportMilkdropPack(MilkdropPackType.ZIP, zip))

        val one = FolderId(1)
        assertEquals(
            listOf(
                Folder(one, "Mix", null, listOf(MilkdropStyles.of("top.milk"))),
                Folder(FolderId(2), "Geiss", one, listOf(MilkdropStyles.of("worms.milk"))),
            ),
            visuals.state.value.library
                ?.folders,
        )
    }

    @Test
    fun `presets loose in a picked folder are filed under the folder's name`() {
        val tree = "content://tree/primary%3AMilk"
        val pack = FakePack("Milk", listOf("a.milk" to preset, "Old/b.milk" to preset))
        val visuals = store(FakePickedPacks(mapOf(tree to pack)))

        visuals.send(VisualsCommand.ImportMilkdropPack(MilkdropPackType.FOLDER, tree))

        val one = FolderId(1)
        assertEquals(
            listOf(
                Folder(one, "Milk", null, listOf(MilkdropStyles.of("a.milk"))),
                Folder(FolderId(2), "Old", one, listOf(MilkdropStyles.of("b.milk"))),
            ),
            visuals.state.value.library
                ?.folders,
        )
    }

    @Test
    fun `a pack that runs into a limit keeps what came before and says so`() {
        val pack = FakePack("Big.zip", listOf("a.milk" to preset), stopped = PackLimit.ENTRIES)
        val visuals = store(FakePickedPacks(mapOf(zip to pack)))

        visuals.send(VisualsCommand.ImportMilkdropPack(MilkdropPackType.ZIP, zip))

        val report =
            checkNotNull(
                visuals.state.value.milkdrop
                    ?.lastImport,
            )
        assertEquals(listOf("a"), report.imported)
        assertEquals(PackLimit.ENTRIES, report.stopped)
    }

    @Test
    fun `a pack that breaks part way keeps what came before and is reported by its name`() {
        val pack = FakePack("Broken.zip", listOf("a.milk" to preset), breaks = true)
        val visuals = store(FakePickedPacks(mapOf(zip to pack)))

        visuals.send(VisualsCommand.ImportMilkdropPack(MilkdropPackType.ZIP, zip))

        val report =
            checkNotNull(
                visuals.state.value.milkdrop
                    ?.lastImport,
            )
        assertEquals(listOf("a"), report.imported)
        assertEquals(listOf(BrokenFile("Broken.zip", MilkdropImportProblem.UNREADABLE)), report.broken)
        assertEquals(emptyList<FeatureFailure>(), failures)
    }

    @Test
    fun `a pack that cannot be read at all is reported, and the next import may start`() {
        val visuals = store(FakePickedPacks())

        visuals.send(VisualsCommand.ImportMilkdropPack(MilkdropPackType.ZIP, "content://downloads/lost.zip"))

        val shelf = checkNotNull(visuals.state.value.milkdrop)
        assertEquals(
            ImportReport(MilkdropKind.PRESETS, emptyList(), listOf(BrokenFile("lost.zip", MilkdropImportProblem.UNREADABLE))),
            shelf.lastImport,
        )
        assertFalse(shelf.importing)
    }

    @Test
    fun `a file that cannot be written is reported and the rest still come in`() {
        folder.failWrite += "a.milk"
        val pack = FakePack("Mix.zip", listOf("Mix/a.milk" to preset, "Mix/b.milk" to preset))
        val visuals = store(FakePickedPacks(mapOf(zip to pack)))

        visuals.send(VisualsCommand.ImportMilkdropPack(MilkdropPackType.ZIP, zip))

        val report =
            checkNotNull(
                visuals.state.value.milkdrop
                    ?.lastImport,
            )
        assertEquals(listOf("b"), report.imported)
        assertEquals(listOf(BrokenFile("Mix/a.milk", MilkdropImportProblem.NOT_SAVED)), report.broken)
    }
}
