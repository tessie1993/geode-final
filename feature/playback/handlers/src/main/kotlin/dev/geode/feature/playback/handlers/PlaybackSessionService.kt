package dev.geode.feature.playback.handlers

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import dev.geode.feature.playback.api.Playback
import dev.geode.feature.playback.api.Track
import dev.geode.feature.playback.core.MediaControls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

/** The id of Playback's media session. */
const val PLAYBACK_SESSION_ID = "dev.geode.feature.playback"

/**
 * Music that outlives the screen (#36). The media session over [PlaybackSessionPlayer] lives here,
 * and with it the notification and the lock-screen controls; every button on them sends Playback a
 * Command. While Playback means to play, Media3 keeps this service in the foreground, so leaving the
 * app or locking the phone does not stop the music (ADR-0004).
 *
 * It is also a media library (#17), so a car or another media browser can look through the music
 * and hand songs to the session's player, which sends them to Playback as Commands too.
 *
 * `:app` subclasses it to hand over the one app-scoped [playback], the [sessionActivity] and what
 * [browsing] shows, and declares that subclass in its manifest.
 */
@OptIn(UnstableApi::class)
abstract class PlaybackSessionService : MediaLibraryService() {
    /** The app-scoped Playback store. */
    protected abstract val playback: Playback

    /** What a tap on the notification opens, or null for nothing. */
    protected open fun sessionActivity(): PendingIntent? = null

    protected open val clocks: PlaybackClocks get() = PlaybackClocks.system()

    /** What a media browser sees and what it may hand over; by default there is nothing to browse. */
    protected open fun browsing(): MediaLibrarySession.Callback = object : MediaLibrarySession.Callback {}

    /** Loads the artwork the notification and the lock screen show; null keeps Media3's own. */
    protected open fun bitmapLoader(): BitmapLoader? = null

    /** What [bitmapLoader] needs in [track]'s metadata to find its artwork. */
    protected open fun artworkExtras(track: Track): Bundle? = null

    /** The session was built over [player]: the place to follow it, as the home-screen widget does. */
    protected open fun onSessionBuilt(player: Player) = Unit

    /** The session over [player] is about to be released. */
    protected open fun onSessionReleasing(player: Player) = Unit

    private var scope: CoroutineScope? = null
    private var session: MediaLibrarySession? = null

    override fun onCreate() {
        super.onCreate()
        val main = MainScope().also { scope = it }
        val built =
            MediaLibrarySession
                .Builder(this, PlaybackSessionPlayer(playback, clocks, main, ::artworkExtras), browsing())
                .setId(PLAYBACK_SESSION_ID)
                .apply { sessionActivity()?.let { setSessionActivity(it) } }
                .apply { bitmapLoader()?.let { setBitmapLoader(it) } }
                .build()
        session = built
        // Added now rather than when a controller first connects, so the notification follows
        // Playback from the start.
        addSession(built)
        onSessionBuilt(built.player)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    /**
     * The app was swiped away. The music goes on if it is playing; otherwise there is nothing to keep
     * the notification for.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!MediaControls.sessionOf(playback.state.value.player).playsInBackground) stopSelf()
    }

    override fun onDestroy() {
        session?.let {
            onSessionReleasing(it.player)
            it.player.release()
            it.release()
        }
        session = null
        scope?.cancel()
        scope = null
        super.onDestroy()
    }
}
