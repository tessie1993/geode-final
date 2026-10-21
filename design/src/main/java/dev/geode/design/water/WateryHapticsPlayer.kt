package dev.geode.design.water

import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView

/** Plays the App UI's [WateryHaptic]s. */
fun interface WateryHapticsPlayer {
    fun play(haptic: WateryHaptic)

    companion object {
        /** Plays nothing: the default until the app provides the person's Settings choice. */
        val Silent = WateryHapticsPlayer { }
    }
}

/** The haptics player of the composition; [WateryHapticsPlayer.Silent] unless [ProvideWateryHaptics] sets one. */
val LocalWateryHaptics = staticCompositionLocalOf { WateryHapticsPlayer.Silent }

/**
 * Provides watery haptics to [content], played through this window's vibrator. [enabled] is the
 * person's "Haptics" switch in Settings; when it is off nothing vibrates at all.
 */
@Composable
fun ProvideWateryHaptics(
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    val player = remember(view, enabled) { AndroidWateryHaptics(view, enabled) }
    CompositionLocalProvider(LocalWateryHaptics provides player, content = content)
}

/** Plays each [HapticPlan] on Android: a composed vibration, or the view's own feedback. */
internal class AndroidWateryHaptics(
    private val view: View,
    private val enabled: Boolean,
) : WateryHapticsPlayer {
    private val vibrator: Vibrator? = view.context.getSystemService(VibratorManager::class.java)?.defaultVibrator

    private val supported: Set<HapticPrimitive> =
        vibrator
            ?.takeIf { it.hasVibrator() }
            ?.let { v -> HapticPrimitive.entries.filter { v.areAllPrimitivesSupported(it.platformId()) }.toSet() }
            .orEmpty()

    override fun play(haptic: WateryHaptic) {
        when (val plan = WateryHaptics.plan(haptic, enabled, supported)) {
            HapticPlan.Off -> Unit
            is HapticPlan.Composed -> vibrator?.vibrate(plan.toEffect())
            is HapticPlan.Platform -> view.performHapticFeedback(plan.fallback.platformId())
        }
    }

    private fun HapticPlan.Composed.toEffect(): VibrationEffect =
        steps
            .fold(VibrationEffect.startComposition()) { composition, step ->
                composition.addPrimitive(step.primitive.platformId(), step.scale, step.delayMillis)
            }.compose()
}

private fun HapticPrimitive.platformId(): Int =
    when (this) {
        HapticPrimitive.Tick -> VibrationEffect.Composition.PRIMITIVE_TICK
        HapticPrimitive.LowTick -> VibrationEffect.Composition.PRIMITIVE_LOW_TICK
        HapticPrimitive.Click -> VibrationEffect.Composition.PRIMITIVE_CLICK
        HapticPrimitive.Thud -> VibrationEffect.Composition.PRIMITIVE_THUD
        HapticPrimitive.QuickRise -> VibrationEffect.Composition.PRIMITIVE_QUICK_RISE
        HapticPrimitive.QuickFall -> VibrationEffect.Composition.PRIMITIVE_QUICK_FALL
    }

private fun HapticFallback.platformId(): Int =
    when (this) {
        HapticFallback.Tick -> HapticFeedbackConstants.CLOCK_TICK
        HapticFallback.Click -> HapticFeedbackConstants.CONTEXT_CLICK
        HapticFallback.Confirm -> HapticFeedbackConstants.CONFIRM
    }
