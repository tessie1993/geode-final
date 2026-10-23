package dev.geode.design.theme

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import dev.geode.design.forest.ForestMotion

/**
 * Whether the App UI should keep still: the person's "Reduce motion" preference, or Android's
 * "remove animations". Components and Element effects read it as their default.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

/** Motion is reduced when the app asks for it or Android's animator [durationScale] is 0. */
fun isMotionReduced(
    appPreference: Boolean,
    durationScale: Float,
): Boolean = appPreference || ForestMotion.isAnimationRemoved(durationScale)

/** Provides [LocalReducedMotion] from the app's [appPreference] and the system setting. */
@Composable
fun ProvideReducedMotion(
    appPreference: Boolean,
    content: @Composable () -> Unit,
) {
    val resolver = LocalContext.current.contentResolver
    val durationScale = remember(resolver) { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }
    CompositionLocalProvider(LocalReducedMotion provides isMotionReduced(appPreference, durationScale), content = content)
}
