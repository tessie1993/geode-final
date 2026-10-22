package dev.geode.feature.playback.ui

import androidx.compose.runtime.Immutable
import dev.geode.feature.playback.api.Banner
import dev.geode.feature.playback.api.PlaybackCommand

/**
 * What the app-wide error banner draws (#147, ADR-0004): one failure's [message]. [counting] says
 * whether its time on screen has started; until it has, the banner says it is on screen.
 */
@Immutable
data class BannerModel(
    val message: String,
    val counting: Boolean,
)

/** The one callback the banner sends (ADR-0003). */
sealed interface BannerAction {
    /** The banner's message is on screen. */
    data object Shown : BannerAction

    data object Dismiss : BannerAction
}

/** The banner's model, or null when it shows nothing. */
fun Banner.toBannerModel(): BannerModel? =
    when (this) {
        Banner.Hidden -> null
        is Banner.Waiting -> BannerModel(notice.message, counting = false)
        is Banner.Showing -> BannerModel(notice.message, counting = true)
    }

/** The Command [this] action sends to Playback. */
fun BannerAction.toCommand(): PlaybackCommand =
    when (this) {
        BannerAction.Shown -> PlaybackCommand.BannerShown
        BannerAction.Dismiss -> PlaybackCommand.DismissBanner
    }
