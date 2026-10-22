package dev.geode.feature.diagnostics.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** Each kind of personal data the redaction takes out of free text, and what it leaves alone. */
class PersonalDataTest {
    private val table: List<Pair<String, String>> =
        listOf(
            // Plain diagnostic text stays as it is.
            "100 Playback: started" to "100 Playback: started",
            "capture from MICROPHONE did not start (code 3)" to "capture from MICROPHONE did not start (code 3)",
            "ratio 16/9 at 60 fps" to "ratio 16/9 at 60 fps",
            // Uris of any scheme.
            "opened content://media/external/audio/media/42 ok" to "opened <uri> ok",
            "file:///sdcard/a.mp3" to "<uri>",
            // Absolute paths.
            "wrote /data/user/0/dev.geode/files/state" to "wrote <path>",
            "missing /storage/emulated/0/Music/" to "missing <path>",
            // File names, spaces and all, whatever the case of the extension.
            "skipped: Ambient Glow.json" to "skipped: <file>",
            "Track.FLAC failed" to "<file> failed",
            "lost (Night Drive.m4a)" to "lost (<file>)",
            "a.jpeg" to "<file>",
            "b.jpg" to "<file>",
            "c.aif" to "<file>",
            "d.aiff" to "<file>",
            "e.m3u8" to "<file>",
            "summary.flacky stays" to "summary.flacky stays",
            // E-mail addresses.
            "account jane.doe+music@mail.example.co.uk set" to "account <email> set",
            // Anything quoted.
            "renamed to \"Road Trip\"" to "renamed to <text>",
            "renamed to 'Road Trip'" to "renamed to <text>",
        )

    @Test
    fun `redaction replaces personal data with markers and leaves the rest`() {
        for ((text, redacted) in table) {
            assertEquals(text, redacted, PersonalData.redact(text))
        }
    }

    @Test
    fun `a setting that names the user's things is hidden whatever its value`() {
        val hidden =
            listOf(
                "Music folder",
                "Export path",
                "Library URI",
                "Download dir",
                "Last file",
                "Account",
                "E-mail",
                "Email",
                "User",
                "Playlist name",
            )
        for (name in hidden) {
            assertEquals(name, PersonalData.HIDDEN, PersonalData.redactSetting(Setting(name, "Dark")))
        }
    }

    @Test
    fun `any other setting shows its value, redacted`() {
        assertEquals("Dark", PersonalData.redactSetting(Setting("Theme", "Dark")))
        assertEquals("<uri>", PersonalData.redactSetting(Setting("Theme", "content://x/y")))
    }
}
