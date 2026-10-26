package dev.geode.feature.settings.api

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.net.URL

/**
 * The engine draws only the No-flash safe looks it lists itself (core/viz StyleTransition.hpp,
 * kSafeLooks) and the fade for any other id, so the picker must offer exactly those, plus the random
 * draw from them. This reads that file and fails when the two drift. It reads files rather than runs
 * code, so it stays out of mutation runs.
 */
class NativeTransitionLooksKonsistTest {
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

    private val header: String = File(root, "core/viz/include/geode/viz/StyleTransition.hpp").readText()

    private fun safeLooks(): List<String> {
        val body = Regex("""kSafeLooks\s*=\s*\{([^}]*)\};""").find(header)?.groupValues?.get(1) ?: error("no kSafeLooks")
        return Regex("\"([^\"]+)\"").findAll(body).map { it.groupValues[1] }.toList()
    }

    @Test
    fun `the picker offers the engine's safe looks in its order, then the random draw`() {
        val random = Regex("""kRandom = "([^"]+)";""").find(header)?.groupValues?.get(1) ?: error("no kRandom")

        assertEquals(safeLooks() + random, TransitionLook.entries.map { it.id })
    }
}
