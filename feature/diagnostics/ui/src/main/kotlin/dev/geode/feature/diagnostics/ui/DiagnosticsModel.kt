package dev.geode.feature.diagnostics.ui

import androidx.compose.runtime.Immutable
import dev.geode.engine.types.Micros
import dev.geode.feature.diagnostics.api.DeveloperState
import dev.geode.feature.diagnostics.api.DiagnosticsState
import dev.geode.feature.diagnostics.api.EngineStats
import dev.geode.feature.diagnostics.api.OverlayState
import dev.geode.feature.diagnostics.api.ShareProblem
import dev.geode.feature.diagnostics.api.ShareState

/** What the "Share diagnostics" button shows. */
enum class ShareUi {
    READY,
    WORKING,
    FAILED_WRITE,
    FAILED_NO_TARGET,
    FAILED_TIMED_OUT,
}

/** The Diagnostics section of Settings, as its screen draws it. */
@Immutable
data class DiagnosticsSectionModel(
    val version: String,
    val share: ShareUi,
    val developerUnlocked: Boolean,
    val overlayOn: Boolean,
)

/** The developer overlay: the latest [stats], or null while it waits for the first sample. */
@Immutable
data class DeveloperOverlayModel(
    val stats: EngineStats?,
)

/** The section's model for [version], the app's version name, which the route reads from the app. */
fun DiagnosticsState.toSectionModel(version: String): DiagnosticsSectionModel =
    DiagnosticsSectionModel(
        version = version,
        share = share.toUi(),
        developerUnlocked = developer is DeveloperState.Unlocked,
        overlayOn = overlayModel() != null,
    )

/** The overlay's model, or null when the overlay is not on. */
fun DiagnosticsState.overlayModel(): DeveloperOverlayModel? =
    when (val developer = developer) {
        is DeveloperState.Locked -> null
        is DeveloperState.Unlocked ->
            when (val overlay = developer.overlay) {
                OverlayState.Hidden -> null
                is OverlayState.Shown -> DeveloperOverlayModel(overlay.stats)
            }
    }

private fun ShareState.toUi(): ShareUi =
    when (this) {
        ShareState.Idle -> ShareUi.READY
        ShareState.Preparing, ShareState.Sharing -> ShareUi.WORKING
        is ShareState.Failed ->
            when (problem) {
                ShareProblem.WRITE_FAILED -> ShareUi.FAILED_WRITE
                ShareProblem.NO_SHARE_TARGET -> ShareUi.FAILED_NO_TARGET
                ShareProblem.TIMED_OUT -> ShareUi.FAILED_TIMED_OUT
            }
    }

private const val MICROS_PER_TENTH_MILLI = 100L
private const val HALF_A_TENTH = MICROS_PER_TENTH_MILLI / 2
private const val TENTHS_PER_MILLI = 10L

/** [frameTime] in milliseconds with one decimal, rounded half up, whatever the phone's language: "16.7". */
fun frameMillis(frameTime: Micros): String {
    val tenths = (frameTime.value + HALF_A_TENTH) / MICROS_PER_TENTH_MILLI
    return "${tenths / TENTHS_PER_MILLI}.${tenths % TENTHS_PER_MILLI}"
}
