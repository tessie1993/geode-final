package dev.geode.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.playback.api.Playback
import dev.geode.feature.playback.api.Track
import dev.geode.feature.playback.handlers.PlaybackSessionService
import dev.geode.ui.OldScreenModels
import dev.geode.widget.WidgetPublisher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.inject.Inject

/**
 * Playback's media session service as the app runs it (#17, #36): the notification, the lock screen,
 * Bluetooth and headset buttons, watches and Android Auto all reach the one app-scoped [playback]
 * through it. It replaces the old player's service.
 *
 * Browsing walks the phone's music ([LibraryTree]); a song a car picks is handed to the session's
 * player, which sends it to Playback as a Command. The home-screen widget follows the session's player.
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class GeodePlaybackService : PlaybackSessionService() {
    // Dagger cannot inject a protected member, so the store comes in here and is handed over below.
    @Inject
    lateinit var store: Playback

    override val playback: Playback get() = store

    private var widget: WidgetPublisher? = null
    private var artwork: SessionBitmapLoader? = null

    /** The songs' own embedded pictures, decoded by the app's loader (see [MediaArtwork]). */
    override fun bitmapLoader(): BitmapLoader = CacheBitmapLoader(SessionBitmapLoader(this).also { artwork = it })

    override fun artworkExtras(track: Track): Bundle = MediaArtwork.embeddedArtExtras(track.id)

    override fun sessionActivity(): PendingIntent {
        val launch =
            packageManager.getLaunchIntentForPackage(packageName)
                ?: Intent(Intent.ACTION_MAIN).setPackage(packageName)
        launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    override fun browsing(): MediaLibrarySession.Callback =
        Browsing(
            LibraryTree(this) {
                playback.state.value.history
                    .map { it.track }
            },
            ::leftOff,
            lengthOf = { TrackLength.of(this, it) },
        )

    /**
     * Where Playback was left off, for the system's "resume" after the app was gone. Playback restores
     * its queue as it starts, so this waits for that, briefly.
     */
    private fun leftOff(): MediaSession.MediaItemsWithStartPosition? {
        val state =
            runBlocking {
                withTimeoutOrNull(RESTORE_WAIT_MS) { playback.state.first { OldScreenModels.queueOf(it.player) != null } }
            } ?: return null
        val queue = OldScreenModels.queueOf(state.player) ?: return null
        val items =
            queue.tracks.map { track ->
                MediaItem
                    .Builder()
                    .setMediaId(track.id.value)
                    .setMediaMetadata(
                        MediaMetadata
                            .Builder()
                            .setTitle(track.title.ifBlank { OldScreenModels.fileNameOf(track.id) })
                            .setArtist(track.artist.ifBlank { null })
                            .setDurationMs(track.duration.value)
                            .setExtras(MediaArtwork.embeddedArtExtras(track.id))
                            .build(),
                    ).build()
            }
        return MediaSession.MediaItemsWithStartPosition(items, queue.index, OldScreenModels.positionAt(state.player, Millis.ZERO).value)
    }

    override fun onSessionBuilt(player: Player) {
        widget =
            WidgetPublisher(this, player).also {
                player.addListener(it)
                it.publish()
            }
    }

    override fun onSessionReleasing(player: Player) {
        widget?.let {
            player.removeListener(it)
            it.clear()
        }
        widget = null
        artwork?.release()
        artwork = null
    }

    /** Browsing answers on its own thread: the tree reads files and the phone's media index. */
    private class Browsing(
        private val tree: LibraryTree,
        private val leftOff: () -> MediaSession.MediaItemsWithStartPosition?,
        private val lengthOf: (TrackId) -> Millis,
    ) : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            // A browser coming back to the root (a new car connection, a refresh) picks up library edits.
            tree.invalidate()
            return Futures.immediateFuture(LibraryResult.ofItem(tree.root(), params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            background {
                val all = tree.children(parentId)
                // In Long: Media3 asks for "everything" with pageSize = Integer.MAX_VALUE.
                val from = (page.toLong() * pageSize.toLong()).coerceIn(0L, all.size.toLong()).toInt()
                val until = (from.toLong() + pageSize.toLong()).coerceIn(from.toLong(), all.size.toLong()).toInt()
                LibraryResult.ofItemList(ImmutableList.copyOf(all.subList(from, until)), params)
            }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            background { tree.item(mediaId)?.let { LibraryResult.ofItem(it, null) } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE) }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> = background { mediaItems.map { song(resolve(it)) } }

        /** A song tapped in a browsed folder plays that whole folder from the song, as the Library does. */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            background {
                val single = mediaItems.singleOrNull()?.takeIf { it.localConfiguration == null }
                val folder = single?.let { tree.queueFor(it.mediaId) }
                if (folder != null) {
                    MediaSession.MediaItemsWithStartPosition(folder.first.map(::song), folder.second, startPositionMs)
                } else {
                    MediaSession.MediaItemsWithStartPosition(mediaItems.map { song(resolve(it)) }, startIndex, startPositionMs)
                }
            }

        /** Picking up where the user left off from the system's media controls, after the app was gone. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            // Nothing was ever played (a cold start on a fresh install): an empty queue, not a failure.
            background { leftOff() ?: NOTHING_LEFT_OFF }

        private fun resolve(item: MediaItem): MediaItem = if (item.localConfiguration != null) item else tree.playable(item.mediaId) ?: item

        /**
         * The item as Playback's session player reads it: the song's own id, not its place in the tree,
         * and its length, from the file when the media index did not give one, so it can be sought.
         */
        private fun song(item: MediaItem): MediaItem {
            val id = item.localConfiguration?.uri?.toString() ?: return item
            val length = item.mediaMetadata.durationMs?.takeIf { it > 0 } ?: lengthOf(TrackId(id)).value
            return item
                .buildUpon()
                .setMediaId(id)
                .setMediaMetadata(
                    item.mediaMetadata
                        .buildUpon()
                        .setTitle(item.mediaMetadata.title ?: OldScreenModels.fileNameOf(TrackId(id)))
                        .setDurationMs(length.takeIf { it > 0 })
                        .build(),
                ).build()
        }

        private fun <T> background(work: () -> T): ListenableFuture<T> = Futures.submit(Callable { work() }, browsingThread)

        private companion object {
            val NOTHING_LEFT_OFF = MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L)

            val browsingThread = Executors.newSingleThreadExecutor { r -> Thread(r, "geode-browsing").apply { isDaemon = true } }
        }
    }

    private companion object {
        const val RESTORE_WAIT_MS = 2_000L
    }
}
