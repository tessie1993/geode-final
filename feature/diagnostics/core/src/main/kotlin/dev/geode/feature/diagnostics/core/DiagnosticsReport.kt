package dev.geode.feature.diagnostics.core

import dev.geode.engine.types.Millis
import dev.geode.feature.diagnostics.api.EngineStats

/** The app's version, as the user sees it in Settings. */
data class AppFacts(
    val versionName: String,
    val versionCode: Long,
)

/** The phone: maker, model and Android version. Nothing that identifies the phone or its owner. */
data class DeviceFacts(
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdk: Int,
)

/** One setting, by its name and the value it shows. */
data class Setting(
    val name: String,
    val value: String,
)

/**
 * Everything the diagnostics file holds, and nothing else (#62): when it was made, the app and the
 * phone, the settings, the latest engine sample when there is one, and the local log. There is no
 * field for the user's music, files, folders or accounts; the settings and the log pass through
 * [PersonalData.redact] before they are written.
 */
data class ReportInputs(
    val generatedAt: Millis,
    val app: AppFacts,
    val device: DeviceFacts,
    val settings: List<Setting>,
    val engine: EngineStats?,
    val log: List<String>,
)

/** Writes [ReportInputs] as the plain-text diagnostics file. Pure, so its content is tested here. */
object DiagnosticsReport {
    /** The file's name in the share sheet. */
    const val FILE_NAME = "geode-diagnostics.txt"

    /** The file's media type for the share sheet. */
    const val MIME_TYPE = "text/plain"

    /** Bumped whenever the layout below changes, so a reader knows which layout it has. */
    const val FORMAT_VERSION = 1

    /** The most log lines a report carries: the newest, as many as the ring log keeps. */
    const val MAX_LOG_LINES = 500

    private const val INDENT = "  "
    private const val NONE = "(none)"
    private const val NOT_SAMPLED = "(not sampled; the developer overlay was off)"

    fun compose(inputs: ReportInputs): String =
        buildString {
            appendLine("Geode diagnostics (format $FORMAT_VERSION)")
            appendLine("Generated at: ${inputs.generatedAt.value} ms since 1970")
            appendLine("App: ${inputs.app.versionName} (${inputs.app.versionCode})")
            appendLine("Device: ${inputs.device.manufacturer} ${inputs.device.model}")
            appendLine("Android: ${inputs.device.androidRelease} (API ${inputs.device.sdk})")
            appendLine()
            appendLine("Settings")
            section(inputs.settings.map { "${PersonalData.redact(it.name)}: ${PersonalData.redactSetting(it)}" })
            appendLine()
            appendLine("Engine")
            section(inputs.engine?.let(::engineLines).orEmpty(), empty = NOT_SAMPLED)
            appendLine()
            appendLine("Log")
            section(inputs.log.takeLast(MAX_LOG_LINES).map(PersonalData::redact))
        }

    private fun engineLines(stats: EngineStats): List<String> =
        listOf(
            "state: ${stats.engine}",
            "frame time: ${stats.frameTime}",
            "audio buffer: ${stats.audioBuffer}",
            "visual buffer: ${stats.visualBuffer.width}x${stats.visualBuffer.height}",
            "underruns: ${stats.underruns}",
        )

    private fun StringBuilder.section(
        lines: List<String>,
        empty: String = NONE,
    ) {
        if (lines.isEmpty()) {
            appendLine(INDENT + empty)
        } else {
            lines.forEach { appendLine(INDENT + it) }
        }
    }
}
