package dev.geode.feature.diagnostics.core

import dev.geode.engine.types.Frames
import dev.geode.engine.types.Micros
import dev.geode.engine.types.Millis
import dev.geode.feature.diagnostics.api.BufferSize
import dev.geode.feature.diagnostics.api.EngineRunState
import dev.geode.feature.diagnostics.api.EngineStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The diagnostics file: exactly what it holds, and that none of it is the user's (#62). */
class DiagnosticsReportTest {
    private val app = AppFacts(versionName = "1.8.0", versionCode = 32)
    private val device = DeviceFacts(manufacturer = "Google", model = "Pixel 8", androidRelease = "15", sdk = 35)
    private val engine =
        EngineStats(
            frameTime = Micros(16_667),
            audioBuffer = Frames(192),
            visualBuffer = BufferSize(width = 1_080, height = 2_400),
            underruns = 3,
            engine = EngineRunState.RUNNING,
        )

    @Test
    fun `the report lays out the app, the phone, the settings, the engine and the log`() {
        val report =
            DiagnosticsReport.compose(
                ReportInputs(
                    generatedAt = Millis(1_700_000_000_000),
                    app = app,
                    device = device,
                    settings = listOf(Setting("Theme", "Dark"), Setting("Idle engine shuts down", "On")),
                    engine = engine,
                    log = listOf("100 Playback: started", "200 Capture: lost"),
                ),
            )

        assertEquals(
            """
            Geode diagnostics (format 1)
            Generated at: 1700000000000 ms since 1970
            App: 1.8.0 (32)
            Device: Google Pixel 8
            Android: 15 (API 35)

            Settings
              Theme: Dark
              Idle engine shuts down: On

            Engine
              state: RUNNING
              frame time: 16667us
              audio buffer: 192 frames
              visual buffer: 1080x2400
              underruns: 3

            Log
              100 Playback: started
              200 Capture: lost

            """.trimIndent(),
            report,
        )
    }

    @Test
    fun `empty sections say so`() {
        val report = DiagnosticsReport.compose(inputs(settings = emptyList(), log = emptyList()))

        assertTrue(report, report.contains("Settings\n  (none)\n"))
        assertTrue(report, report.contains("Engine\n  (not sampled; the developer overlay was off)\n"))
        assertTrue(report, report.endsWith("Log\n  (none)\n"))
    }

    @Test
    fun `only the newest log lines are kept`() {
        val log = (1..DiagnosticsReport.MAX_LOG_LINES + 2).map { "line $it" }

        val report = DiagnosticsReport.compose(inputs(log = log))

        assertFalse(report, report.contains("  line 2\n"))
        assertTrue(report, report.contains("  line 3\n"))
        assertTrue(report, report.endsWith("  line ${DiagnosticsReport.MAX_LOG_LINES + 2}\n"))
    }

    @Test
    fun `the report has no field for personal data`() {
        // Adding a field to what the file holds must be a deliberate, reviewed change to this list.
        assertEquals(setOf("generatedAt", "app", "device", "settings", "engine", "log"), fieldsOf(ReportInputs::class.java))
        assertEquals(setOf("versionName", "versionCode"), fieldsOf(AppFacts::class.java))
        assertEquals(setOf("manufacturer", "model", "androidRelease", "sdk"), fieldsOf(DeviceFacts::class.java))
        assertEquals(setOf("name", "value"), fieldsOf(Setting::class.java))
        val personal = listOf("path", "uri", "file", "folder", "title", "artist", "album", "account", "email", "serial", "imei")
        val fields =
            listOf(ReportInputs::class.java, AppFacts::class.java, DeviceFacts::class.java, Setting::class.java)
                .flatMap(::fieldsOf)
        for (field in fields) {
            assertFalse(field, personal.any { field.lowercase().contains(it) })
        }
    }

    @Test
    fun `personal data in the log and the settings never reaches the file`() {
        val report =
            DiagnosticsReport.compose(
                inputs(
                    settings =
                        listOf(
                            Setting("Music folder", "Summer Mix"),
                            Setting("Last export path", "Movies/Geode"),
                            Setting("Theme", "content://com.android.providers.media.documents/document/audio%3A42"),
                        ),
                    log =
                        listOf(
                            "1 PresetStore: unreadable preset skipped: Ambient Glow.json",
                            "2 NativePlayer: open failed (FileNotFoundException: /storage/emulated/0/Music/Night Drive.flac)",
                            "3 Library: loaded content://media/external/audio/media/1234",
                            "4 Backup: signed in as jane.doe@example.com",
                            "5 TakeStore: take failed to load: \"Jane's birthday\"",
                        ),
                ),
            )

        val planted =
            listOf(
                "Summer Mix",
                "Movies/Geode",
                "content://",
                "audio%3A42",
                "Ambient",
                "Glow",
                "Night Drive",
                "/storage",
                "Music/",
                "media/1234",
                "jane",
                "example.com",
                "birthday",
            )
        for (personal in planted) {
            assertFalse("\"$personal\" leaked into:\n$report", report.contains(personal))
        }
        assertTrue(report, report.contains("  Music folder: <hidden>\n"))
        assertTrue(report, report.contains("  Last export path: <hidden>\n"))
        assertTrue(report, report.contains("  Theme: <uri>\n"))
        assertTrue(report, report.contains("  1 PresetStore: unreadable preset skipped: <file>\n"))
        assertTrue(report, report.contains("  4 Backup: signed in as <email>\n"))
    }

    private fun inputs(
        settings: List<Setting> = listOf(Setting("Theme", "Dark")),
        log: List<String> = listOf("1 Playback: started"),
    ) = ReportInputs(Millis(1), app, device, settings, engine = null, log = log)

    private fun fieldsOf(type: Class<*>): Set<String> =
        type.declaredFields
            .map { it.name }
            .filterNot { it.startsWith("$") || it == "Companion" }
            .toSet()
}
