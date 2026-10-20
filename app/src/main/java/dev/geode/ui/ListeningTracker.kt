package dev.geode.ui

import android.app.Application
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import dev.geode.data.FavouritesRepository
import dev.geode.data.HistoryStore
import dev.geode.data.SessionRepository
import dev.geode.data.SessionStore
import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.playback.toTrackId
import dev.geode.playback.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class ListeningTracker(
    application: Application,
    private val favouritesRepository: FavouritesRepository,
    private val sessionRepository: SessionRepository,
    private val storeScope: CoroutineScope,
    private val host: Host,
) {
    interface Host {
        val player: Player
        val currentUri: Uri?

        fun mediaItemFor(uri: Uri): MediaItem

        fun mediaItemFor(track: QueueTrack): MediaItem
    }

    private val historyStore = HistoryStore(application)

    private val _historyTick = MutableStateFlow(0)
    val historyTick: StateFlow<Int> = _historyTick

    val favourites: StateFlow<Set<TrackId>> = favouritesRepository.favourites

    private var listenTickAtMs = 0L
    private var listenTickUri: TrackId? = null
    private var lastSessionWriteMs = Long.MIN_VALUE
    private var lastSessionIndex = -1

    fun recentlyPlayed() = historyStore.recentlyPlayed()

    fun recentlyPlayed(limit: Int) = historyStore.recentlyPlayed(limit)

    fun playCountOf(uri: TrackId): Int = historyStore.entryFor(uri)?.playCount ?: 0

    fun toggleFavourite(uri: TrackId) {
        storeScope.launch {
            favouritesRepository.toggle(uri)
            _historyTick.update { it + 1 }
        }
    }

    fun recordPlay(
        uri: TrackId,
        title: String,
        artist: String,
    ) {
        flushListenTime()
        historyStore.recordPlay(uri, title, artist)
        _historyTick.update { it + 1 }
    }

    fun accrueListenTime() {
        val uri = host.currentUri?.toTrackId()
        val now = System.currentTimeMillis()
        val playing = host.player.isPlaying && uri != null
        if (!playing || uri != listenTickUri) {
            if (listenTickAtMs != 0L) historyStore.flush()
            listenTickAtMs = if (playing) now else 0L
            listenTickUri = if (playing) uri else null
            return
        }
        val delta = now - listenTickAtMs
        listenTickAtMs = now
        if (delta in 1..MAX_LISTEN_TICK_MS) historyStore.addListenTime(uri, Millis(delta))
    }

    fun flushListenTime() {
        accrueListenTime()
        historyStore.flush()
    }

    fun awaitHistoryWrites(timeoutMs: Long) = historyStore.awaitWrites(timeoutMs)

    @Suppress("ReturnCount")
    suspend fun prepareLastPlayed(): Uri? {
        val player = host.player
        val saved = sessionRepository.load()
        if (saved != null) {
            val restored =
                runCatching {
                    player.setMediaItems(
                        saved.tracks.map { host.mediaItemFor(QueueTrack(it.uri, it.title, it.artist)) },
                        saved.index,
                        saved.position.value,
                    )
                    player.prepare()
                    saved.tracks[saved.index].uri.toUri()
                }.getOrNull()
            if (restored != null) return restored
        }
        val last = historyStore.recentlyPlayed(1).firstOrNull() ?: return null
        return runCatching {
            val uri = last.uri.toUri()
            player.setMediaItems(listOf(host.mediaItemFor(uri)))
            player.prepare()
            uri
        }.getOrNull()
    }

    fun persistSession() {
        val player = host.player
        val count = runCatching { player.mediaItemCount }.getOrDefault(0)
        if (count == 0) return
        val index = runCatching { player.currentMediaItemIndex }.getOrDefault(0)
        val position = runCatching { player.currentPosition }.getOrDefault(0L)
        val movedTrack = index != lastSessionIndex
        if (!movedTrack && position - lastSessionWriteMs in 0 until SessionStore.POSITION_WRITE_INTERVAL_MS) return
        lastSessionWriteMs = position
        lastSessionIndex = index

        val tracks =
            (0 until count).mapNotNull { i ->
                val item = runCatching { player.getMediaItemAt(i) }.getOrNull() ?: return@mapNotNull null
                val uri = item.localConfiguration?.uri?.toTrackId() ?: return@mapNotNull null
                SessionStore.SavedTrack(
                    uri = uri,
                    title =
                        item.mediaMetadata.title
                            ?.toString()
                            .orEmpty(),
                    artist =
                        item.mediaMetadata.artist
                            ?.toString()
                            .orEmpty(),
                )
            }
        storeScope.launch { sessionRepository.save(SessionStore.Saved(tracks, index, Millis(position))) }
    }

    private companion object {
        const val MAX_LISTEN_TICK_MS = 5_000L
    }
}
