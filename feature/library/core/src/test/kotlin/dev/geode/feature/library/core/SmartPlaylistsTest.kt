package dev.geode.feature.library.core

import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.library.api.RuleField
import dev.geode.feature.library.api.RuleOp
import dev.geode.feature.library.api.SmartPlaylist
import dev.geode.feature.library.api.SmartRule
import dev.geode.feature.library.api.Song
import dev.geode.feature.library.core.Fixtures.alpha
import dev.geode.feature.library.core.Fixtures.beta
import dev.geode.feature.library.core.Fixtures.gamma
import dev.geode.feature.library.core.Fixtures.voice
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Smart playlist rules and their evaluation (#146), as a table: each row is one rule and the songs it
 * picks from Alpha (3:20, added at 3 s, played once, a favourite), beta (3:00, added at 1 s, played
 * twice), Gamma (4:00, added at 2 s, never played) and a 12 s voice note (added at 4 s), with the clock
 * at [now]: two and a half days after the start of time.
 */
class SmartPlaylistsTest {
    private val songs = listOf(alpha, beta, gamma, voice)
    private val playCounts = mapOf(alpha.id to 1, beta.id to 2)
    private val favourites = setOf(alpha.id)
    private val now = Millis(DAY * 5 / 2)

    private fun pick(
        playlist: SmartPlaylist,
        clock: Millis? = now,
    ): List<Song> = SmartPlaylists.resolve(playlist, songs, { playCounts[it] ?: 0 }, favourites, clock)

    private fun rule(
        field: RuleField,
        op: RuleOp,
        value: String = "",
    ) = SmartRule(field, op, value)

    private val table: List<Pair<SmartRule, List<Song>>> =
        listOf(
            // Words, ignoring case and the spaces around the value.
            rule(RuleField.TITLE, RuleOp.CONTAINS, " A ") to listOf(alpha, beta, gamma),
            rule(RuleField.TITLE, RuleOp.IS, "BETA") to listOf(beta),
            rule(RuleField.TITLE, RuleOp.IS_NOT, "beta ") to listOf(alpha, gamma, voice),
            rule(RuleField.ARTIST, RuleOp.IS, "ann") to listOf(alpha, gamma),
            rule(RuleField.ALBUM, RuleOp.CONTAINS, "sec") to listOf(gamma),
            rule(RuleField.FOLDER, RuleOp.CONTAINS, "/music/rock") to listOf(alpha, gamma),
            rule(RuleField.FOLDER, RuleOp.IS, "/music/rock") to listOf(alpha),
            // Words are never at least or at most anything.
            rule(RuleField.TITLE, RuleOp.AT_LEAST, "a") to emptyList(),
            rule(RuleField.TITLE, RuleOp.AT_MOST, "z") to emptyList(),
            // Minutes: at least and at most include the value; "is" compares whole minutes.
            rule(RuleField.DURATION_MINUTES, RuleOp.AT_LEAST, "3") to listOf(alpha, beta, gamma),
            rule(RuleField.DURATION_MINUTES, RuleOp.AT_LEAST, "3.5") to listOf(gamma),
            rule(RuleField.DURATION_MINUTES, RuleOp.AT_MOST, "3") to listOf(beta, voice),
            rule(RuleField.DURATION_MINUTES, RuleOp.AT_MOST, "3.4") to listOf(alpha, beta, voice),
            rule(RuleField.DURATION_MINUTES, RuleOp.IS, "3") to listOf(alpha, beta),
            rule(RuleField.DURATION_MINUTES, RuleOp.IS, "3.9") to listOf(alpha, beta),
            rule(RuleField.DURATION_MINUTES, RuleOp.IS_NOT, "3") to listOf(gamma, voice),
            // A number that is not one picks nothing, and numbers contain nothing.
            rule(RuleField.DURATION_MINUTES, RuleOp.AT_LEAST, "three") to emptyList(),
            rule(RuleField.DURATION_MINUTES, RuleOp.CONTAINS, "3") to emptyList(),
            // Days since added, from the clock.
            rule(RuleField.ADDED_DAYS_AGO, RuleOp.AT_LEAST, "2.5") to emptyList(),
            rule(RuleField.ADDED_DAYS_AGO, RuleOp.AT_MOST, "2.5") to songs,
            rule(RuleField.ADDED_DAYS_AGO, RuleOp.IS, "2") to songs,
            // Plays.
            rule(RuleField.PLAY_COUNT, RuleOp.AT_LEAST, "1") to listOf(alpha, beta),
            rule(RuleField.PLAY_COUNT, RuleOp.AT_LEAST, "2") to listOf(beta),
            rule(RuleField.PLAY_COUNT, RuleOp.AT_MOST, "1") to listOf(alpha, gamma, voice),
            rule(RuleField.PLAY_COUNT, RuleOp.IS, "0") to listOf(gamma, voice),
            // Favourites need no value.
            rule(RuleField.FAVOURITE, RuleOp.IS) to listOf(alpha),
            rule(RuleField.FAVOURITE, RuleOp.IS_NOT) to listOf(beta, gamma, voice),
            // A rule with a blank value is left out, so it picks everything.
            rule(RuleField.TITLE, RuleOp.IS, " ") to songs,
            rule(RuleField.PLAY_COUNT, RuleOp.AT_LEAST, "") to songs,
        )

    @Test
    fun `each rule picks the songs the table says, in their order`() {
        for ((rule, picked) in table) {
            assertEquals("$rule", picked, pick(SmartPlaylist("x", listOf(rule))))
        }
    }

    @Test
    fun `days since added count from the clock, and nothing matches them before the clock is read`() {
        val recent = SmartPlaylist("New", listOf(rule(RuleField.ADDED_DAYS_AGO, RuleOp.AT_MOST, "1")))
        val older = SmartPlaylist("Old", listOf(rule(RuleField.ADDED_DAYS_AGO, RuleOp.AT_LEAST, "1")))
        // Beta, added first, is exactly a day old; the others are not quite.
        val dayAfterBeta = Millis(DAY + beta.addedAt.value)

        assertEquals(songs, pick(recent, dayAfterBeta))
        assertEquals(listOf(beta), pick(older, dayAfterBeta))
        assertEquals(emptyList<Song>(), pick(recent, Millis(DAY * 2)))
        assertEquals(songs, pick(older, Millis(DAY * 2)))
        assertEquals(emptyList<Song>(), pick(recent, clock = null))
        assertEquals(emptyList<Song>(), pick(older.copy(rules = listOf(rule(RuleField.ADDED_DAYS_AGO, RuleOp.AT_LEAST, "0"))), null))
    }

    @Test
    fun `no rules picks every song, and the limit keeps the first ones`() {
        assertEquals(songs, pick(SmartPlaylist("All")))
        assertEquals(listOf(alpha, beta), pick(SmartPlaylist("All", limit = 2)))
        assertEquals(songs, pick(SmartPlaylist("All", limit = 4)))
        assertEquals(songs, pick(SmartPlaylist("All", limit = -1)))
    }

    @Test
    fun `a playlist matches all its rules, or any one of them`() {
        val rules = listOf(rule(RuleField.ARTIST, RuleOp.IS, "Ann"), rule(RuleField.PLAY_COUNT, RuleOp.AT_LEAST, "1"))

        assertEquals(listOf(alpha), pick(SmartPlaylist("Both", rules, matchAll = true)))
        assertEquals(listOf(alpha, beta, gamma), pick(SmartPlaylist("Either", rules, matchAll = false)))
        assertEquals(listOf(alpha), pick(SmartPlaylist("Either", rules, matchAll = false, limit = 1)))
        // A blank rule does not stop the others matching.
        val withBlank = rules + rule(RuleField.TITLE, RuleOp.IS, "")
        assertEquals(listOf(alpha), pick(SmartPlaylist("Both", withBlank, matchAll = true)))
    }

    @Test
    fun `plays are counted by the song's id`() {
        val played = SmartPlaylist("Played", listOf(rule(RuleField.PLAY_COUNT, RuleOp.AT_LEAST, "1")))
        val counts = mapOf(gamma.id to 5)

        assertEquals(listOf(gamma), SmartPlaylists.resolve(played, songs, { id: TrackId -> counts[id] ?: 0 }, emptySet(), now))
    }

    @Test
    fun `a smart playlist is saved with its name trimmed and no limit below none`() {
        val rules = listOf(rule(RuleField.TITLE, RuleOp.CONTAINS, " a "))

        assertEquals(
            SmartPlaylist("Mix", rules, matchAll = false, limit = 0),
            SmartPlaylists.tidied(SmartPlaylist(" Mix ", rules, false, -3)),
        )
        assertEquals(SmartPlaylist("Mix", limit = 5), SmartPlaylists.tidied(SmartPlaylist("Mix", limit = 5)))
    }

    private companion object {
        const val DAY = 86_400_000L
    }
}
