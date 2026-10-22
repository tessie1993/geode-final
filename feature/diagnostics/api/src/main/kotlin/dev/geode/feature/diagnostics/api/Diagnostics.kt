package dev.geode.feature.diagnostics.api

import dev.geode.engine.types.Frames
import dev.geode.engine.types.Micros
import dev.geode.kernel.Feature

// Diagnostics (#62, ADR-0005): there is no internet permission, so diagnosis stays on the phone. A
// "Share diagnostics" button in Settings writes a file (the log, the settings, the phone model and the
// app version, no personal data) and hands it to the system share sheet. A hidden developer overlay,
// unlocked by tapping the version, shows frame time, buffer sizes, audio underruns and the engine
// state. The overlay is App UI: it is never drawn into the Visual, a Render or a Recording.

/** The public interface: what Settings' screens and the overlay may use. */
typealias Diagnostics = Feature<DiagnosticsState, DiagnosticsCommand, DiagnosticsFact>

/** How many taps on the version in a row unlock the developer options. */
const val UNLOCK_TAPS = 7

/** Everything the outside may read: the share in progress and the developer options. */
data class DiagnosticsState(
    val share: ShareState = ShareState.Idle,
    val developer: DeveloperState = DeveloperState.Locked(taps = 0),
)

/** Where "Share diagnostics" is. */
sealed interface ShareState {
    /** Nothing in progress; the button can be pressed. */
    data object Idle : ShareState

    /** The diagnostics file is being written. */
    data object Preparing : ShareState

    /** The file is written and the share sheet is opening. */
    data object Sharing : ShareState

    /** The last attempt failed, for [problem]; shown until dismissed or tried again. */
    data class Failed(
        val problem: ShareProblem,
    ) : ShareState
}

enum class ShareProblem {
    /** The file could not be written, for example because the phone is out of space. */
    WRITE_FAILED,

    /** No app on the phone can receive the file. */
    NO_SHARE_TARGET,

    /** Writing or sharing took too long. */
    TIMED_OUT,
}

/** The hidden developer options. */
sealed interface DeveloperState {
    /** Hidden. [taps] counts the recent taps on the version, towards [UNLOCK_TAPS]. */
    data class Locked(
        val taps: Int,
    ) : DeveloperState

    /** Unlocked: the developer overlay can be switched on and off. */
    data class Unlocked(
        val overlay: OverlayState,
    ) : DeveloperState
}

/** The developer overlay, drawn over the App UI only. */
sealed interface OverlayState {
    data object Hidden : OverlayState

    /** Shown, with the latest [stats] from the engine, or null until the first sample arrives. */
    data class Shown(
        val stats: EngineStats?,
    ) : OverlayState
}

/**
 * One sample of how the engine is doing, for the developer overlay and the diagnostics file:
 * how long the last Visual frame took to draw ([frameTime]), the self-tuning audio output buffer
 * ([audioBuffer]) and the Visual's load-following scene buffer ([visualBuffer]) (ADR-0005), the
 * audio underruns (crackles) since the engine started ([underruns]), and what the engine is doing.
 */
data class EngineStats(
    val frameTime: Micros,
    val audioBuffer: Frames,
    val visualBuffer: BufferSize,
    val underruns: Long,
    val engine: EngineRunState,
)

/** A pixel buffer's size. */
data class BufferSize(
    val width: Int,
    val height: Int,
)

/** What the native engine is doing. */
enum class EngineRunState {
    STOPPED,
    STARTING,
    RUNNING,
    PAUSED,
    FAILED,
}

sealed interface DiagnosticsCommand {
    /** Write the diagnostics file and open the share sheet with it. */
    data object Share : DiagnosticsCommand

    /** Clear a [ShareState.Failed]. */
    data object DismissProblem : DiagnosticsCommand

    /** The version in Settings was tapped; [UNLOCK_TAPS] in a row unlock the developer options. */
    data object TapVersion : DiagnosticsCommand

    /** Show or hide the developer overlay; ignored while the developer options are locked. */
    data class SetOverlay(
        val visible: Boolean,
    ) : DiagnosticsCommand
}

sealed interface DiagnosticsFact {
    /** The developer options were just unlocked, for a one-off confirmation. */
    data object DeveloperUnlocked : DiagnosticsFact
}
