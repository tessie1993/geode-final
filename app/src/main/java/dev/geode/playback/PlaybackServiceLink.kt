package dev.geode.playback

import android.content.ComponentName
import android.content.Context
import androidx.annotation.MainThread
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dev.geode.RingLog
import dev.geode.feature.playback.api.Playback
import dev.geode.geodeContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps Playback's media session service ([GeodePlaybackService]) running once Playback first means
 * to play, by connecting to its session (#36).
 *
 * A connection, never a foreground start: `startForegroundService()` promises Android a
 * `startForeground()` within seconds, which Media3 keeps only while something plays, and playback
 * that flips on and straight off again broke that promise. Media3 promotes a bound service itself
 * when playback starts. The connection lasts as long as the process, as Playback's store does.
 */
class PlaybackServiceLink(
    private val context: Context,
    private val playback: Playback,
) {
    private var connection: ListenableFuture<MediaController>? = null

    fun start() {
        val app = context.applicationContext
        app.geodeContainer.appScope.launch {
            playback.state
                .map { it.player.meantToPlay }
                .distinctUntilChanged()
                .filter { it }
                // A MediaController is only touched on the main thread, isConnected included.
                .collect { withContext(Dispatchers.Main.immediate) { connect(app) } }
        }
    }

    @MainThread
    private fun connect(app: Context) {
        val current = connection
        if (current != null && (!current.isDone || runCatching { current.get().isConnected }.getOrDefault(false))) return
        current?.let { MediaController.releaseFuture(it) }
        val token = SessionToken(app, ComponentName(app, GeodePlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        connection = future
        future.addListener(
            {
                runCatching { future.get() }
                    .onFailure { RingLog.note(TAG, "connecting to Playback's session failed", it) }
            },
            app.mainExecutor,
        )
    }

    private companion object {
        const val TAG = "PlaybackServiceLink"
    }
}
