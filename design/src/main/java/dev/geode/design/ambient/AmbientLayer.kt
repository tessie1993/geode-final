package dev.geode.design.ambient

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import dev.geode.design.forest.ForestThemeId
import dev.geode.design.forest.rememberAnimationsRemoved

/**
 * The floating assets behind the App UI: up to [AmbientScene.MAX_ON_SCREEN] approved assets of
 * [theme], drifting slowly across three [DepthLayer]s with gentle parallax.
 *
 * Put it behind a screen's content. It is App UI only: never on the Player over the Visual, and it
 * never listens to the music or any other audio (a Konsist test in `:app` holds both lines).
 *
 * [mode] is the person's choice; battery saver, the system's "remove animations" and the app's own
 * [reducedMotion] turn drifting into still ([AmbientPolicy]). It draws nothing when off, and nothing
 * for a theme whose assets the owner has not approved yet ([FloatingAssetManifest]). The same [seed]
 * always places the same scene. [scrollOffsetPx] is read while drawing, so scrolling never recomposes.
 */
@Composable
fun AmbientLayer(
    theme: ForestThemeId,
    modifier: Modifier = Modifier,
    mode: AmbientMode = AmbientMode.Default,
    reducedMotion: Boolean = false,
    seed: Long = AmbientScene.DEFAULT_SEED,
    scrollOffsetPx: () -> Float = { NO_SCROLL },
    assets: List<FloatingAsset> = FloatingAssetManifest.entries,
) {
    val powerSaver = rememberPowerSaveMode()
    val animationsRemoved = rememberAnimationsRemoved()
    val effective =
        AmbientPolicy.effectiveMode(
            chosen = mode,
            powerSaver = powerSaver,
            reducedMotion = reducedMotion || animationsRemoved,
        )
    if (effective == AmbientMode.Off) return
    val shippable = remember(theme, assets) { FloatingAssetManifest.shippable(theme, assets) }
    if (shippable.isEmpty()) return
    val sprites = remember(shippable, seed) { AmbientScene.place(shippable, seed) }
    val images: Map<Int, ImageBitmap> = shippable.associate { it.drawable to ImageBitmap.imageResource(it.drawable) }
    val elapsedSeconds = remember { mutableFloatStateOf(STILL_SECONDS) }
    if (effective == AmbientMode.Drifting) {
        LaunchedEffect(Unit) {
            val resumedAt = elapsedSeconds.floatValue
            val start = withFrameNanos { it }
            while (true) {
                withFrameNanos { now -> elapsedSeconds.floatValue = resumedAt + (now - start) / NANOS_PER_SECOND }
            }
        }
    }
    Canvas(modifier.fillMaxSize()) {
        val elapsed = elapsedSeconds.floatValue
        val scrollFraction = if (size.height > 0f) scrollOffsetPx() / size.height else NO_SCROLL
        for (sprite in sprites) {
            val image = images.getValue(sprite.asset.drawable)
            val pose = AmbientScene.poseAt(sprite, elapsed, scrollFraction)
            val centre = Offset(pose.x * size.width, pose.y * size.height)
            rotate(pose.rotation, pivot = centre) {
                drawImage(
                    image = image,
                    topLeft = Offset(centre.x - image.width / HALF, centre.y - image.height / HALF),
                    alpha = sprite.asset.layer.alpha,
                )
            }
        }
    }
}

/** Whether battery saver is on, kept current while the caller is composed. */
@Composable
fun rememberPowerSaveMode(): Boolean {
    val context = LocalContext.current
    val power = remember(context) { context.getSystemService(PowerManager::class.java) }
    var saving by remember(power) { mutableStateOf(power?.isPowerSaveMode == true) }
    DisposableEffect(context, power) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    received: Context,
                    intent: Intent,
                ) {
                    saving = power?.isPowerSaveMode == true
                }
            }
        val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        onDispose { context.unregisterReceiver(receiver) }
    }
    return saving
}

private const val NO_SCROLL = 0f
private const val STILL_SECONDS = 0f
private const val NANOS_PER_SECOND = 1_000_000_000f
private const val HALF = 2f
