package dev.geode.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import dev.geode.engine.audio.FocusChange
import dev.geode.util.bestEffort

/**
 * Audio focus and the becoming-noisy broadcast for Geode's own player; ExoPlayer handles both for
 * itself. Focus changes and the broadcast arrive on the main thread, where the player runs.
 *
 * The request says the player pauses rather than ducks, so the system never ducks it on its own (it
 * could not anyway once the native output holds an exclusive stream) and every ducking request
 * reaches [onFocusChange], which lowers the volume itself.
 */
internal class PlayerAudioRoute(
    context: Context,
    private val onFocusChange: (FocusChange) -> Unit,
    private val onBecomingNoisy: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val audioManager: AudioManager? = appContext.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private val request: AudioFocusRequest =
        AudioFocusRequest
            .Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            ).setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ code -> FocusChange.fromAndroid(code)?.let(onFocusChange) }, main)
            .build()

    private var holdsFocus = false
    private var noisyRegistered = false

    /** Whether unplugging headphones pauses: the Settings choice (pause when unplugged). */
    var handleNoisy: Boolean = true

    private val noisyReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) onBecomingNoisy()
            }
        }

    /** Asks for focus before playing; true when the music may play. Main thread. */
    fun requestFocus(): Boolean {
        if (holdsFocus) return true
        val manager = audioManager ?: return true
        holdsFocus = manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return holdsFocus
    }

    /** Gives focus back once the player no longer means to play. Main thread. */
    fun abandonFocus() {
        if (!holdsFocus) return
        holdsFocus = false
        audioManager?.abandonAudioFocusRequest(request)
    }

    /** Listens for headphones being unplugged while [playing], when the Settings ask for it. Main thread. */
    fun listenForNoisy(playing: Boolean) {
        val wanted = playing && handleNoisy
        if (wanted == noisyRegistered) return
        noisyRegistered = wanted
        if (wanted) {
            ContextCompat.registerReceiver(
                appContext,
                noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        } else {
            bestEffort(TAG, "unregister the becoming-noisy receiver") { appContext.unregisterReceiver(noisyReceiver) }
        }
    }

    private companion object {
        const val TAG = "PlayerAudioRoute"
    }
}
