package dev.geode.feature.customization.core

import dev.geode.feature.customization.api.ModulatorShape
import dev.geode.feature.customization.api.ReactionSource
import dev.geode.feature.customization.api.SoundSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URL

/**
 * Tilt is never a source for Reactions or Modulators (#397, the owner's decision): a Reaction listens to
 * a Sound signal, measured from the audio, or to a Modulator, generated; tilt is neither (CONTEXT.md).
 * Tilt reaches the Visual only as the engine's GravityField, which moves the camera and the scenes and
 * never a Customization value.
 *
 * This holds both halves to it: the Kotlin model (whose `when`s below stop compiling if a new kind of
 * source or Modulator appears, so adding one is a decision this test makes someone look at), and the C++
 * that evaluates Reactions every frame (ADR-0002), read as files, so it stays out of mutation runs.
 */
class TiltIsNoReactionSourceKonsistTest {
    // The repository root above the working directory, or above the compiled test class when a runner
    // (Pitest) starts somewhere else.
    private val starts: List<File> =
        listOf(
            File(System.getProperty("user.dir")),
            File(compiledClassLocation().toURI()),
        )

    private fun compiledClassLocation(): URL = javaClass.protectionDomain.codeSource.location

    private val root: File =
        starts
            .flatMap { start -> generateSequence(start.absoluteFile) { it.parentFile }.toList() }
            .first { File(it, "settings.gradle.kts").isFile && File(it, "core/viz").isDirectory }

    private fun read(path: String): String = File(root, path).readText()

    /** Anything that is the phone's motion rather than the music. */
    private val motion = Regex("""(?i)gravity|\btilt|sensor|acceler|gyro|orientation""")

    private fun source(source: ReactionSource): String =
        when (source) {
            is ReactionSource.Sound -> "sound ${source.signal}"
            is ReactionSource.Generated -> "modulator ${source.modulator.value}"
        }

    /** The one Modulator that listens to anything listens to a Sound signal; the others listen to nothing. */
    private fun listensTo(shape: ModulatorShape): SoundSignal? =
        when (shape) {
            is ModulatorShape.HitEnvelope -> shape.signal
            is ModulatorShape.Lfo,
            is ModulatorShape.StepSequencer,
            is ModulatorShape.RandomDrift,
            is ModulatorShape.ToggleOnBeat,
            is ModulatorShape.RandomOnBeat,
            -> null
        }

    @Test
    fun `a Reaction listens to a Sound signal or a Modulator, and no Sound signal is the phone's motion`() {
        assertEquals("sound LEVEL", source(ReactionSource.Sound(SoundSignal.LEVEL)))
        val motionSignals = SoundSignal.entries.filter { motion.containsMatchIn(it.name) }
        assertEquals(emptyList<SoundSignal>(), motionSignals)
    }

    @Test
    fun `a Modulator listens to nothing but a Sound signal`() {
        val envelope = ModulatorShape.HitEnvelope(SoundSignal.KICK, ModulatorShape.DEFAULT_GLIDE, ModulatorShape.DEFAULT_GLIDE, 0.5f)
        assertEquals(SoundSignal.KICK, listensTo(envelope))
    }

    @Test
    fun `the engine's Reaction sources are Sound signals and Modulators only`() {
        val graph = read("core/viz/include/geode/viz/ReactionGraph.hpp")
        val kinds = Regex("""struct Source \{\s*enum class Kind : uint8_t \{([^}]*)\}""").find(graph) ?: error("no Source::Kind")
        assertEquals(
            listOf("Sound", "Modulator"),
            kinds.groupValues[1]
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() },
        )
    }

    @Test
    fun `nothing that evaluates Reactions and Modulators reads the phone's motion`() {
        val evaluators =
            listOf(
                "core/viz/include/geode/viz/ReactionGraph.hpp",
                "core/viz/ReactionGraph.cpp",
                "core/viz/include/geode/viz/Reactions.hpp",
                "core/viz/Reactions.cpp",
                "core/viz/include/geode/viz/Modulators.hpp",
                "core/viz/Modulators.cpp",
                "core/viz/include/geode/viz/PlanCodec.hpp",
                "core/viz/PlanCodec.cpp",
                "core/viz/include/geode/viz/Normaliser.hpp",
                "core/viz/Normaliser.cpp",
                "feature/customization/core/src/main/kotlin/dev/geode/feature/customization/core/NativePlan.kt",
            )
        val readers = evaluators.filter { motion.containsMatchIn(read(it)) }
        assertEquals(emptyList<String>(), readers)
    }

    @Test
    fun `the engine evaluates Reactions from the heard sound alone`() {
        // The frame the graph is handed is built from the analysis' features, and those hold no motion.
        val api = read("core/api/geode_viz_api.cpp")
        assertTrue(api.contains("graph.evaluate(v->plan, geode::viz::mod::signalFrame(features), dt)"))
        val header = read("core/api/include/geode/api/geode_api.h")
        val frame =
            Regex("""typedef struct GeodeFeatureFrame \{([^}]*)\} GeodeFeatureFrame;""").find(header) ?: error("no GeodeFeatureFrame")
        assertEquals(null, motion.find(frame.groupValues[1])?.value)
    }
}
