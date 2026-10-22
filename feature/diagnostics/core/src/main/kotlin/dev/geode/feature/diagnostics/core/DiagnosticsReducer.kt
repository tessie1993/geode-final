package dev.geode.feature.diagnostics.core

import dev.geode.engine.types.Millis
import dev.geode.feature.diagnostics.api.DeveloperState
import dev.geode.feature.diagnostics.api.DiagnosticsCommand
import dev.geode.feature.diagnostics.api.DiagnosticsFact
import dev.geode.feature.diagnostics.api.DiagnosticsState
import dev.geode.feature.diagnostics.api.EngineStats
import dev.geode.feature.diagnostics.api.OverlayState
import dev.geode.feature.diagnostics.api.ShareProblem
import dev.geode.feature.diagnostics.api.ShareState
import dev.geode.feature.diagnostics.api.UNLOCK_TAPS
import dev.geode.kernel.CancelTimer
import dev.geode.kernel.Effect
import dev.geode.kernel.Emit
import dev.geode.kernel.Next
import dev.geode.kernel.Perform
import dev.geode.kernel.Reducer
import dev.geode.kernel.ReportFailure
import dev.geode.kernel.StartTimer

/** Everything the reducer handles: the commands, what the handlers report back, and fired timers. */
sealed interface DiagnosticsEvent {
    data class Command(
        val command: DiagnosticsCommand,
    ) : DiagnosticsEvent

    /** The diagnostics file is written and can be shared as [file]. */
    data class ReportWritten(
        val file: ReportFile,
    ) : DiagnosticsEvent

    /** The diagnostics file could not be written. */
    data object WriteFailed : DiagnosticsEvent

    /** The share sheet opened with the file. */
    data object ShareOpened : DiagnosticsEvent

    /** No app can receive the file. */
    data object NoShareTarget : DiagnosticsEvent

    /** Writing and sharing took longer than [SHARE_TIMEOUT]. */
    data object ShareTimedOut : DiagnosticsEvent

    /** [TAP_WINDOW] passed without another tap on the version. */
    data object TapWindowElapsed : DiagnosticsEvent

    /** The engine reported a new sample. */
    data class StatsSampled(
        val stats: EngineStats,
    ) : DiagnosticsEvent
}

/** The diagnostics file once written: a content uri other apps may be granted to read. */
data class ReportFile(
    val uri: String,
)

/** The work the handlers carry out. */
sealed interface DiagnosticsWork {
    /** Follow the engine's stats for the life of the store, while [SampleEngine] asks for them. */
    data object FollowEngine : DiagnosticsWork

    /** Start ([on]) or stop forwarding the engine's stats; only the overlay needs them. */
    data class SampleEngine(
        val on: Boolean,
    ) : DiagnosticsWork

    /** Gather the report, with the latest [engine] sample if the overlay has one, and write it. */
    data class WriteReport(
        val engine: EngineStats?,
    ) : DiagnosticsWork

    /** Open the system share sheet with [file]. */
    data class OpenShareSheet(
        val file: ReportFile,
    ) : DiagnosticsWork
}

typealias DiagnosticsEffect = Effect<DiagnosticsWork, DiagnosticsEvent, DiagnosticsFact>

/** The timer that bounds a share, armed from the button press until the share sheet opens or it fails. */
object ShareTimeout

/** The timer that forgets the taps on the version when they stop coming. */
object TapWindow

/** How long writing the file and opening the share sheet may take. */
val SHARE_TIMEOUT = Millis(10_000)

/** How long after one tap on the version the next must come to count towards [UNLOCK_TAPS]. */
val TAP_WINDOW = Millis(1_500)

/** The failure reported to the error channel when the file cannot be written. */
const val WRITE_FAILURE = "the diagnostics file could not be written"

/** The failure reported to the error channel when a share times out. */
const val TIMEOUT_FAILURE = "sharing diagnostics timed out"

/**
 * Diagnostics' state machine. The share and the developer options are independent: each event
 * changes one of them, with its own transitions over its own sealed states.
 */
object DiagnosticsReducer : Reducer<DiagnosticsState, DiagnosticsEvent, DiagnosticsEffect> {
    private val shareCommand = DiagnosticsEvent.Command(DiagnosticsCommand.Share)
    private val dismissCommand = DiagnosticsEvent.Command(DiagnosticsCommand.DismissProblem)
    private val tapCommand = DiagnosticsEvent.Command(DiagnosticsCommand.TapVersion)

    override fun reduce(
        state: DiagnosticsState,
        event: DiagnosticsEvent,
    ): Next<DiagnosticsState, DiagnosticsEffect> =
        when (event) {
            is DiagnosticsEvent.Command ->
                when (val command = event.command) {
                    DiagnosticsCommand.Share, DiagnosticsCommand.DismissProblem -> share(state, event)
                    DiagnosticsCommand.TapVersion -> developer(state, event)
                    is DiagnosticsCommand.SetOverlay -> overlay(state, command.visible)
                }
            is DiagnosticsEvent.ReportWritten,
            DiagnosticsEvent.WriteFailed,
            DiagnosticsEvent.ShareOpened,
            DiagnosticsEvent.NoShareTarget,
            DiagnosticsEvent.ShareTimedOut,
            -> share(state, event)
            DiagnosticsEvent.TapWindowElapsed -> developer(state, event)
            is DiagnosticsEvent.StatsSampled -> sampled(state, event.stats)
        }

    private fun share(
        state: DiagnosticsState,
        event: DiagnosticsEvent,
    ): Next<DiagnosticsState, DiagnosticsEffect> {
        val next =
            when (val share = state.share) {
                ShareState.Idle -> idle(share, event, state)
                ShareState.Preparing -> preparing(share, event)
                ShareState.Sharing -> sharing(share, event)
                is ShareState.Failed -> failed(share, event, state)
            }
        return Next(state.copy(share = next.state), next.effects)
    }

    private fun idle(
        share: ShareState,
        event: DiagnosticsEvent,
        state: DiagnosticsState,
    ): Next<ShareState, DiagnosticsEffect> = if (event == shareCommand) prepare(state) else Next(share)

    private fun failed(
        share: ShareState.Failed,
        event: DiagnosticsEvent,
        state: DiagnosticsState,
    ): Next<ShareState, DiagnosticsEffect> =
        when (event) {
            shareCommand -> prepare(state)
            dismissCommand -> Next(ShareState.Idle)
            else -> Next(share)
        }

    private fun preparing(
        share: ShareState,
        event: DiagnosticsEvent,
    ): Next<ShareState, DiagnosticsEffect> =
        when (event) {
            is DiagnosticsEvent.ReportWritten -> Next(ShareState.Sharing, listOf(Perform(DiagnosticsWork.OpenShareSheet(event.file))))
            DiagnosticsEvent.WriteFailed ->
                Next(ShareState.Failed(ShareProblem.WRITE_FAILED), listOf(CancelTimer(ShareTimeout), ReportFailure(WRITE_FAILURE)))
            DiagnosticsEvent.ShareTimedOut -> timedOut()
            else -> Next(share)
        }

    private fun sharing(
        share: ShareState,
        event: DiagnosticsEvent,
    ): Next<ShareState, DiagnosticsEffect> =
        when (event) {
            DiagnosticsEvent.ShareOpened -> Next(ShareState.Idle, listOf(CancelTimer(ShareTimeout)))
            DiagnosticsEvent.NoShareTarget -> Next(ShareState.Failed(ShareProblem.NO_SHARE_TARGET), listOf(CancelTimer(ShareTimeout)))
            DiagnosticsEvent.ShareTimedOut -> timedOut()
            else -> Next(share)
        }

    private fun prepare(state: DiagnosticsState): Next<ShareState, DiagnosticsEffect> =
        Next(
            ShareState.Preparing,
            listOf(
                StartTimer(ShareTimeout, SHARE_TIMEOUT, DiagnosticsEvent.ShareTimedOut),
                Perform(DiagnosticsWork.WriteReport(latestStats(state.developer))),
            ),
        )

    private fun timedOut(): Next<ShareState, DiagnosticsEffect> =
        Next(ShareState.Failed(ShareProblem.TIMED_OUT), listOf(ReportFailure(TIMEOUT_FAILURE)))

    private fun latestStats(developer: DeveloperState): EngineStats? =
        when (developer) {
            is DeveloperState.Locked -> null
            is DeveloperState.Unlocked ->
                when (val overlay = developer.overlay) {
                    OverlayState.Hidden -> null
                    is OverlayState.Shown -> overlay.stats
                }
        }

    private fun developer(
        state: DiagnosticsState,
        event: DiagnosticsEvent,
    ): Next<DiagnosticsState, DiagnosticsEffect> =
        when (val developer = state.developer) {
            is DeveloperState.Locked ->
                when (event) {
                    tapCommand -> tapped(state, developer.taps + 1)
                    else -> Next(state.copy(developer = DeveloperState.Locked(taps = 0)))
                }
            is DeveloperState.Unlocked -> Next(state)
        }

    private fun tapped(
        state: DiagnosticsState,
        taps: Int,
    ): Next<DiagnosticsState, DiagnosticsEffect> =
        if (taps < UNLOCK_TAPS) {
            Next(
                state.copy(developer = DeveloperState.Locked(taps)),
                listOf(StartTimer(TapWindow, TAP_WINDOW, DiagnosticsEvent.TapWindowElapsed)),
            )
        } else {
            Next(
                state.copy(developer = DeveloperState.Unlocked(OverlayState.Hidden)),
                listOf(CancelTimer(TapWindow), Emit(DiagnosticsFact.DeveloperUnlocked)),
            )
        }

    private fun overlay(
        state: DiagnosticsState,
        visible: Boolean,
    ): Next<DiagnosticsState, DiagnosticsEffect> =
        when (val developer = state.developer) {
            is DeveloperState.Locked -> Next(state)
            is DeveloperState.Unlocked ->
                when (developer.overlay) {
                    OverlayState.Hidden ->
                        if (visible) switchOverlay(state, OverlayState.Shown(stats = null), on = true) else Next(state)
                    is OverlayState.Shown ->
                        if (visible) Next(state) else switchOverlay(state, OverlayState.Hidden, on = false)
                }
        }

    private fun switchOverlay(
        state: DiagnosticsState,
        overlay: OverlayState,
        on: Boolean,
    ): Next<DiagnosticsState, DiagnosticsEffect> =
        Next(state.copy(developer = DeveloperState.Unlocked(overlay)), listOf(Perform(DiagnosticsWork.SampleEngine(on))))

    private fun sampled(
        state: DiagnosticsState,
        stats: EngineStats,
    ): Next<DiagnosticsState, DiagnosticsEffect> =
        when (val developer = state.developer) {
            is DeveloperState.Locked -> Next(state)
            is DeveloperState.Unlocked ->
                when (developer.overlay) {
                    OverlayState.Hidden -> Next(state)
                    is OverlayState.Shown -> Next(state.copy(developer = DeveloperState.Unlocked(OverlayState.Shown(stats))))
                }
        }
}
