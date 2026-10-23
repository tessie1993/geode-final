package dev.geode.feature.playback.ui

import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.playback.api.AbLoop
import dev.geode.feature.playback.api.Bookmark
import dev.geode.feature.playback.api.BookmarkId
import dev.geode.feature.playback.api.QueueEnd
import dev.geode.feature.playback.api.RepeatMode
import dev.geode.feature.playback.api.SleepTimer
import dev.geode.feature.playback.api.Track

/** The models the queue and listening tools previews draw (#35). */
internal object ListeningPreviewModels {
    private val night = Track(TrackId("content://preview/1"), "Night Drive", "Ann Arbor", Millis(215_000))
    private val tide = Track(TrackId("content://preview/2"), "Low Tide", "Bob Marsh", Millis(187_000))
    private val glass = Track(TrackId("content://preview/3"), "Glass Garden", "Cy Lune", Millis(242_000))
    private val lecture = Track(TrackId("content://preview/4"), "On Stones, Part One", "Dee Quarry", Millis(3_720_000))

    val queue = QueueModel(listOf(night, tide, glass, lecture), current = 1, shuffle = true, repeat = RepeatMode.ALL)
    val queueEmpty = QueueModel.EMPTY

    val tools =
        ListeningModel(
            sleep = SleepTimer.Off,
            crossfade = Millis.ZERO,
            gapless = true,
            queueEnd = QueueEnd.STOP,
            loop = AbLoop.Off,
            trackOpen = true,
            position = Millis(64_000),
            bookmarks = emptyList(),
        )
    val toolsInUse =
        tools.copy(
            sleep = SleepTimer.Counting(Millis(840_000), finishTrack = false),
            crossfade = Millis(4_000),
            queueEnd = QueueEnd.SIMILAR,
            loop = AbLoop.On(tide.id, Millis(50_000), Millis(80_000)),
            bookmarks = listOf(Bookmark(BookmarkId(2), lecture, Millis(1_520_000)), Bookmark(BookmarkId(1), night, Millis(42_000))),
        )
}
