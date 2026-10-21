package dev.geode.ui

import androidx.media3.common.Player
import dev.geode.analysis.IntelligenceMode
import dev.geode.data.Preset
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
    val intelligenceMode: IntelligenceMode = IntelligenceMode.MANUAL,
    val suggestedSceneId: SceneId? = null,
    val attack: Float = 0.6f,
    val decay: Float = 0.12f,
    val analysis: AnalysisState = AnalysisState.Idle,
    val bpm: Float = 0f,
    val shaderError: String? = null,
    val presets: List<Preset> = emptyList(),
    val params: SceneParams = SceneParams.DEFAULT,
    val transitionStyle: TransitionStyle = TransitionStyle.FADE,
    val transitionId: String = TransitionStyle.FADE.name.lowercase(),
    val transitionDurationSec: Float = 1.2f,
)

data class VizApply(
    val milkPath: String? = null,
    val customShader: String? = null,
    val sceneId: SceneId? = null,
)

data class MilkFile(
    val name: String,
    val path: String,
)

data class QueueTrack(
    val uri: TrackId,
    val title: String = "",
    val artist: String = "",
)

data class QueueUiState(
    val tracks: List<QueueTrack> = emptyList(),
    val index: Int = 0,
)
