package dev.geode.feature.diagnostics.handlers

import dev.geode.engine.types.Frames
import dev.geode.engine.types.Micros
import dev.geode.engine.types.Millis
import dev.geode.feature.diagnostics.api.BufferSize
import dev.geode.feature.diagnostics.api.DeveloperState
import dev.geode.feature.diagnostics.api.DiagnosticsCommand
import dev.geode.feature.diagnostics.api.DiagnosticsFact
import dev.geode.feature.diagnostics.api.EngineRunState
import dev.geode.feature.diagnostics.api.EngineStats
import dev.geode.feature.diagnostics.api.OverlayState
import dev.geode.feature.diagnostics.api.ShareProblem
import dev.geode.feature.diagnostics.api.ShareState
import dev.geode.feature.diagnostics.api.UNLOCK_TAPS
import dev.geode.feature.diagnostics.core.FakeEngineStatsSource
import dev.geode.feature.diagnostics.core.FakeLogSource
import dev.geode.feature.diagnostics.core.FakeSettingsSource
import dev.geode.feature.diagnostics.core.FakeSystemFacts
import dev.geode.feature.diagnostics.core.ReportFile
import dev.geode.feature.diagnostics.core.Setting
import dev.geode.feature.diagnostics.core.SettingsSource
import dev.geode.kernel.FakeClock
import dev.geode.kernel.FeatureFailure
import dev.geode.kernel.inPlaceRuntime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Diagnostics end to end with fakes for the app's ports: the share writes the report and opens the
 * share sheet, failures land in the state and on the error channel, the taps unlock the developer
 * options, and the engine's stats flow only while the overlay shows them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsStoreTest {
    private val engine = FakeEngineStatsSource()
    private val log = FakeLogSource(listOf("1 Playback: started", "2 PresetStore: unreadable preset skipped: Road Trip.json"))
    private val settings = FakeSettingsSource(listOf(Setting("Theme", "Dark"), Setting("Music folder", "/storage/emulated/0/Music")))
    private val files = RecordingFiles()
    private val shareSheet = RecordingShareSheet()
    private val clock = FakeClock()
    private val failures = mutableListOf<FeatureFailure>()

    private fun store(
        reportFiles: ReportFiles = files,
        sheet: ShareSheet = shareSheet,
        settingsSource: SettingsSource = settings,
    ) =
        diagnosticsStore(
            DiagnosticsHandlers(FakeSystemFacts(), log, settingsSource, engine, reportFiles, sheet, clock = { Millis(REPORT_TIME) }),
            inPlaceRuntime(clock, failures),
        )

    @Test
    fun `sharing writes the report and opens the share sheet with it`() {
        val store = store()

        store.send(DiagnosticsCommand.Share)

        assertEquals(ShareState.Idle, store.state.value.share)
        assertEquals(listOf(ReportFile(SHARED_URI)), shareSheet.opened)
        assertEquals(0, clock.pendingCount)
        val report = files.written.single()
        assertTrue(report, report.startsWith("Geode diagnostics (format 1)\nGenerated at: $REPORT_TIME ms since 1970\n"))
        assertTrue(report, report.contains("App: 1.8.0 (32)\nDevice: Google Pixel 8\nAndroid: 15 (API 35)\n"))
        assertTrue(report, report.contains("  Theme: Dark\n  Music folder: <hidden>\n"))
        assertTrue(report, report.contains("  1 Playback: started\n  2 PresetStore: unreadable preset skipped: <file>\n"))
        assertFalse(report, report.contains("Road Trip") || report.contains("/storage"))
    }

    @Test
    fun `a file that cannot be written fails the share and is reported`() {
        val store = store(reportFiles = { throw IOException("no space left") })

        store.send(DiagnosticsCommand.Share)

        assertEquals(ShareState.Failed(ShareProblem.WRITE_FAILED), store.state.value.share)
        assertEquals(emptyList<ReportFile>(), shareSheet.opened)
        assertEquals(listOf("diagnostics: the diagnostics file could not be written"), failures.map { "${it.feature}: ${it.message}" })
        assertEquals(0, clock.pendingCount)
    }

    @Test
    fun `a share sheet nothing can receive fails the share`() {
        val store = store(sheet = { false })

        store.send(DiagnosticsCommand.Share)

        assertEquals(ShareState.Failed(ShareProblem.NO_SHARE_TARGET), store.state.value.share)
        store.send(DiagnosticsCommand.DismissProblem)
        assertEquals(ShareState.Idle, store.state.value.share)
    }

    @Test
    fun `a share sheet that throws fails the share instead of the app`() {
        val store = store(sheet = { error("refused") })

        store.send(DiagnosticsCommand.Share)

        assertEquals(ShareState.Failed(ShareProblem.NO_SHARE_TARGET), store.state.value.share)
        assertEquals(emptyList<FeatureFailure>(), failures)
    }

    @Test
    fun `a share whose report never gathers times out`() {
        val store = store(settingsSource = { awaitCancellation() })

        store.send(DiagnosticsCommand.Share)
        assertEquals(ShareState.Preparing, store.state.value.share)
        clock.advanceBy(Millis(SHARE_TIMEOUT_MS))

        assertEquals(ShareState.Failed(ShareProblem.TIMED_OUT), store.state.value.share)
        assertEquals(listOf("diagnostics: sharing diagnostics timed out"), failures.map { "${it.feature}: ${it.message}" })
    }

    @Test
    fun `seven quick taps on the version unlock the developer options, once`() =
        runTest {
            val store = store()
            val facts = mutableListOf<DiagnosticsFact>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.events.collect { facts += it } }

            repeat(UNLOCK_TAPS + 1) { store.send(DiagnosticsCommand.TapVersion) }

            assertEquals(DeveloperState.Unlocked(OverlayState.Hidden), store.state.value.developer)
            assertEquals(listOf(DiagnosticsFact.DeveloperUnlocked), facts)
            assertEquals(0, clock.pendingCount)
        }

    @Test
    fun `slow taps start counting again`() {
        val store = store()

        repeat(UNLOCK_TAPS - 1) { store.send(DiagnosticsCommand.TapVersion) }
        clock.advanceBy(Millis(TAP_WINDOW_MS))
        store.send(DiagnosticsCommand.TapVersion)

        assertEquals(DeveloperState.Locked(taps = 1), store.state.value.developer)
    }

    @Test
    fun `the engine's stats flow only while the overlay shows them`() {
        val store = store()
        repeat(UNLOCK_TAPS) { store.send(DiagnosticsCommand.TapVersion) }
        assertEquals(0, engine.collectors)

        store.send(DiagnosticsCommand.SetOverlay(visible = true))
        assertEquals(1, engine.collectors)
        engine.sample(STATS)
        assertEquals(DeveloperState.Unlocked(OverlayState.Shown(STATS)), store.state.value.developer)

        store.send(DiagnosticsCommand.SetOverlay(visible = false))
        assertEquals(0, engine.collectors)
        assertEquals(DeveloperState.Unlocked(OverlayState.Hidden), store.state.value.developer)
    }

    @Test
    fun `a share while the overlay shows stats puts the latest sample in the report`() {
        val store = store()
        repeat(UNLOCK_TAPS) { store.send(DiagnosticsCommand.TapVersion) }
        store.send(DiagnosticsCommand.SetOverlay(visible = true))
        engine.sample(STATS)

        store.send(DiagnosticsCommand.Share)

        val report = files.written.single()
        assertTrue(report, report.contains("Engine\n  state: RUNNING\n  frame time: 16667us\n  audio buffer: 192 frames\n"))
        assertTrue(report, report.contains("  visual buffer: 1080x2400\n  underruns: 4\n"))
    }

    private class RecordingFiles : ReportFiles {
        val written = mutableListOf<String>()

        override fun write(text: String): ReportFile {
            written += text
            return ReportFile(SHARED_URI)
        }
    }

    private class RecordingShareSheet : ShareSheet {
        val opened = mutableListOf<ReportFile>()

        override fun open(file: ReportFile): Boolean {
            opened += file
            return true
        }
    }

    private companion object {
        const val REPORT_TIME = 1_700_000_000_000L
        const val SHARE_TIMEOUT_MS = 10_000L
        const val TAP_WINDOW_MS = 1_500L
        const val SHARED_URI = "content://dev.geode.diagnostics/diagnostics/geode-diagnostics.txt"
        val STATS =
            EngineStats(
                frameTime = Micros(16_667),
                audioBuffer = Frames(192),
                visualBuffer = BufferSize(width = 1_080, height = 2_400),
                underruns = 4,
                engine = EngineRunState.RUNNING,
            )
    }
}
