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
import dev.geode.feature.playback.api.MAX_CROSSFADE
import dev.geode.feature.playback.api.Queue
import dev.geode.feature.playback.api.RepeatMode
import dev.geode.feature.playback.api.Track
import dev.geode.feature.playback.handlers.HISTORY_LIMIT
import dev.geode.feature.playback.handlers.PlaybackStorage
import dev.geode.feature.playback.handlers.SavedSession
import dev.geode.ui.DeviceTrackQuery
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What the old player kept (#17), as Playback's own terms: where playback was left off, the play
 * history, where each long track was left, and the listening choices.
 */
data class LegacyPlayback(
    val session: SavedSession?,
    /** Oldest first, each with when it was last played (wall-clock time). */
    val plays: List<Pair<Track, Millis>>,
    val resumePoints: Map<TrackId, Millis>,
    val options: ListeningOptions?,
)

/**
 * Moves what the old player kept into Playback's own database, once (ADR-0004: every saved format is
 * migrated and nothing is lost). The old files are read and left as they are.
 */
object LegacyPlaybackImport {
    /** Writes [legacy] into [storage], leaving anything Playback already keeps in place. */
    suspend fun into(
        storage: PlaybackStorage,
        legacy: LegacyPlayback,
        now: Millis,
    ) {
        if (storage.loadSession() == null) legacy.session?.let { storage.saveSession(it.queue, it.position) }
        legacy.plays.takeLast(HISTORY_LIMIT).forEach { (track, at) -> storage.recordPlay(track, at) }
        legacy.resumePoints.forEach { (track, position) -> storage.saveResumePoint(track, position, now) }
        if (storage.loadListening() == null) legacy.options?.let { storage.saveListening(it) }
    }

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
                .map { Track(it.uri, it.title, it.artist, lengthOf(it.uri)) to Millis(it.lastPlayedMs) }
        val prefs = PlayerPrefsStore(files.player).load()
        val options =
            ListeningOptions(
                shuffle = prefs.shuffle,
                repeat = repeatOf(prefs.repeatMode),
                crossfade = Millis(prefs.crossfadeMs.toLong()).coerceAtMost(MAX_CROSSFADE),
                gapless = prefs.gapless,
            )
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
        return stored.loadListening()
    }

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
                    LegacyPlaybackImport.read(context) { lengths[it] ?: Millis.ZERO }
                },
                now = { Millis(System.currentTimeMillis()) },
            )
    }
}
