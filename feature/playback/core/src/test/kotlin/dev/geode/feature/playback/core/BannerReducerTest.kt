package dev.geode.feature.playback.core

import dev.geode.engine.types.Millis
import dev.geode.feature.playback.api.BANNER_VISIBLE
import dev.geode.feature.playback.api.Banner
import dev.geode.feature.playback.api.Banner.Hidden
import dev.geode.feature.playback.api.Banner.Showing
import dev.geode.feature.playback.api.Banner.Waiting
import dev.geode.feature.playback.api.Notice
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.feature.playback.api.PlaybackState
import dev.geode.kernel.CancelTimer
import dev.geode.kernel.Next
import dev.geode.kernel.StartTimer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The app-wide error banner (#147, ADR-0004) as a transition table: every banner state against every
 * banner event. One notice at a time, never stacking; it shows for [BANNER_VISIBLE] once on screen.
 */
class BannerReducerTest {
    private val old = Notice("library", "Could not read the music")
    private val new = Notice("playback", "Could not save the playlist")

    private val reported = PlaybackEvent.FailureReported(new)
    private val shown = PlaybackEvent.Command(PlaybackCommand.BannerShown)
    private val dismiss = PlaybackEvent.Command(PlaybackCommand.DismissBanner)
    private val timedOut = PlaybackEvent.BannerTimedOut

    private val arm = StartTimer(BannerTimeout, BANNER_VISIBLE, PlaybackEvent.BannerTimedOut)
    private val disarm = CancelTimer(BannerTimeout)

    private val states: List<Banner> = listOf(Hidden, Waiting(old), Showing(old))
    private val events: List<PlaybackEvent> = listOf(reported, shown, dismiss, timedOut)

    private val table =
        listOf(
            // A failure: it waits to be seen, or replaces the one on screen and shows for the full time.
            row(Hidden, reported, Waiting(new)),
            row(Waiting(old), reported, Waiting(new)),
            row(Showing(old), reported, Showing(new), arm),
            // On screen: the countdown starts.
            row(Hidden, shown, Hidden),
            row(Waiting(old), shown, Showing(old), arm),
            row(Showing(old), shown, Showing(old)),
            // Dismissed.
            row(Hidden, dismiss, Hidden),
            row(Waiting(old), dismiss, Hidden),
            row(Showing(old), dismiss, Hidden, disarm),
            // Its time is up.
            row(Hidden, timedOut, Hidden),
            row(Waiting(old), timedOut, Waiting(old)),
            row(Showing(old), timedOut, Hidden),
        )

    @Test
    fun `every banner state handles every banner event as the table says`() {
        for (row in table) {
            val before = PlaybackState(banner = row.state)

            assertEquals("${row.state} + ${row.event}", row.next, PlaybackReducer.reduce(before, row.event))
            assertEquals("${row.state} + ${row.event}", row.next, PlaybackStoreReducer.reduce(before, row.event))
        }
    }

    @Test
    fun `the table covers every banner state against every banner event`() {
        val covered = table.map { it.state to it.event }.toSet()

        assertEquals(states.flatMap { state -> events.map { state to it } }.toSet(), covered)
    }

    @Test
    fun `a notice says who reported what, and the banner shows for eight seconds`() {
        assertEquals("playback", new.feature)
        assertEquals("Could not save the playlist", reported.notice.message)
        assertEquals(Millis(8_000), BANNER_VISIBLE)
    }

    private fun row(
        state: Banner,
        event: PlaybackEvent,
        next: Banner,
        vararg effects: PlaybackEffect,
    ) = Row(state, event, Next(PlaybackState(banner = next), effects.toList()))

    private class Row(
        val state: Banner,
        val event: PlaybackEvent,
        val next: Next<PlaybackState, PlaybackEffect>,
    )
}
