package dev.geode.feature.diagnostics.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.geode.engine.types.Frames
import dev.geode.engine.types.Micros
import dev.geode.feature.diagnostics.api.BufferSize
import dev.geode.feature.diagnostics.api.EngineRunState
import dev.geode.feature.diagnostics.api.EngineStats

/** The overlay's look; replaced by the `:design` tokens once that module lands (#61). */
private object OverlayLook {
    val padding = 8.dp
    val margin = 8.dp
    val corner = 8.dp
    const val SCRIM_ALPHA = 0.8f
}

/**
 * The developer overlay: frame time, buffer sizes, audio underruns and the engine state, in a small
 * panel the route lays over the App UI. It is App UI (CONTEXT.md): it is composed in the app's window
 * above everything else and never drawn into the Visual's surface, so no Render or Recording ever
 * shows it. Stateless: it draws [model] and nothing else.
 */
@Composable
fun DeveloperOverlay(
    model: DeveloperOverlayModel,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.padding(OverlayLook.margin),
        shape = RoundedCornerShape(OverlayLook.corner),
        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = OverlayLook.SCRIM_ALPHA),
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
    ) {
        Column(Modifier.padding(OverlayLook.padding)) {
            val stats = model.stats
            if (stats == null) {
                OverlayLine(stringResource(R.string.overlay_waiting))
            } else {
                OverlayLine(stringResource(R.string.overlay_frame_time, frameMillis(stats.frameTime)))
                OverlayLine(stringResource(R.string.overlay_audio_buffer, stats.audioBuffer.value))
                OverlayLine(stringResource(R.string.overlay_visual_buffer, stats.visualBuffer.width, stats.visualBuffer.height))
                OverlayLine(stringResource(R.string.overlay_underruns, stats.underruns))
                OverlayLine(stringResource(R.string.overlay_engine, stringResource(engineText(stats.engine))))
            }
        }
    }
}

@Composable
private fun OverlayLine(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace))
}

private fun engineText(state: EngineRunState): Int =
    when (state) {
        EngineRunState.STOPPED -> R.string.engine_stopped
        EngineRunState.STARTING -> R.string.engine_starting
        EngineRunState.RUNNING -> R.string.engine_running
        EngineRunState.PAUSED -> R.string.engine_paused
        EngineRunState.FAILED -> R.string.engine_failed
    }

internal val previewStats =
    EngineStats(
        frameTime = Micros(16_667),
        audioBuffer = Frames(192),
        visualBuffer = BufferSize(width = 1_080, height = 2_400),
        underruns = 3,
        engine = EngineRunState.RUNNING,
    )

@Preview
@Composable
internal fun DeveloperOverlayPreview() {
    MaterialTheme { DeveloperOverlay(DeveloperOverlayModel(previewStats)) }
}

@Preview
@Composable
internal fun DeveloperOverlayWaitingPreview() {
    MaterialTheme { DeveloperOverlay(DeveloperOverlayModel(stats = null)) }
}
