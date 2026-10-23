package dev.geode.ui

import androidx.media3.common.Player
import dev.geode.engine.types.Millis
import dev.geode.engine.types.SceneId
import dev.geode.engine.types.TrackId
import dev.geode.render.TransitionStyle
import dev.geode.render.scene.SceneIds
import dev.geode.render.scene.SceneParams

data class PlayerUiState(
    val isPlaying: Boolean = false,
    val position: Millis = Millis.ZERO,
    val duration: Millis = Millis.ZERO,
    val title: String? = null,
    val artist: String? = null,
    val hasMedia: Boolean = false,
    val queueSize: Int = 0,
    val queueIndex: Int = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
)

data class VizUiState(
    val sceneId: SceneId = SceneIds.DEFAULT,
    val attack: Float = 0.6f,
    val decay: Float = 0.12f,
    val analysis: AnalysisState = AnalysisState.Idle,
    val params: SceneParams = SceneParams.DEFAULT,
    val transitionStyle: TransitionStyle = TransitionStyle.FADE,
    val transitionId: String = TransitionStyle.FADE.name.lowercase(),
    val transitionDurationSec: Float = 1.2f,
)

data class QueueTrack(
    val uri: TrackId,
    val title: String = "",
    val artist: String = "",
    /** Zero while the length is not known. */
    val duration: Millis = Millis.ZERO,
)

/** The A-B loop as the old player shows it: its start, and its end once set. */
data class AbLoop(
    val start: Millis,
    val end: Millis? = null,
)

data class QueueUiState(
    val tracks: List<QueueTrack> = emptyList(),
    val index: Int = 0,
)
