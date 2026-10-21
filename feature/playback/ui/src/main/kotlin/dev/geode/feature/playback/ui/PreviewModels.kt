package dev.geode.feature.playback.ui

import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.playback.api.PlaybackFailure
import dev.geode.feature.playback.api.Track

/** The models the previews, and so the screenshots, draw. */
internal object PreviewModels {
    private val track = Track(TrackId("content://preview/1"), "Night Drive", "Ann Arbor", Millis(215_000))

    val playing =
        PlayerModel(PlayerStatus.PLAYING, track, Millis(64_000), meantToPlay = true, canGoPrevious = true, canGoNext = true)
    val paused = playing.copy(status = PlayerStatus.PAUSED, meantToPlay = false)
    val buffering = playing.copy(status = PlayerStatus.BUFFERING)
    val loading = playing.copy(status = PlayerStatus.LOADING, position = Millis.ZERO)
    val ended = playing.copy(status = PlayerStatus.ENDED, position = track.duration, meantToPlay = false, canGoNext = false)
    val error =
        playing.copy(
            status = PlayerStatus.ERROR,
            position = Millis.ZERO,
            meantToPlay = false,
            failure = PlaybackFailure.ENGINE_NOT_RESPONDING,
        )
    val idle = PlayerModel.IDLE
}
