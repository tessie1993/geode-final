package dev.geode.feature.visuals.handlers

import dev.geode.engine.types.SceneId
import dev.geode.feature.customization.api.CustomizationCommand
import dev.geode.feature.customization.api.StyleFamily
import dev.geode.feature.visuals.api.Folder
import dev.geode.feature.visuals.api.FolderId
import dev.geode.feature.visuals.api.StyleEntry
import dev.geode.feature.visuals.api.StyleGroup
import dev.geode.feature.visuals.api.StyleLibrary
import dev.geode.feature.visuals.api.VisualsCommand
import dev.geode.feature.visuals.api.VisualsFact
import dev.geode.feature.visuals.core.VISUALS_FEATURE
import dev.geode.feature.visuals.core.WorkFailure
import dev.geode.kernel.FeatureFailure
import dev.geode.kernel.inPlaceRuntime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Visuals end to end over a fake Customization and storage in memory: Commands in, Customization
 * asked, what it shows followed back into Recent, the library read and saved, failures on the
 * app-wide error channel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VisualsStoreTest {
    private val tunnel = SceneId("tunnel")
    private val ink = SceneId("fluid_ink")
    private val milk = SceneId("milkdrop")
    private val manifests =
        listOf(manifest(milk, StyleFamily.MILKDROP), manifest(tunnel, StyleFamily.SHADER), manifest(ink, StyleFamily.FLUID))

    private val customization = FakeCustomization(tunnel)
    private val storage = FakeVisualsStorage(StyleLibrary(favourites = listOf(milk), recent = listOf(tunnel)))
    private val failures = mutableListOf<FeatureFailure>()

    private fun store() =
        visualsStore(
            customization,
            storage,
            FakePresetFiles(),
            manifests,
            FakeMilkdropFolder(),
            FakePickedFiles(emptyMap()),
            emptyList(),
            inPlaceRuntime(failures = failures),
        )

    @Test
    fun `the store lists the Styles, follows Customization and reads the library`() {
        val visuals = store()

        assertEquals(
            listOf(
                StyleEntry(tunnel, "Tunnel", StyleGroup.SHADERS),
                StyleEntry(ink, "Fluid Ink", StyleGroup.FLUIDS),
                StyleEntry(milk, "Milkdrop", StyleGroup.MILKDROP),
            ),
            visuals.state.value.styles,
        )
        assertEquals(tunnel, visuals.state.value.current)
        assertEquals(StyleLibrary(favourites = listOf(milk), recent = listOf(tunnel)), visuals.state.value.library)
        assertEquals(0, storage.saves)
    }

    @Test
    fun `choosing asks Customization, and the Style it shows goes to the front of Recent`() {
        val visuals = store()

        visuals.send(VisualsCommand.Choose(ink))

        assertEquals(listOf<CustomizationCommand>(CustomizationCommand.ChooseStyle(ink)), customization.sent)
        assertEquals(ink, visuals.state.value.current)
        assertEquals(StyleLibrary(favourites = listOf(milk), recent = listOf(ink, tunnel)), storage.saved)
    }

    @Test
    fun `a Style Customization shows by itself is followed too`() {
        val visuals = store()

        customization.show(milk)

        assertEquals(milk, visuals.state.value.current)
        assertEquals(listOf(milk, tunnel), storage.saved?.recent)
        assertEquals(emptyList<CustomizationCommand>(), customization.sent)
    }

    @Test
    fun `every edit is saved, and a new folder is announced`() =
        runTest(UnconfinedTestDispatcher()) {
            val visuals = store()
            val facts = mutableListOf<VisualsFact>()
            val listening = launch { visuals.events.toList(facts) }

            visuals.send(VisualsCommand.SetFavourite(ink, true))
            visuals.send(VisualsCommand.CreateFolder("Calm"))
            visuals.send(VisualsCommand.AddToFolder(FolderId(1), tunnel))

            assertEquals(
                StyleLibrary(listOf(ink, milk), listOf(tunnel), listOf(Folder(FolderId(1), "Calm", null, listOf(tunnel)))),
                storage.saved,
            )
            assertEquals(3, storage.saves)
            assertEquals(listOf<VisualsFact>(VisualsFact.FolderCreated(FolderId(1))), facts)
            listening.cancel()
        }

    @Test
    fun `a library that cannot be read is reported and never saved over`() {
        storage.failLoad = true
        val visuals = store()

        visuals.send(VisualsCommand.SetFavourite(ink, true))
        customization.show(ink)

        assertEquals(null, visuals.state.value.library)
        assertEquals(0, storage.saves)
        assertEquals(listOf(FeatureFailure(VISUALS_FEATURE, WorkFailure.LIBRARY_NOT_LOADED.message)), failures)
    }

    @Test
    fun `a save that fails is reported and the edit stays`() {
        val visuals = store()
        storage.failSave = true

        visuals.send(VisualsCommand.SetFavourite(ink, true))

        assertEquals(
            listOf(ink, milk),
            visuals.state.value.library
                ?.favourites,
        )
        assertEquals(listOf(FeatureFailure(VISUALS_FEATURE, WorkFailure.LIBRARY_NOT_SAVED.message)), failures)
    }

    @Test
    fun `a Customization that refuses is reported`() {
        val visuals = store()
        customization.failNextSend = IllegalStateException("engine gone")

        visuals.send(VisualsCommand.Choose(ink))

        assertEquals(tunnel, visuals.state.value.current)
        assertEquals(listOf(FeatureFailure(VISUALS_FEATURE, WorkFailure.STYLE_NOT_CHOSEN.message)), failures)
    }
}
