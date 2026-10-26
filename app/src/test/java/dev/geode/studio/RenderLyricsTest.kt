package dev.geode.studio

import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.studio.api.NewVideo
import dev.geode.feature.studio.api.RenderExtras
import dev.geode.feature.studio.api.RenderLength
import dev.geode.feature.studio.api.RenderTrack
import dev.geode.feature.studio.api.VideoKind
import dev.geode.feature.studio.api.edit.ClipContent
import dev.geode.feature.studio.api.edit.LaneKind
import dev.geode.feature.studio.api.edit.Subtitles
import dev.geode.feature.studio.api.edit.TimedLyric
import dev.geode.feature.studio.core.RenderFinish
import dev.geode.ui.LyricsLoader
import org.junit.Assert.assertEquals
import org.junit.Test

/** A Render's lyric captions from an `.lrc` file (#329): parsed timings land at the lines' times in the Edit. */
class RenderLyricsTest {
    private val lrc =
        """
        [ti:Night Drive]
        [ar:The Neon]
        [00:02.50]Lights on the road
        [00:07]We drive all night
        [00:12.250][00:20.1]Into the dawn
        """.trimIndent()

    @Test
    fun `an lrc's lines become captions at their times`() {
        val lyrics = checkNotNull(LyricsLoader.parse(lrc, "an .lrc file next to the track"))
        val cues = Subtitles.fromLyrics(lyrics.lines.map { TimedLyric(it.timeMs, it.text) }, 30_000L)
        val track = RenderTrack(TrackId("content://media/12"), "Night Drive", Millis(30_000), "The Neon")
        val video = NewVideo(VideoKind.RENDER, "Night Drive", "content://media/41", null, Millis(30_000), 1920, 1080, "Night Drive")

        val cut = checkNotNull(RenderFinish.cutOf(video, track, RenderLength.WholeTrack, RenderExtras(captions = true), cues))

        val captions =
            cut.timeline.lanes
                .single { it.kind == LaneKind.Text }
                .clips
        assertEquals(listOf(2_500L, 7_000L, 12_250L, 20_100L), captions.map { it.startMs })
        assertEquals(
            listOf("Lights on the road", "We drive all night", "Into the dawn", "Into the dawn"),
            captions.map { (it.content as ClipContent.Text).text },
        )
    }
}
