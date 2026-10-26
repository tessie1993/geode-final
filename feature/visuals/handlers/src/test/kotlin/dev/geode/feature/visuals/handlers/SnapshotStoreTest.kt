package dev.geode.feature.visuals.handlers

import dev.geode.engine.types.SceneId
import dev.geode.feature.customization.api.StyleFamily
import dev.geode.feature.visuals.api.SnapshotProblem
import dev.geode.feature.visuals.api.StyleLibrary
import dev.geode.feature.visuals.api.Visuals
import dev.geode.feature.visuals.api.VisualsCommand
import dev.geode.feature.visuals.api.VisualsFact
import dev.geode.kernel.FeatureFailure
import dev.geode.kernel.inPlaceRuntime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * Snapshots end to end (#405): the Command reaches the camera with the file's name, one at a time,
 * and how it went is announced; nothing about a Snapshot goes to the app-wide error channel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SnapshotStoreTest {
    private val tunnel = SceneId("tunnel")
    private val at = LocalDateTime.of(2026, 10, 26, 14, 3, 22)
    private val name = "Tunnel (Geode Snapshot 2026-10-26 14-03-22).png"
    private val failures = mutableListOf<FeatureFailure>()
    private val asked = mutableListOf<String>()

    private fun store(camera: SnapshotCamera?) =
        if (camera == null) {
            visualsStore(
                FakeCustomization(tunnel),
                FakeVisualsStorage(StyleLibrary()),
                FakePresetFiles(),
                listOf(manifest(tunnel, StyleFamily.SHADER)),
                FakeMilkdropFolder(),
                FakePickedFiles(emptyMap()),
                emptyList(),
                inPlaceRuntime(failures = failures),
            )
        } else {
            visualsStore(
                FakeCustomization(tunnel),
                FakeVisualsStorage(StyleLibrary()),
                FakePresetFiles(),
                listOf(manifest(tunnel, StyleFamily.SHADER)),
                FakeMilkdropFolder(),
                FakePickedFiles(emptyMap()),
                emptyList(),
                inPlaceRuntime(failures = failures),
                snapshots = camera,
                clock = { at },
            )
        }

    private fun facts(
        camera: SnapshotCamera?,
        block: suspend (Visuals) -> Unit,
    ): List<VisualsFact> {
        val facts = mutableListOf<VisualsFact>()
        runTest(UnconfinedTestDispatcher()) {
            val visuals = store(camera)
            val listening = launch { visuals.events.toList(facts) }
            block(visuals)
            listening.cancel()
        }
        return facts
    }

    @Test
    fun `a Snapshot is saved under the Style's name and the time, and announced`() {
        val facts =
            facts(
                SnapshotCamera { fileName ->
                    asked += fileName
                    null
                },
            ) { it.send(VisualsCommand.Snapshot) }

        assertEquals(listOf(name), asked)
        assertEquals(listOf<VisualsFact>(VisualsFact.SnapshotSaved(name)), facts)
        assertEquals(emptyList<FeatureFailure>(), failures)
    }

    @Test
    fun `a camera that says why is announced as not saved`() {
        val facts = facts(SnapshotCamera { SnapshotProblem.NO_VISUAL }) { it.send(VisualsCommand.Snapshot) }

        assertEquals(listOf<VisualsFact>(VisualsFact.SnapshotNotSaved(SnapshotProblem.NO_VISUAL)), facts)
        assertEquals(emptyList<FeatureFailure>(), failures)
    }

    @Test
    fun `a camera that throws is a Snapshot not saved`() {
        val facts = facts(SnapshotCamera { throw IllegalStateException("no picture") }) { it.send(VisualsCommand.Snapshot) }

        assertEquals(listOf<VisualsFact>(VisualsFact.SnapshotNotSaved(SnapshotProblem.NOT_SAVED)), facts)
        assertEquals(emptyList<FeatureFailure>(), failures)
    }

    @Test
    fun `without a camera there is no Visual to take a still of`() {
        val facts = facts(null) { it.send(VisualsCommand.Snapshot) }

        assertEquals(listOf<VisualsFact>(VisualsFact.SnapshotNotSaved(SnapshotProblem.NO_VISUAL)), facts)
    }

    @Test
    fun `one Snapshot at a time, and the next may start once it is saved`() {
        val developing = CompletableDeferred<SnapshotProblem?>()
        val facts =
            facts(
                SnapshotCamera { fileName ->
                    asked += fileName
                    developing.await()
                },
            ) { visuals ->
                visuals.send(VisualsCommand.Snapshot)
                assertTrue(visuals.state.value.snapshotting)
                visuals.send(VisualsCommand.Snapshot)
                assertEquals(1, asked.size)
                developing.complete(null)
                assertFalse(visuals.state.value.snapshotting)
                visuals.send(VisualsCommand.Snapshot)
            }

        assertEquals(listOf(name, name), asked)
        assertEquals(listOf<VisualsFact>(VisualsFact.SnapshotSaved(name), VisualsFact.SnapshotSaved(name)), facts)
    }
}
