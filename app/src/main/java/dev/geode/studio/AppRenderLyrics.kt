package dev.geode.studio

import android.content.Context
import dev.geode.feature.studio.api.RenderTrack
import dev.geode.feature.studio.api.edit.SubtitleCue
import dev.geode.feature.studio.api.edit.Subtitles
import dev.geode.feature.studio.api.edit.TimedLyric
import dev.geode.feature.studio.handlers.RenderLyrics
import dev.geode.playback.toUri
import dev.geode.ui.LyricsLoader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * A Render's lyric captions (#329): the Track's synced lyrics from an `.lrc` file beside it or its own
 * tags, read on [io], each line shown until the next. Nothing is looked up online; lyrics without times
 * make no captions.
 */
class AppRenderLyrics(
    private val context: Context,
    private val io: CoroutineDispatcher,
) : RenderLyrics {
    override suspend fun cues(track: RenderTrack): List<SubtitleCue> =
        withContext(io) {
            val lyrics = LyricsLoader.load(context, track.id.toUri())
            if (lyrics == null || !lyrics.synced) {
                emptyList()
            } else {
                Subtitles.fromLyrics(lyrics.lines.map { TimedLyric(it.timeMs, it.text) }, track.length.value)
            }
        }
}
