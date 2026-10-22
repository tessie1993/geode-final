package dev.geode.feature.playback.handlers

import android.os.SystemClock
import dev.geode.audio.adapter.AudioAdapter
import dev.geode.audio.adapter.PlaybackAnnouncement
import dev.geode.audio.adapter.PlaybackMark
import dev.geode.engine.types.Millis
import dev.geode.feature.playback.api.ListeningOptions
import dev.geode.feature.playback.api.Lyrics
import dev.geode.feature.playback.api.Notice
import dev.geode.feature.playback.api.PlayMark
import dev.geode.feature.playback.api.Playback
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.feature.playback.api.PlaybackFact
import dev.geode.feature.playback.api.PlaybackState
import dev.geode.feature.playback.api.ShuffleOrder
import dev.geode.feature.playback.core.EngineStep
import dev.geode.feature.playback.core.PlaybackEvent
import dev.geode.feature.playback.core.PlaybackStoreReducer
import dev.geode.feature.playback.core.PlaybackWork
import dev.geode.feature.playback.core.ResumeChoice
import dev.geode.feature.playback.core.SIMILAR_BATCH
import dev.geode.feature.playback.core.StorageFailure
import dev.geode.feature.playback.core.parseLyrics
import dev.geode.feature.playback.core.pickSimilar
import dev.geode.feature.playback.core.resumeChoice
import dev.geode.feature.playback.core.waveformOf
import dev.geode.kernel.EffectRunner
import dev.geode.kernel.FeatureFailure
import dev.geode.kernel.FeatureRuntime
import dev.geode.kernel.FeatureStore
import dev.geode.kernel.Perform
import dev.geode.kernel.Results
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlin.random.Random

/** The feature name failures are reported under. */
const val PLAYBACK = "playback"

/**
 * The clocks the handlers read, never the reducer: [elapsed] is the monotonic clock the audio
 * adapter's marks use, and [wall] is the time of day the history records.
 */
class PlaybackClocks(
    val elapsed: () -> Millis,
    val wall: () -> Millis,
) {
    companion object {
        fun system(): PlaybackClocks =
            PlaybackClocks(elapsed = { Millis(SystemClock.elapsedRealtime()) }, wall = { Millis(System.currentTimeMillis()) })
    }
}

/**
 * Carries out Playback's work (ADR-0005): it drives the audio adapter and turns what it announces
 * into events (ADR-0004: the adapter announces, nothing polls), and keeps the playlists, the history
 * and the saved session in [storage]. Every failure is caught here and reported as a named event.
 *
 * For the Player (#147) it reads the current track's lyrics from [lyrics] and its loudness from
 * [loudness], and hears every failure reported to the app-wide error channel on [failures], for the
 * banner.
 */
class PlaybackHandlers(
    private val audio: AudioAdapter,
    private val storage: PlaybackStorage,
    private val clocks: PlaybackClocks,
    private val lyrics: LyricsFiles = LyricsFiles.NONE,
    private val loudness: TrackLoudness = TrackLoudness.NONE,
    private val failures: Flow<FeatureFailure> = emptyFlow(),
) : EffectRunner<PlaybackWork, PlaybackEvent> {
    override suspend fun run(
        work: PlaybackWork,
        results: Results<PlaybackEvent>,
    ) {
        when (work) {
            PlaybackWork.Listen ->
                audio.announcements
                    .filterIsInstance<PlaybackAnnouncement>()
                    .collect { results.report(it.toEvent()) }
            PlaybackWork.Watch -> watch(results)
            PlaybackWork.Restore ->
                guarded(results, StorageFailure.READ) {
                    storage.loadSession()?.let { results.report(PlaybackEvent.Restored(it.queue, it.position)) }
                }
            is PlaybackWork.Drive -> drive(work.steps, results)
            is PlaybackWork.RecordPlay -> guarded(results, StorageFailure.RECORD_PLAY) { storage.recordPlay(work.track, clocks.wall()) }
            is PlaybackWork.SaveSession ->
                guarded(results, StorageFailure.SAVE_SESSION) {
                    storage.saveSession(work.queue, work.mark.positionAt(clocks.elapsed()))
                }
            is PlaybackWork.OpenPlaylist ->
                guarded(results, StorageFailure.READ) { results.report(PlaybackEvent.PlaylistOpened(storage.playlistTracks(work.id))) }
            is PlaybackWork.CreatePlaylist ->
                guarded(results, StorageFailure.SAVE_PLAYLIST) {
                    storage.createPlaylist(work.name, work.tracks, clocks.wall())
                    results.report(PlaybackEvent.PlaylistSaved(work.name))
                }
            is PlaybackWork.DeletePlaylist -> guarded(results, StorageFailure.DELETE_PLAYLIST) { storage.deletePlaylist(work.id) }
            // The listening tools (#35).
            PlaybackWork.RestoreListening ->
                guarded(results, StorageFailure.READ) {
                    results.report(PlaybackEvent.ListeningRestored(storage.loadListening() ?: ListeningOptions()))
                }
            is PlaybackWork.SaveListening -> guarded(results, StorageFailure.SAVE_LISTENING) { storage.saveListening(work.options) }
            is PlaybackWork.AddBookmark ->
                guarded(results, StorageFailure.SAVE_BOOKMARK) {
                    val position = work.mark.positionAt(clocks.elapsed()).coerceIn(Millis.ZERO, work.track.duration)
                    storage.addBookmark(work.track, position, clocks.wall())
                }
            is PlaybackWork.DeleteBookmark -> guarded(results, StorageFailure.DELETE_BOOKMARK) { storage.deleteBookmark(work.id) }
            is PlaybackWork.SaveResumePoint -> guarded(results, StorageFailure.SAVE_RESUME_POINT) { keep(work) }
            is PlaybackWork.ForgetResumePoint ->
                guarded(results, StorageFailure.SAVE_RESUME_POINT) { storage.forgetResumePoint(work.track) }
            is PlaybackWork.PickSimilar ->
                guarded(results, StorageFailure.READ) {
                    results.report(PlaybackEvent.SimilarPicked(pickSimilar(work.queue, storage.knownTracks(), SIMILAR_BATCH)))
                }
            // What the Player shows beside the music (#147).
            is PlaybackWork.FindLyrics -> results.report(PlaybackEvent.LyricsFound(work.track, findLyrics(work)))
            is PlaybackWork.FindWaveform ->
                results.report(PlaybackEvent.WaveformFound(work.track, unlessUnreadable { loudness.levels(work.track) }?.let(::waveformOf)))
            PlaybackWork.WatchFailures -> failures.collect { results.report(PlaybackEvent.FailureReported(Notice(it.feature, it.message))) }
        }
    }

    /** The first lyrics found with any words; a file that cannot be read is no lyrics, as in the old player. */
    private suspend fun findLyrics(work: PlaybackWork.FindLyrics): Lyrics? =
        unlessUnreadable { lyrics.find(work.track) }.orEmpty().firstNotNullOfOrNull { parseLyrics(it.text, it.source) }

    /** What [read] gives, or null when it throws: a detail that cannot be read is not a failure for the banner. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // Handlers catch everything at their edge (ADR-0005).
    private suspend fun <T : Any> unlessUnreadable(read: suspend () -> T?): T? =
        try {
            read()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unreadable: Exception) {
            null
        }

    private suspend fun keep(work: PlaybackWork.SaveResumePoint) {
        val position = work.mark.positionAt(clocks.elapsed())
        when (resumeChoice(position, work.track.duration)) {
            ResumeChoice.KEEP -> storage.saveResumePoint(work.track.id, position, clocks.wall())
            ResumeChoice.FORGET -> storage.forgetResumePoint(work.track.id)
            ResumeChoice.SKIP -> Unit
        }
    }

    private suspend fun watch(results: Results<PlaybackEvent>) =
        coroutineScope {
            launch { storage.playlists.follow(results) { PlaybackEvent.PlaylistsChanged(it) } }
            launch { storage.history.follow(results) { PlaybackEvent.HistoryChanged(it) } }
            launch { storage.bookmarks.follow(results) { PlaybackEvent.BookmarksChanged(it) } }
            launch { storage.resumePoints.follow(results) { PlaybackEvent.ResumePointsChanged(it) } }
        }

    private suspend fun <T> Flow<T>.follow(
        results: Results<PlaybackEvent>,
        toEvent: (T) -> PlaybackEvent,
    ) = catch { results.report(PlaybackEvent.StorageFailed(StorageFailure.READ)) }.collect { results.report(toEvent(it)) }

    /** Asks the adapter for each step in order; a step the adapter throws on is a failed player. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // Handlers catch everything at their edge (ADR-0005).
    private suspend fun drive(
        steps: List<EngineStep>,
        results: Results<PlaybackEvent>,
    ) {
        try {
            steps.forEach { step(it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            results.report(PlaybackEvent.Failed)
        }
    }

    private suspend fun step(step: EngineStep) =
        when (step) {
            is EngineStep.Load -> audio.load(step.track, step.from.positionAt(clocks.elapsed()))
            EngineStep.Play -> audio.setPlaying(true)
            EngineStep.Pause -> audio.setPlaying(false)
            is EngineStep.Seek -> audio.seekTo(step.position)
            is EngineStep.Transitions -> audio.setTransitions(step.crossfade, step.gapless)
            is EngineStep.PrepareNext -> audio.prepareNext(step.track)
        }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // Handlers catch everything at their edge (ADR-0005).
    private suspend fun guarded(
        results: Results<PlaybackEvent>,
        failure: StorageFailure,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            results.report(PlaybackEvent.StorageFailed(failure))
        }
    }

    private fun PlaybackAnnouncement.toEvent(): PlaybackEvent =
        when (this) {
            is PlaybackAnnouncement.Started -> PlaybackEvent.Started(mark.toMark())
            is PlaybackAnnouncement.Paused -> PlaybackEvent.Paused(mark.toMark())
            is PlaybackAnnouncement.Seeked -> PlaybackEvent.Seeked(mark.toMark())
            is PlaybackAnnouncement.TrackChanged -> PlaybackEvent.TrackChanged(track, mark.toMark())
            PlaybackAnnouncement.Ended -> PlaybackEvent.Ended
            // Why it failed matters to nobody: the track is skipped or reloaded either way (#34).
            is PlaybackAnnouncement.Failed -> PlaybackEvent.Failed
        }

    private fun PlaybackMark.toMark() = PlayMark(position, at, moving)
}

/**
 * How `:app` builds Playback's one app-scoped store: it listens to the adapter, follows the playlists,
 * the history, the bookmarks and the resume points, and opens where playback was left off with the
 * listening options saved last, all from the start. [shuffleSeed] starts the shuffles, so that each
 * run of the app shuffles differently.
 *
 * For the Player (#147) it looks up each current track's [lyrics] and [loudness], and shows the
 * failures every feature reports to the runtime's error channel in the app-wide banner.
 */
fun playbackStore(
    audio: AudioAdapter,
    storage: PlaybackStorage,
    clocks: PlaybackClocks,
    runtime: FeatureRuntime,
    shuffleSeed: Long = Random.nextLong(),
    lyrics: LyricsFiles = LyricsFiles.NONE,
    loudness: TrackLoudness = TrackLoudness.NONE,
): Playback =
    FeatureStore<PlaybackState, PlaybackCommand, PlaybackEvent, PlaybackWork, PlaybackFact>(
        name = PLAYBACK,
        initial = PlaybackState(shuffle = ShuffleOrder(seed = shuffleSeed)),
        reducer = PlaybackStoreReducer,
        toEvent = PlaybackEvent::Command,
        runner = PlaybackHandlers(audio, storage, clocks, lyrics, loudness, runtime.errors.failures),
        runtime = runtime,
        start =
            listOf(
                Perform(PlaybackWork.WatchFailures),
                Perform(PlaybackWork.Listen),
                Perform(PlaybackWork.Watch),
                Perform(PlaybackWork.RestoreListening),
                Perform(PlaybackWork.Restore),
            ),
    )
