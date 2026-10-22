package dev.geode.feature.customization.core

import dev.geode.feature.customization.api.ExtraKind
import dev.geode.feature.customization.api.MACRO_COUNT
import dev.geode.feature.customization.api.Modulator
import dev.geode.feature.customization.api.SharedProperty
import dev.geode.feature.customization.api.SoundSignal
import dev.geode.feature.customization.api.ValueKind
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The engine evaluates Reactions and Modulators in C++ (ADR-0002) from its own copy of the model's
 * tables (core/viz). This reads those files and fails when they drift from the Kotlin model. It reads
 * files rather than runs code, so it stays out of mutation runs.
 */
class NativeCustomizationKonsistTest {
    // The repository root above the working directory, or above the compiled test class when a runner
    // (Pitest) starts somewhere else.
    private val root: File =
        sequenceOf(
            File(System.getProperty("user.dir")),
            File(javaClass.protectionDomain.codeSource.location.toURI()),
        ).flatMap { start -> generateSequence(start.absoluteFile) { it.parentFile } }
            .first { File(it, "settings.gradle.kts").isFile && File(it, "core/viz").isDirectory }

    private fun read(path: String): String = File(root, path).readText()

    private data class Row(
        val name: String,
        val min: Float,
        val max: Float,
        val initial: Float,
        val kind: String,
        val luminous: Boolean,
    )

    private val rowPattern =
        Regex("""\{"(\w+)", (-?[\d.]+)f, (-?[\d.]+)f, (-?[\d.]+)f, K::(\w+), (true|false)\}""")

    private fun constant(
        source: String,
        name: String,
    ): String =
        Regex("""constexpr \w+ $name = ([^;]+);""").find(source)?.groupValues?.get(1)?.trim()
            ?: error("no constant $name")

    private fun nativeKind(kind: ValueKind): String =
        when (kind) {
            ValueKind.CONTINUOUS -> "Continuous"
            ValueKind.STEPPED -> "Stepped"
            ValueKind.SWITCH -> "Switch"
        }

    private fun camel(name: String): String =
        name.lowercase().split('_').joinToString("") { part -> part.replaceFirstChar { it.uppercase() } }

    @Test
    fun `the engine's Shared core table matches SharedProperty row for row`() {
        val rows =
            rowPattern.findAll(read("core/viz/SharedCore.cpp")).map {
                val g = it.groupValues
                Row(g[1], g[2].toFloat(), g[3].toFloat(), g[4].toFloat(), g[5], g[6] == "true")
            }.toList()
        val expected =
            SharedProperty.entries.map {
                Row(it.name, it.min, it.max, it.default, nativeKind(it.kind), it.luminous)
            }
        assertEquals(expected, rows)
        val header = read("core/viz/include/geode/viz/SharedCore.hpp")
        assertEquals(SharedProperty.entries.size.toString(), constant(header, "kSharedCount").removeSuffix("u"))
        assertEquals(ExtraKind.entries.size.toString(), constant(header, "kExtraCount"))
        assertEquals(MACRO_COUNT.toString(), constant(header, "kMacroCount"))
    }

    @Test
    fun `the engine's Sound signals are the model's, in the same order`() {
        val header = read("core/viz/include/geode/viz/Reactions.hpp")
        val body =
            Regex("""enum class SoundSignal : uint8_t \{([^}]*)\}""").find(header)?.groupValues?.get(1)
                ?: error("no SoundSignal enum")
        val native = body.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(SoundSignal.entries.map { camel(it.name) }, native)
        assertEquals(SoundSignal.entries.size.toString(), constant(header, "kSoundSignalCount"))
    }

    @Test
    fun `the engine keeps the model's rate range and No-flash rise`() {
        val modulators = read("core/viz/include/geode/viz/Modulators.hpp")
        assertEquals(Modulator.MIN_RATE, constant(modulators, "kMinRate").removeSuffix("f").toFloat())
        assertEquals(Modulator.MAX_RATE, constant(modulators, "kMaxRate").removeSuffix("f").toFloat())
        val reactions = read("core/viz/include/geode/viz/Reactions.hpp")
        val riseSeconds = constant(reactions, "kNoFlashMinRiseSeconds").removeSuffix("f").toFloat()
        assertEquals(NO_FLASH_MIN_RISE.value.toFloat() / 1000f, riseSeconds)
    }
}
