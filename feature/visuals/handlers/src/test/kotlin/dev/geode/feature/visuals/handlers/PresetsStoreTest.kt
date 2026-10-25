package dev.geode.feature.visuals.handlers

import dev.geode.engine.types.PresetId
import dev.geode.engine.types.SceneId
import dev.geode.feature.customization.api.CustomizationCommand
import dev.geode.feature.customization.api.StyleFamily
import dev.geode.feature.visuals.api.BuiltInPreset
import dev.geode.feature.visuals.api.ImportProblem
import dev.geode.feature.visuals.api.PresetFolderPath
import dev.geode.feature.visuals.api.PresetShare
import dev.geode.feature.visuals.api.PresetShelf
import dev.geode.feature.visuals.api.SavedPreset
import dev.geode.feature.visuals.api.Visuals
import dev.geode.feature.visuals.api.VisualsCommand
import dev.geode.feature.visuals.api.VisualsFact
import dev.geode.feature.visuals.core.PresetLinks
import dev.geode.feature.visuals.core.VISUALS_FEATURE
import dev.geode.feature.visuals.core.WorkFailure
import dev.geode.kernel.FeatureFailure
import dev.geode.kernel.inPlaceRuntime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.random.Random

/**
 * The Presets browser (#144) end to end over a fake Customization and preset files in memory: the
 * presets listed, applied through Customization, changed on the phone, shared as links or files, and
 * imported from links and files, with failures on the app-wide error channel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PresetsStoreTest {
    private val tunnel = SceneId("tunnel")
    private val ink = SceneId("fluid_ink")
    private val manifests = listOf(manifest(tunnel, StyleFamily.SHADER), manifest(ink, StyleFamily.FLUID))

    private val calmId = PresetId("Calm")
    private val chillId = PresetId("Tunnel · Chill")
    private val night = PresetFolderPath("Night")
    private val calm = SavedPreset(calmId, tunnel)
    private val deep = SavedPreset(PresetId("Deep"), ink, night)
    private val chill = presetOf(chillId.value, tunnel)

    private val customization = FakeCustomization(tunnel)
    private val files =
        FakePresetFiles(
            builtIns = listOf(chill),
            saved = listOf(deep, calm),
            folders = listOf(night),
            documents = mapOf("content://shared" to "Calm|fluid_ink", "content://photo" to "not a preset"),
        )
    private val failures = mutableListOf<FeatureFailure>()

    private fun store(): Visuals =
        visualsStore(
            customization,
            FakeVisualsStorage(),
            files,
            manifests,
            FakeMilkdropFolder(),
            FakePickedFiles(emptyMap()),
            emptyList(),
            inPlaceRuntime(failures = failures),
        )

    private fun failure(failure: WorkFailure) = FeatureFailure(VISUALS_FEATURE, failure.message)

    /** Runs [block] on a store, collecting the facts it announces. */
    private fun withFacts(block: TestScope.(Visuals, List<VisualsFact>) -> Unit) =
        runTest(UnconfinedTestDispatcher()) {
            val visuals = store()
            val facts = mutableListOf<VisualsFact>()
            val listening = launch { visuals.events.toList(facts) }
            block(visuals, facts)
            listening.cancel()
        }

    @Test
    fun `the store lists the built-in and saved presets`() {
        val visuals = store()

        assertEquals(
            PresetShelf(builtIn = listOf(BuiltInPreset(chillId, tunnel)), saved = listOf(calm, deep), folders = listOf(night)),
            visuals.state.value.presets,
        )
        assertEquals(emptyList<FeatureFailure>(), failures)
    }

    @Test
    fun `a saved preset is loaded by Customization, a built-in one is handed to it`() {
        val visuals = store()

        visuals.send(VisualsCommand.ApplyPreset(calmId))
        visuals.send(VisualsCommand.ApplyPreset(chillId))

        assertEquals(listOf(CustomizationCommand.LoadPreset(calmId), CustomizationCommand.ApplyPreset(chill)), customization.sent)
    }

    @Test
    fun `a preset Customization cannot take is reported`() {
        val visuals = store()
        customization.failNextSend = IllegalStateException("engine gone")

        visuals.send(VisualsCommand.ApplyPreset(calmId))

        assertEquals(listOf(failure(WorkFailure.PRESET_NOT_APPLIED)), failures)
    }

    @Test
    fun `renames, moves, deletions and folders reach the files`() {
        val visuals = store()

        visuals.send(VisualsCommand.CreatePresetFolder("Party"))
        visuals.send(VisualsCommand.MovePreset(calmId, PresetFolderPath("Party")))
        visuals.send(VisualsCommand.RenamePreset(calmId, "Quiet"))
        visuals.send(VisualsCommand.RenamePresetFolder(night, "Evening"))
        visuals.send(VisualsCommand.DeletePreset(PresetId("Deep")))
        visuals.send(VisualsCommand.DeletePresetFolder(PresetFolderPath("Evening")))

        assertEquals(listOf(SavedPreset(PresetId("Quiet"), tunnel, PresetFolderPath("Party"))), files.presets)
        assertEquals(listOf(PresetFolderPath("Party")), files.folders)
        assertEquals(PresetShelf(listOf(BuiltInPreset(chillId, tunnel)), files.presets, files.folders), visuals.state.value.presets)
        assertEquals(emptyList<FeatureFailure>(), failures)
    }

    @Test
    fun `a change the files refuse is reported, and the presets are listed again as they are`() {
        val visuals = store()
        files.failure = IOException("disk full")

        visuals.send(VisualsCommand.DeletePreset(calmId))

        // The listing failed too while the disk stayed broken; once it works, a reload shows the truth.
        files.failure = null
        visuals.send(VisualsCommand.ReloadPresets)
        assertEquals(
            listOf(calm, deep),
            visuals.state.value.presets
                ?.saved,
        )
        assertEquals(listOf(failure(WorkFailure.PRESET_NOT_CHANGED), failure(WorkFailure.PRESETS_NOT_LOADED)), failures)
    }

    @Test
    fun `presets that cannot be listed are reported`() {
        files.failure = IOException("folder gone")

        val visuals = store()

        assertNull(visuals.state.value.presets)
        assertEquals(listOf(failure(WorkFailure.PRESETS_NOT_LOADED)), failures)
    }

    @Test
    fun `a small preset is shared as a link that imports back`() =
        withFacts { visuals, facts ->
            visuals.send(VisualsCommand.SharePreset(calmId))

            val share = facts.single() as VisualsFact.PresetShareReady
            val link = (share.share as PresetShare.Link).link
            assertEquals("Calm|tunnel", PresetLinks.decode(link))
        }

    @Test
    fun `a preset too big for a link is shared as its file`() =
        withFacts { visuals, facts ->
            val random = Random(SEED)
            files.texts[calmId] = (1..BIG).map { 'a' + random.nextInt(ALPHABET) }.joinToString("")

            visuals.send(VisualsCommand.SharePreset(calmId))

            assertEquals(listOf<VisualsFact>(VisualsFact.PresetShareReady(PresetShare.File(calmId))), facts)
        }

    @Test
    fun `a preset that cannot be read to share is reported`() =
        withFacts { visuals, facts ->
            files.failure = IOException("unreadable")

            visuals.send(VisualsCommand.SharePreset(calmId))

            assertEquals(emptyList<VisualsFact>(), facts)
            assertEquals(listOf(failure(WorkFailure.PRESET_NOT_SHARED)), failures)
        }

    @Test
    fun `an imported link is saved under a free name and announced`() =
        withFacts { visuals, facts ->
            visuals.send(VisualsCommand.ImportPresetLink("Try this: ${PresetLinks.encode("Calm|fluid_ink")}"))

            val imported = SavedPreset(PresetId("Calm 2"), ink)
            assertEquals(listOf<VisualsFact>(VisualsFact.PresetImported(imported.id)), facts)
            assertTrue(imported in files.presets)
            assertEquals(
                listOf(calm, imported, deep),
                visuals.state.value.presets
                    ?.saved,
            )
        }

    @Test
    fun `a link that is broken, or not a preset, is not imported`() =
        withFacts { visuals, facts ->
            visuals.send(VisualsCommand.ImportPresetLink("geode://preset/broken"))
            visuals.send(VisualsCommand.ImportPresetLink(PresetLinks.encode("hello")))
            visuals.send(VisualsCommand.ImportPresetLink("no link at all"))

            assertEquals(
                listOf(
                    VisualsFact.PresetNotImported(ImportProblem.NOT_A_PRESET),
                    VisualsFact.PresetNotImported(ImportProblem.NOT_A_PRESET),
                    VisualsFact.PresetNotImported(ImportProblem.NO_LINK),
                ),
                facts,
            )
            assertEquals(2, files.presets.size)
        }

    @Test
    fun `a preset file is imported, and a file that is not a preset is not`() =
        withFacts { visuals, facts ->
            visuals.send(VisualsCommand.ImportPresetFile("content://shared"))
            visuals.send(VisualsCommand.ImportPresetFile("content://photo"))

            assertEquals(
                listOf(VisualsFact.PresetImported(PresetId("Calm 2")), VisualsFact.PresetNotImported(ImportProblem.NOT_A_PRESET)),
                facts,
            )
        }

    @Test
    fun `a file that cannot be read is reported`() =
        withFacts { visuals, facts ->
            visuals.send(VisualsCommand.ImportPresetFile("content://gone"))

            assertEquals(emptyList<VisualsFact>(), facts)
            assertEquals(listOf(failure(WorkFailure.PRESET_NOT_IMPORTED)), failures)
        }

    private companion object {
        const val SEED = 144
        const val ALPHABET = 26
        const val BIG = 20_000
    }
}
