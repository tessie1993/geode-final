package dev.geode.feature.studio.core

/**
 * Which track's Customization one continuous video draws with as its frames go by (#326): it starts with
 * the first track's, and moves to each next track's at its moment in [changes] (one per track after the
 * first, in order, in the video's milliseconds, as [ContinuousPlan.changesAt] gives them). Asked frame by
 * frame, in order. Not thread-safe; it lives on the Render's own thread.
 */
class TrackCues(
    private val changes: List<Long>,
) {
    /** The track whose Customization is drawn now. */
    var track: Int = 0
        private set

    /**
     * The track to move to by [untilMs], the end of the frame about to be drawn, or null to stay. Moments
     * that have all passed by then move straight to the last of them.
     */
    fun dueBy(untilMs: Long): Int? {
        var next = track
        while (next < changes.size && changes[next] < untilMs) next++
        if (next == track) return null
        track = next
        return next
    }
}
