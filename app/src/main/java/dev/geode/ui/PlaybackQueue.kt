package dev.geode.ui

import dev.geode.engine.types.TrackId

object PlaybackQueue {
    const val MAX_QUEUE = 1001

    data class Window(
        val tracks: List<QueueTrack>,
        val startIndex: Int,
    )

    fun window(
        tracks: List<QueueTrack>,
        startUri: TrackId,
    ): Window {
        val at = tracks.indexOfFirst { it.uri == startUri }
        if (at < 0) return Window(listOf(QueueTrack(startUri)), 0)
        if (tracks.size <= MAX_QUEUE) return Window(tracks, at)
        val half = MAX_QUEUE / 2
        val from = (at - half).coerceIn(0, tracks.size - MAX_QUEUE)
        return Window(tracks.subList(from, from + MAX_QUEUE), at - from)
    }

    fun queueTrack(track: DeviceTrack): QueueTrack = QueueTrack(track.uri, track.title, track.artist, track.duration)
}
