package dev.geode.feature.diagnostics.core

import dev.geode.engine.types.Frames
import dev.geode.engine.types.Micros
import dev.geode.engine.types.Millis
import dev.geode.feature.diagnostics.api.BufferSize
import dev.geode.feature.diagnostics.api.DeveloperState
import dev.geode.feature.diagnostics.api.DeveloperState.Locked
import dev.geode.feature.diagnostics.api.DeveloperState.Unlocked
import dev.geode.feature.diagnostics.api.DiagnosticsCommand
import dev.geode.feature.diagnostics.api.DiagnosticsFact
import dev.geode.feature.diagnostics.api.DiagnosticsState
import dev.geode.feature.diagnostics.api.EngineRunState
import dev.geode.feature.diagnostics.api.EngineStats
import dev.geode.feature.diagnostics.api.OverlayState.Hidden
import dev.geode.feature.diagnostics.api.OverlayState.Shown
import dev.geode.feature.diagnostics.api.ShareProblem.NO_SHARE_TARGET
import dev.geode.feature.diagnostics.api.ShareProblem.TIMED_OUT
import dev.geode.feature.diagnostics.api.ShareProblem.WRITE_FAILED
import dev.geode.feature.diagnostics.api.ShareState
import dev.geode.feature.diagnostics.api.ShareState.Failed
import dev.geode.feature.diagnostics.api.ShareState.Idle
import dev.geode.feature.diagnostics.api.ShareState.Preparing
import dev.geode.feature.diagnostics.api.ShareState.Sharing
import dev.geode.feature.diagnostics.core.DiagnosticsEvent.Command
import dev.geode.feature.diagnostics.core.DiagnosticsEvent.NoShareTarget
import dev.geode.feature.diagnostics.core.DiagnosticsEvent.ReportWritten
import dev.geode.feature.diagnostics.core.DiagnosticsEvent.ShareOpened
import dev.geode.feature.diagnostics.core.DiagnosticsEvent.ShareTimedOut
import dev.geode.feature.diagnostics.core.DiagnosticsEvent.StatsSampled
import dev.geode.feature.diagnostics.core.DiagnosticsEvent.TapWindowElapsed
import dev.geode.feature.diagnostics.core.DiagnosticsEvent.WriteFailed
import dev.geode.feature.diagnostics.core.DiagnosticsWork.OpenShareSheet
import dev.geode.feature.diagnostics.core.DiagnosticsWork.SampleEngine
import dev.geode.feature.diagnostics.core.DiagnosticsWork.WriteReport
import dev.geode.kernel.CancelTimer
import dev.geode.kernel.Emit
import dev.geode.kernel.Next
import dev.geode.kernel.Perform
import dev.geode.kernel.ReportFailure
import dev.geode.kernel.StartTimer
import org.junit.Assert.assertEquals
import org.junit.Test

/** The reducer as transition tables: every state of each part against every event (ADR-0003). */
class DiagnosticsReducerTest {
    private val share = Command(DiagnosticsCommand.Share)
    private val dismiss = Command(DiagnosticsCommand.DismissProblem)
    private val tap = Command(DiagnosticsCommand.TapVersion)
    private val show = Command(DiagnosticsCommand.SetOverlay(visible = true))
    private val hide = Command(DiagnosticsCommand.SetOverlay(visible = false))
    private val file = ReportFile("content://dev.geode.diagnostics/diagnostics/geode-diagnostics.txt")
    private val written = ReportWritten(file)
    private val stats = stats(underruns = 1)
    private val newer = stats(underruns = 2)
    private val sampled = StatsSampled(newer)

    private val shareEvents = listOf(share, dismiss, written, WriteFailed, ShareOpened, NoShareTarget, ShareTimedOut)
    private val developerEvents = listOf(tap, TapWindowElapsed, show, hide, sampled)
    private val shareStates = listOf(Idle, Preparing, Sharing, Failed(WRITE_FAILED))
    private val developerStates =
        listOf(Locked(0), Locked(UNLOCK_MINUS_ONE), Unlocked(Hidden), Unlocked(Shown(null)), Unlocked(Shown(stats)))

    private val preparing =
        listOf(StartTimer(ShareTimeout, Millis(10_000), ShareTimedOut), Perform(WriteReport(engine = null)))

    // The share, with the developer options locked: each row is (share state, event, next share, effects).
    private val shareTable: List<Row<ShareState>> =
        listOf(
            // Idle: only the button starts anything.
            Row(Idle, share, Preparing, preparing),
            Row(Idle, dismiss, Idle),
            Row(Idle, written, Idle),
            Row(Idle, WriteFailed, Idle),
            Row(Idle, ShareOpened, Idle),
            Row(Idle, NoShareTarget, Idle),
            Row(Idle, ShareTimedOut, Idle),
            // Preparing: the file is written, then shared; a failure or the timeout ends it.
            Row(Preparing, share, Preparing),
            Row(Preparing, dismiss, Preparing),
            Row(Preparing, written, Sharing, listOf(Perform(OpenShareSheet(file)))),
            Row(Preparing, WriteFailed, Failed(WRITE_FAILED), listOf(CancelTimer(ShareTimeout), ReportFailure(WRITE_FAILURE))),
            Row(Preparing, ShareOpened, Preparing),
            Row(Preparing, NoShareTarget, Preparing),
            Row(Preparing, ShareTimedOut, Failed(TIMED_OUT), listOf(ReportFailure(TIMEOUT_FAILURE))),
            // Sharing: the share sheet opens, or nothing can take the file.
            Row(Sharing, share, Sharing),
            Row(Sharing, dismiss, Sharing),
            Row(Sharing, written, Sharing),
            Row(Sharing, WriteFailed, Sharing),
            Row(Sharing, ShareOpened, Idle, listOf(CancelTimer(ShareTimeout))),
            Row(Sharing, NoShareTarget, Failed(NO_SHARE_TARGET), listOf(CancelTimer(ShareTimeout))),
            Row(Sharing, ShareTimedOut, Failed(TIMED_OUT), listOf(ReportFailure(TIMEOUT_FAILURE))),
            // Failed: shown until dismissed; the button tries again. Late results are ignored.
            Row(Failed(WRITE_FAILED), share, Preparing, preparing),
            Row(Failed(WRITE_FAILED), dismiss, Idle),
            Row(Failed(WRITE_FAILED), written, Failed(WRITE_FAILED)),
            Row(Failed(WRITE_FAILED), WriteFailed, Failed(WRITE_FAILED)),
            Row(Failed(WRITE_FAILED), ShareOpened, Failed(WRITE_FAILED)),
            Row(Failed(WRITE_FAILED), NoShareTarget, Failed(WRITE_FAILED)),
            Row(Failed(WRITE_FAILED), ShareTimedOut, Failed(WRITE_FAILED)),
        )

    private val tapWindow = StartTimer(TapWindow, Millis(1_500), TapWindowElapsed)

    // The developer options, with no share in progress.
    private val developerTable: List<Row<DeveloperState>> =
        listOf(
            // Locked: each tap counts and rearms the window; the seventh unlocks.
            Row(Locked(0), tap, Locked(1), listOf(tapWindow)),
            Row(Locked(0), TapWindowElapsed, Locked(0)),
            Row(Locked(0), show, Locked(0)),
            Row(Locked(0), hide, Locked(0)),
            Row(Locked(0), sampled, Locked(0)),
            Row(Locked(UNLOCK_MINUS_ONE), tap, Unlocked(Hidden), listOf(CancelTimer(TapWindow), Emit(DiagnosticsFact.DeveloperUnlocked))),
            Row(Locked(UNLOCK_MINUS_ONE), TapWindowElapsed, Locked(0)),
            Row(Locked(UNLOCK_MINUS_ONE), show, Locked(UNLOCK_MINUS_ONE)),
            Row(Locked(UNLOCK_MINUS_ONE), hide, Locked(UNLOCK_MINUS_ONE)),
            Row(Locked(UNLOCK_MINUS_ONE), sampled, Locked(UNLOCK_MINUS_ONE)),
            // Unlocked, overlay hidden: the switch shows it and starts the engine's samples.
            Row(Unlocked(Hidden), tap, Unlocked(Hidden)),
            Row(Unlocked(Hidden), TapWindowElapsed, Unlocked(Hidden)),
            Row(Unlocked(Hidden), show, Unlocked(Shown(null)), listOf(Perform(SampleEngine(on = true)))),
            Row(Unlocked(Hidden), hide, Unlocked(Hidden)),
            Row(Unlocked(Hidden), sampled, Unlocked(Hidden)),
            // Unlocked, overlay shown: follows the samples until switched off.
            Row(Unlocked(Shown(stats)), tap, Unlocked(Shown(stats))),
            Row(Unlocked(Shown(stats)), TapWindowElapsed, Unlocked(Shown(stats))),
            Row(Unlocked(Shown(stats)), show, Unlocked(Shown(stats))),
            Row(Unlocked(Shown(stats)), hide, Unlocked(Hidden), listOf(Perform(SampleEngine(on = false)))),
            Row(Unlocked(Shown(stats)), sampled, Unlocked(Shown(newer))),
            Row(Unlocked(Shown(null)), sampled, Unlocked(Shown(newer))),
        )

    @Test
    fun `every share state handles every share event as the table says`() {
        for (row in shareTable) {
            val expected = Next(DiagnosticsState(share = row.next, developer = Locked(0)), row.effects)
            assertEquals("${row.state} + ${row.event}", expected, reduce(DiagnosticsState(share = row.state), row.event))
        }
    }

    @Test
    fun `every developer state handles every developer event as the table says`() {
        for (row in developerTable) {
            val expected = Next(DiagnosticsState(share = Idle, developer = row.next), row.effects)
            assertEquals("${row.state} + ${row.event}", expected, reduce(DiagnosticsState(developer = row.state), row.event))
        }
    }

    @Test
    fun `the developer events leave the share as it is`() {
        for (state in shareStates) {
            for (event in developerEvents) {
                assertEquals("$state + $event", state, reduce(DiagnosticsState(share = state), event).state.share)
            }
        }
    }

    @Test
    fun `the share events leave the developer options as they are`() {
        for (state in developerStates) {
            for (event in shareEvents) {
                assertEquals("$state + $event", state, reduce(DiagnosticsState(developer = state), event).state.developer)
            }
        }
    }

    @Test
    fun `taps count up to the unlock, one at a time`() {
        var state = DiagnosticsState()
        repeat(UNLOCK_MINUS_ONE) { state = reduce(state, tap).state }

        assertEquals(Locked(UNLOCK_MINUS_ONE), state.developer)
        assertEquals(Unlocked(Hidden), reduce(state, tap).state.developer)
    }

    @Test
    fun `the report carries the overlay's latest sample, and only while the overlay shows one`() {
        val expected =
            mapOf(
                Locked(0) to null,
                Unlocked(Hidden) to null,
                Unlocked(Shown(null)) to null,
                Unlocked(Shown(stats)) to stats,
            )
        for ((developer, engine) in expected) {
            val effects = reduce(DiagnosticsState(developer = developer), share).effects
            assertEquals("$developer", Perform(WriteReport(engine)), effects.last())
        }
    }

    private fun reduce(
        state: DiagnosticsState,
        event: DiagnosticsEvent,
    ): Next<DiagnosticsState, DiagnosticsEffect> = DiagnosticsReducer.reduce(state, event)

    private class Row<S>(
        val state: S,
        val event: DiagnosticsEvent,
        val next: S,
        val effects: List<DiagnosticsEffect> = emptyList(),
    )

    private companion object {
        const val UNLOCK_MINUS_ONE = 6

        fun stats(underruns: Long) =
            EngineStats(
                frameTime = Micros(16_667),
                audioBuffer = Frames(192),
                visualBuffer = BufferSize(width = 1_080, height = 2_400),
                underruns = underruns,
                engine = EngineRunState.RUNNING,
            )
    }
}
