package dev.geode.playback

import dev.geode.audio.adapter.NativeAudioAdapter
import dev.geode.engine.types.Millis
import dev.geode.feature.playback.api.Playback
import dev.geode.feature.playback.api.SleepTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The sleep timer's fade-out (the old player's, #17): over its last [FADE] the music fades to silence,
 * then Playback pauses it as the timer says. Only a timer that pauses when it runs out fades; one that
 * lets the track finish does not, as before. Cancelling the timer mid-fade brings the volume back.
 * The fade is the audio adapter's volume; the engine is not touched.
 */
object SleepFadeOut {
    /** How long before the timer runs out the music starts fading. */
    val FADE = Millis(3_000)

    fun follow(
        playback: Playback,
        audio: NativeAudioAdapter,
        scope: CoroutineScope,
    ) {
        var fading = false
        scope.launch {
            playback.state.map { it.sleep }.distinctUntilChanged().collectLatest { sleep ->
                val counting = sleep as? SleepTimer.Counting
                if (counting == null || counting.finishTrack) {
                    // Off because it ran out pauses at once; off because it was cancelled plays on, loud again.
                    if (fading && sleep == SleepTimer.Off && wantsToPlay(playback)) audio.endSleepFade()
                    fading = false
                    return@collectLatest
                }
                delay(fadeStartsIn(counting.remaining).value)
                fading = true
                audio.fadeOutForSleep(minOf(FADE, counting.remaining))
                awaitCancellation()
            }
        }
    }

    /** How long from now, with [remaining] left on the timer, the fade starts. */
    fun fadeStartsIn(remaining: Millis): Millis = (remaining - FADE).coerceAtLeast(Millis.ZERO)

    private fun wantsToPlay(playback: Playback): Boolean =
        dev.geode.ui.OldScreenModels
            .meantToPlay(playback.state.value.player)
}
