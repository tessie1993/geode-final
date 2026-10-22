package dev.geode.playback

import android.content.Context
import android.content.SharedPreferences
import dev.geode.data.GeodePrefsFiles
import dev.geode.data.HistoryStore
import dev.geode.data.PlayerPrefsStore
import dev.geode.data.SessionStore
import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.playback.api.ListeningOptions
import dev.geode.feature.playback.api.Queue
import dev.geode.feature.playback.api.RepeatMode
import dev.geode.feature.playback.api.Track
import dev.geode.feature.playback.handlers.HISTORY_LIMIT
import dev.geode.feature.playback.handlers.PlaybackStorage
import dev.geode.feature.playback.handlers.SavedSession
import dev.geode.ui.DeviceTrackQuery
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What the old player kept (#17), as Playback's own terms: where playback was left off, the play
 * history, where each long track was left, and the listening choices.
 */
data class LegacyPlayback(
    val session: SavedSession?,
    /** Each song the old player played, oldest first. */
    val plays: List<LegacyPlay>,
    val resumePoints: Map<TrackId, Millis>,
    val options: ListeningOptions?,
)

/** A song in the old play history: played [count] times, the last at [lastPlayedAt] (wall-clock time). */
data class LegacyPlay(
    val track: Track,
    val lastPlayedAt: Millis,
    val count: Int = 1,
)

/**
 * Moves what the old player kept into Playback's own database, once (ADR-0004: every saved format is
 * migrated and nothing is lost). The old files are read and left as they are.
 */
object LegacyPlaybackImport {
    /**
     * Writes [legacy] into [storage], leaving anything Playback already keeps in place. Safe to run
     * again after a run that stopped part way: a play already written is not written twice, and the
     * rest is written over with the same values.
     */
    suspend fun into(
        storage: PlaybackStorage,
        legacy: LegacyPlayback,
        now: Millis,
    ) {
        if (storage.loadSession() == null) legacy.session?.let { storage.saveSession(it.queue, it.position) }
        val kept =
            storage.history
                .first()
                .map { it.track.id to it.playedAt }
                .toSet()
        playsOf(legacy.plays)
            .filterNot { (track, at) -> track.id to at in kept }
            .forEach { (track, at) -> storage.recordPlay(track, at) }
        resumeOrder(legacy.resumePoints).forEachIndexed { index, (track, position) ->
            // One saved after the other, so which are kept when there are too many is always the same.
            storage.saveResumePoint(track, position, now - Millis(index.toLong()))
        }
        if (storage.loadListening() == null) legacy.options?.let { storage.saveListening(it) }
    }

    /**
     * Each old play as one of Playback's, oldest first, the latest [HISTORY_LIMIT] of them. Playback
     * keeps every play rather than a count, so a song played n times becomes n plays: the last at the
     * time the old player kept, the earlier ones just before it (when they really were is not known).
     */
    fun playsOf(plays: List<LegacyPlay>): List<Pair<Track, Millis>> =
        plays
            .flatMap { play ->
                (0 until play.count.coerceAtLeast(1)).map { earlier ->
                    play.track to
                        play.lastPlayedAt - Millis(earlier.toLong())
                }
            }.sortedWith(compareBy<Pair<Track, Millis>> { it.second }.thenBy { it.first.id.value })
            .takeLast(HISTORY_LIMIT)

    /** The old resume points in a fixed order, by song. */
    fun resumeOrder(points: Map<TrackId, Millis>): List<Pair<TrackId, Millis>> = points.toList().sortedBy { it.first.value }

    /** The old player's repeat setting, by its Media3 number. */
    fun repeatOf(old: Int): RepeatMode =
        when (old) {
            REPEAT_ALL -> RepeatMode.ALL
            REPEAT_ONE -> RepeatMode.ONE
            else -> RepeatMode.OFF
        }

    /** Reads what the old player kept on this phone. [lengthOf] gives a song's length when the phone's index knows it. */
    fun read(
        context: Context,
        lengthOf: (TrackId) -> Millis,
    ): LegacyPlayback {
        val files = GeodePrefsFiles(context)
        val session =
            SessionStore(context).load()?.let { saved ->
                val tracks = saved.tracks.map { Track(it.uri, it.title, it.artist, lengthOf(it.uri)) }
                SavedSession(Queue(tracks, saved.index.coerceIn(0, tracks.lastIndex)), saved.position)
            }
        val plays =
            HistoryStore(context)
                .recentlyPlayed(HISTORY_LIMIT)
                .reversed()
                .map { LegacyPlay(Track(it.uri, it.title, it.artist, lengthOf(it.uri)), Millis(it.lastPlayedMs), it.playCount) }
        val prefs = PlayerPrefsStore(files.player).load()
        // Only shuffle and repeat: the crossfade and gapless are Settings', which import them themselves.
        val options = ListeningOptions(shuffle = prefs.shuffle, repeat = repeatOf(prefs.repeatMode))
        return LegacyPlayback(session, plays, resumePointsIn(files.player), options)
    }

    /** The old long-track bookmarks: `bookmark:<uri>` to a position, in the player's preferences. */
    private fun resumePointsIn(prefs: SharedPreferences): Map<TrackId, Millis> =
        prefs.all
            .filterKeys { it.startsWith(BOOKMARK_PREFIX) }
            .mapNotNull { (key, value) ->
                (value as? Long)?.takeIf { it > 0 }?.let {
                    TrackId(key.removePrefix(BOOKMARK_PREFIX)) to
                        Millis(it)
                }
            }.toMap()

    private const val BOOKMARK_PREFIX = "bookmark:"
    private const val REPEAT_ONE = 1
    private const val REPEAT_ALL = 2
}

/**
 * Playback's storage that first brings in what the old player kept, once per install, before
 * Playback reads where it was left off. A failed import is tried again on the next start.
 */
class ImportingPlaybackStorage(
    private val stored: PlaybackStorage,
    private val alreadyImported: () -> Boolean,
    private val markImported: () -> Unit,
    private val legacy: () -> LegacyPlayback,
    private val now: () -> Millis,
) : PlaybackStorage by stored {
    private val once = Mutex()

    @Volatile
    private var ready = false

    private suspend fun imported() {
        if (ready) return
        once.withLock {
            if (ready) return
            if (!alreadyImported()) {
                LegacyPlaybackImport.into(stored, legacy(), now())
                markImported()
            }
            ready = true
        }
    }

    override suspend fun loadSession(): SavedSession? {
        imported()
        return stored.loadSession()
    }

    override suspend fun loadListening(): ListeningOptions? {
        imported()
        return stored.loadListening()?.let(TransitionsFromSettings::withoutTransitions)
    }

    /** The crossfade and gapless are Settings' (#17): Playback keeps no copy of them. */
    override suspend fun saveListening(options: ListeningOptions) =
        stored.saveListening(TransitionsFromSettings.withoutTransitions(options))

    companion object {
        private const val IMPORTED = "playback_imported_v1"

        /** Over [stored], importing from this phone's old player files, remembered in [marks]. */
        fun of(
            context: Context,
            stored: PlaybackStorage,
            marks: SharedPreferences,
        ): ImportingPlaybackStorage =
            ImportingPlaybackStorage(
                stored = stored,
                alreadyImported = { marks.getBoolean(IMPORTED, false) },
                markImported = { marks.edit().putBoolean(IMPORTED, true).apply() },
                legacy = {
                    val lengths = DeviceTrackQuery.query(context).associate { it.uri to it.duration }
                    LegacyPlaybackImport.read(context) { TrackLength.orRead(context, it, lengths[it] ?: Millis.ZERO) }
                },
                now = { Millis(System.currentTimeMillis()) },
            )
    }
}
