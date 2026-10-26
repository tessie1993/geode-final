package dev.geode.ui

import android.app.Application
import android.net.Uri
import dev.geode.analysis.AudioFeatures
import dev.geode.analysis.FeatureTimeline
import dev.geode.analysis.TimelineFrame
import dev.geode.data.ExportDefaults
import dev.geode.engine.bridge.CustomizationSnapshot
import dev.geode.export.DriftStop
import dev.geode.feature.customization.core.BuiltInStyles
import dev.geode.feature.customization.core.Defaults
import dev.geode.visuals.EngineSnapshots
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The export goes by the song's tempo from its own whole-song analysis (ADR-0001), never the live
 * Visual's, which knows none now that the playing song is never analysed (#17).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ExportTempoRobolectricTest {
    private val application: Application = RuntimeEnvironment.getApplication()
    private val song: Uri = Uri.parse("content://media/song")
    private val analysed = FeatureTimeline(listOf(TimelineFrame(0L, AudioFeatures.empty().copy(bpm = 128f))), hopMs = 16)
    private val analysis =
        TrackAnalysisController(
            application,
            object : TrackAnalysisController.Host {
                override val guiPrefs = GuiPrefs()
            },
        )
    private var analyses = 0

    private val host =
        object : ExportController.Host {
            override val exportUri: Uri? get() = song
            override var cachedTimeline: FeatureTimeline?
                get() = analysis.timeline
                set(value) {
                    analysis.timeline = value
                }

            override suspend fun analyze(
                uri: Uri,
                onProgress: (Float) -> Unit,
            ): FeatureTimeline {
                analyses++
                return analysed
            }

            override val guiPrefs = GuiPrefs()
            override val exportDefaults = ExportDefaults()

            override fun look(drift: DriftStop): CustomizationSnapshot =
                EngineSnapshots.forRender(Defaults.customization(BuiltInStyles.catalogue.manifestOf(BuiltInStyles.all[0].id)), null)
        }

    private val export =
        ExportController(application, CoroutineScope(UnconfinedTestDispatcher()), host, UnconfinedTestDispatcher())

    @Test
    fun `the export dialog gets the tempo the export analysis found`() {
        assertEquals(0f, analysis.tempo.value)

        export.prepareTempo()

        assertEquals(128f, analysis.tempo.value)
        assertEquals(1, analyses)
    }

    @Test
    fun `a song already analysed for export is not analysed again`() {
        export.prepareTempo()
        export.prepareTempo()

        assertEquals(1, analyses)
    }

    @Test
    fun `a new song's tempo is unknown until its own export analysis`() {
        export.prepareTempo()

        analysis.timeline = null

        assertEquals(0f, analysis.tempo.value)
    }
}
