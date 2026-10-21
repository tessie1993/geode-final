package dev.geode.architecture

import com.lemonappdev.konsist.api.Konsist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Library touches Playback only through Playback's `:api` (#37, ADR-0005): no Library screen
 * refers to Playback at all, and no Library module refers to Playback's state machine, handlers or
 * screens. Read with Konsist, imports and names written out in the code alike.
 *
 * Named `*KonsistTest` so the mutation run leaves it out (see FeatureArchitectureKonsistTest).
 */
class LibraryKonsistTest {
    private val root: File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }

    private fun referencesIn(module: String): Map<String, Set<String>> {
        val main = File(root, "feature/library/$module/src/main")
        if (!main.isDirectory) return emptyMap()
        return Konsist
            .scopeFromDirectory(main.relativeTo(root).path)
            .files
            .associate { file -> file.path to (file.imports.map { it.name } + playbackNamesIn(file.text)).toSet() }
    }

    @Test
    fun `the scan reaches every Library module`() {
        for (module in MODULES) {
            assertTrue("no source was read in :feature:library:$module", referencesIn(module).isNotEmpty())
        }
    }

    @Test
    fun `no Library screen touches Playback`() {
        val touching = referencesIn("ui").filterValues { refs -> refs.any { it.startsWith(PLAYBACK) } }.keys

        assertEquals(emptySet<String>(), touching)
    }

    @Test
    fun `the Library uses nothing of Playback but its interface`() {
        val internals =
            MODULES.flatMap { module ->
                referencesIn(module).flatMap { (path, refs) ->
                    refs.filter { it.startsWith(PLAYBACK) && !it.startsWith("$PLAYBACK.api") }.map { "$path: $it" }
                }
            }

        assertEquals(emptyList<String>(), internals)
    }

    private fun playbackNamesIn(code: String): Set<String> =
        playbackName
            .findAll(code)
            .map { it.value }
            .toSet()

    private companion object {
        const val PLAYBACK = "dev.geode.feature.playback"
        val MODULES = listOf("api", "core", "handlers", "ui")
        val playbackName = Regex("""dev\.geode\.feature\.playback(?:\.\w+)+""")
    }
}
