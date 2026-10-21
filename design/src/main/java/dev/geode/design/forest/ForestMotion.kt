package dev.geode.design.forest

import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** A spring's feel: how much it overshoots ([dampingRatio], 1 for none) and how fast it moves ([stiffness]). */
data class ForestSpring(
    val dampingRatio: Float,
    val stiffness: Float,
)

/**
 * The App UI's motion: organic and springy, as if things settle onto water and moss. Soft springs,
 * gentle overshoot that never keeps wobbling, and slow fades. It never follows the music. Every spec
 * snaps straight to its end when Android's "remove animations" setting is on ([isAnimationRemoved]).
 */
object ForestMotion {
    /** Panels, sheets and cards coming to rest: a gentle overshoot, settled in about half a second. */
    val Settle = ForestSpring(dampingRatio = 0.75f, stiffness = 300f)

    /** Small things that bloom open, such as a toggle's knob or a chip: a touch more bounce. */
    val Bloom = ForestSpring(dampingRatio = 0.6f, stiffness = 420f)

    /** The quick give of a pressed control, with almost no overshoot. */
    val Press = ForestSpring(dampingRatio = 0.85f, stiffness = 800f)

    /** Slow drift and parallax of floating assets: no overshoot at all. */
    val Drift = ForestSpring(dampingRatio = 1f, stiffness = 50f)

    /** A slow fade in or out, in milliseconds. */
    const val FADE_MILLIS = 420

    /** The opacity pulse that stands in for a ripple when animations are removed, in milliseconds. */
    const val REDUCED_PULSE_MILLIS = 150

    /** The animator duration scale Android reports when the person removed animations. */
    const val ANIMATIONS_REMOVED_SCALE = 0f

    /** Whether Android's animator [durationScale] means the person removed animations. */
    fun isAnimationRemoved(durationScale: Float): Boolean = durationScale <= ANIMATIONS_REMOVED_SCALE

    /** [feel] as a Compose spec, or a snap to the end when [reduced]. */
    fun <T> spec(
        feel: ForestSpring,
        reduced: Boolean,
    ): AnimationSpec<T> = if (reduced) snap() else spring(feel.dampingRatio, feel.stiffness)

    /** The slow fade, or a snap to the end when [reduced]. */
    fun <T> fade(reduced: Boolean): AnimationSpec<T> = if (reduced) snap() else tween(FADE_MILLIS)
}

/** Whether the person turned on Android's "remove animations" setting, read once per composition. */
@Composable
fun rememberAnimationsRemoved(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        ForestMotion.isAnimationRemoved(
            Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, DEFAULT_DURATION_SCALE),
        )
    }
}

private const val DEFAULT_DURATION_SCALE = 1f
