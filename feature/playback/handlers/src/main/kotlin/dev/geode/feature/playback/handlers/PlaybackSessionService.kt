package dev.geode.feature.playback.handlers

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.geode.feature.playback.api.Playback
import dev.geode.feature.playback.core.MediaControls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

/** The id of Playback's media session, distinct from the one the old player publishes until #17. */
const val PLAYBACK_SESSION_ID = "dev.geode.feature.playback"

/**
 * Music that outlives the screen (#36). The media session over [PlaybackSessionPlayer] lives here,
 * and with it the notification and the lock-screen controls; every button on them sends Playback a
 * Command. While Playback means to play, Media3 keeps this service in the foreground, so leaving the
 * app or locking the phone does not stop the music (ADR-0004).
 *
 * `:app` subclasses it to hand over the one app-scoped [playback] and the [sessionActivity], and
 * declares that subclass in its manifest at the switch-over (#17). Until then nothing starts it.
 */
@OptIn(UnstableApi::class)
abstract class PlaybackSessionService : MediaSessionService() {
    /** The app-scoped Playback store. */
    protected abstract val playback: Playback

    /** What a tap on the notification opens, or null for nothing. */
    protected open fun sessionActivity(): PendingIntent? = null

    protected open val clocks: PlaybackClocks get() = PlaybackClocks.system()

    private var scope: CoroutineScope? = null
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val main = MainScope().also { scope = it }
        val built =
            MediaSession
                .Builder(this, PlaybackSessionPlayer(playback, clocks, main))
                .setId(PLAYBACK_SESSION_ID)
                .apply { sessionActivity()?.let { setSessionActivity(it) } }
                .build()
        session = built
        // Added now rather than when a controller first connects, so the notification follows
        // Playback from the start.
        addSession(built)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /**
     * The app was swiped away. The music goes on if it is playing; otherwise there is nothing to keep
     * the notification for.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!MediaControls.sessionOf(playback.state.value.player).playsInBackground) stopSelf()
    }

    override fun onDestroy() {
        session?.let {
            it.player.release()
            it.release()
        }
        session = null
        scope?.cancel()
        scope = null
        super.onDestroy()
    }
}
