package dev.geode.audio.adapter

import dev.geode.engine.types.Millis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What a play/pause fade drives: the player's volume and its play/pause, nothing else. */
internal interface FadedPlayer {
    var volume: Float
    val playWhenReady: Boolean
    val isPlaying: Boolean

    fun play()

    fun pause()
}

/**
 * The play/pause fade (the old player's fades, #17): in from where the volume is on play, out to
 * silence before a pause. There is only ever one fade: a new play or pause cancels the one running
 * and starts from the volume it reached, so a pause that finishes its fade can never pause a play
 * asked for since. A load or a seek [settle]s it at once instead.
 *
 * Everything runs on [scope]'s thread, the player's (main) thread, so a cancelled fade does nothing more.
 */
internal class PlayPauseFade(
    private val scope: CoroutineScope,
    private val stepMs: Long = STEP_MS,
) {
    private var running: Job? = null
    private var end: (() -> Unit)? = null

    /** Plays or pauses [player], fading over [length], or at once when [length] is zero. */
    fun setPlaying(
        player: FadedPlayer,
        playing: Boolean,
        length: Millis,
    ) {
        cancel()
        when {
            length <= Millis.ZERO && playing -> {
                player.volume = 1f
                player.play()
            }
            // Paused before the volume comes back, so music faded out (the sleep timer's) stays silent.
            length <= Millis.ZERO -> pauseAtFullVolume(player)
            playing -> {
                // Going on from a pause still fading out starts where its volume got to.
                val from = if (player.playWhenReady) player.volume else 0f
                player.volume = from
                player.play()
                fade(player, from, 1f, length) { player.volume = 1f }
            }
            player.isPlaying -> fade(player, player.volume, 0f, length) { pauseAtFullVolume(player) }
            else -> pauseAtFullVolume(player)
        }
    }

    /**
     * Fades what plays out to silence over [length] without pausing it: the sleep timer's last seconds
     * (the old player's sleep fade, #17), before the pause it asks Playback for. A play or pause asked
     * for meanwhile takes over from the volume it reached.
     */
    fun fadeOut(
        player: FadedPlayer,
        length: Millis,
    ) {
        if (!player.isPlaying) return
        cancel()
        fade(player, player.volume, 0f, length.coerceAtLeast(Millis(stepMs))) {}
    }

    /** Stops any fade and puts the volume back: the sleep timer was cancelled while it faded out. */
    fun restore(player: FadedPlayer) {
        cancel()
        player.volume = 1f
    }

    /** Ends the running fade where it was heading, at once: a pause pauses now, a play is at full volume. */
    fun settle() {
        val finish = end
        cancel()
        finish?.invoke()
    }

    private fun cancel() {
        running?.cancel()
        running = null
        end = null
    }

    private fun fade(
        player: FadedPlayer,
        from: Float,
        to: Float,
        length: Millis,
        finish: () -> Unit,
    ) {
        end = finish
        running =
            scope.launch {
                val steps = (length.value / stepMs).coerceAtLeast(1)
                for (step in 1..steps) {
                    delay(stepMs)
                    player.volume = (from + (to - from) * step / steps).coerceIn(0f, 1f)
                }
                running = null
                end = null
                finish()
            }
    }

    private fun pauseAtFullVolume(player: FadedPlayer) {
        player.pause()
        player.volume = 1f
    }

    private companion object {
        const val STEP_MS = 25L
    }
}
