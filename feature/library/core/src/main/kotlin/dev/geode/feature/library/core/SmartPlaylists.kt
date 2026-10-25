package dev.geode.feature.library.core

import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.library.api.RuleField
import dev.geode.feature.library.api.RuleOp
import dev.geode.feature.library.api.SmartPlaylist
import dev.geode.feature.library.api.SmartRule
import dev.geode.feature.library.api.Song

/** How long a minute is, for the rules on a song's length. */
private const val MILLIS_PER_MINUTE = 60_000f

/** How long a day is, for the rules on when a song was added. */
private const val MILLIS_PER_DAY = 86_400_000f

/**
 * Smart playlist rules and their evaluation (#146), pure: the same rules as the old smart playlist
 * editor's matcher, so every smart playlist picks the songs it picked before.
 *
 * A rule with a blank value is left out (a yes-or-no rule needs none). No rules picks every song.
 * Words are compared ignoring case; numbers are compared as decimals, except that [RuleOp.IS] and
 * [RuleOp.IS_NOT] compare whole numbers (2.9 minutes "is" 2).
 */
internal object SmartPlaylists {
    /**
     * The songs [playlist] picks from [songs], in their order, at most its limit. [plays] counts how
     * often a song was played, [favourites] are the favourite songs, and [now] is wall-clock time
     * (null when not known yet, so no song matches a rule on the days since it was added).
     */
    fun resolve(
        playlist: SmartPlaylist,
        songs: List<Song>,
        plays: (TrackId) -> Int,
        favourites: Set<TrackId>,
        now: Millis?,
    ): List<Song> {
        val rules = playlist.rules.filter { it.value.isNotBlank() || it.field.isFlag }
        val picked =
            if (rules.isEmpty()) {
                songs
            } else {
                songs.filter { song ->
                    val facts = Facts(song, plays(song.id), song.id in favourites, now)
                    if (playlist.matchAll) rules.all { facts.matches(it) } else rules.any { facts.matches(it) }
                }
            }
        return if (playlist.limit > 0) picked.take(playlist.limit) else picked
    }

    /** [playlist] as it is saved: its name trimmed, and no limit below none. */
    fun tidied(playlist: SmartPlaylist): SmartPlaylist = playlist.copy(name = playlist.name.trim(), limit = playlist.limit.coerceAtLeast(0))

    /** What the rules can read about one song. */
    private class Facts(
        val song: Song,
        val plays: Int,
        val favourite: Boolean,
        val now: Millis?,
    ) {
        fun matches(rule: SmartRule): Boolean =
            when (rule.field) {
                RuleField.TITLE -> text(rule, song.title)
                RuleField.ARTIST -> text(rule, song.artist)
                RuleField.ALBUM -> text(rule, song.album)
                RuleField.FOLDER -> text(rule, song.folder)
                RuleField.DURATION_MINUTES -> number(rule, song.duration.value / MILLIS_PER_MINUTE)
                RuleField.ADDED_DAYS_AGO -> now != null && number(rule, (now - song.addedAt).value / MILLIS_PER_DAY)
                RuleField.PLAY_COUNT -> number(rule, plays.toFloat())
                RuleField.FAVOURITE -> favourite == (rule.op != RuleOp.IS_NOT)
            }
    }

    private fun text(
        rule: SmartRule,
        field: String,
    ): Boolean {
        val wanted = rule.value.trim()
        return when (rule.op) {
            RuleOp.CONTAINS -> field.contains(wanted, ignoreCase = true)
            RuleOp.IS -> field.equals(wanted, ignoreCase = true)
            RuleOp.IS_NOT -> !field.equals(wanted, ignoreCase = true)
            RuleOp.AT_LEAST, RuleOp.AT_MOST -> false
        }
    }

    private fun number(
        rule: SmartRule,
        actual: Float,
    ): Boolean {
        val wanted = rule.value.trim().toFloatOrNull() ?: return false
        return when (rule.op) {
            RuleOp.IS -> actual.toInt() == wanted.toInt()
            RuleOp.IS_NOT -> actual.toInt() != wanted.toInt()
            RuleOp.AT_LEAST -> actual >= wanted
            RuleOp.AT_MOST -> actual <= wanted
            RuleOp.CONTAINS -> false
        }
    }
}
