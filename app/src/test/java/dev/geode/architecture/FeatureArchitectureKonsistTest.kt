package dev.geode.architecture

import com.lemonappdev.konsist.api.Konsist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The architecture test of ADR-0005, on the real build: the module graph read from
 * `settings.gradle.kts` and each module's build file, and the pure modules' sources read with Konsist.
 * The rules themselves are proved to bite in [FeatureRulesTest].
 *
 * Named `*KonsistTest` so the mutation run leaves it out: it reads files, not the classes under
 * mutation, so it can neither kill a mutant nor be slowed down per mutant.
 */
class FeatureArchitectureKonsistTest {
    private val root: File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }

    private val modules: List<Module> by lazy {
        modulesIn(File(root, "settings.gradle.kts").readText()).map { path ->
            val buildFile = File(directoryOf(path), "build.gradle.kts")
            Module(path, if (buildFile.isFile) dependenciesIn(buildFile.readText()) else emptySet())
        }
    }

    private val pureSources: List<SourceFile> by lazy {
        sourcesOf { it.startsWith(":arch:") || it.startsWith(":feature:") }
    }

    private val elementSources: List<SourceFile> by lazy { sourcesOf { it.startsWith(":design:elements:") } }

    private fun sourcesOf(wanted: (String) -> Boolean): List<SourceFile> =
        modules
            .map { it.path }
            .filter(wanted)
            .flatMap { module ->
                val main = File(directoryOf(module), "src/main")
                if (!main.isDirectory) return@flatMap emptyList()
                Konsist
                    .scopeFromDirectory(main.relativeTo(root).path)
                    .files
                    .map { SourceFile(module, it.path, it.imports.map { imported -> imported.name }.toSet() + referencesIn(it.text)) }
            }

    @Test
    fun `the scan reaches the architecture modules`() {
        assertTrue("the kernel module was not found", modules.any { it.path == ":arch:kernel" })
        assertTrue("the design module was not found", modules.any { it.path == ":design" })
        assertTrue("no kernel source was read", pureSources.any { it.module == ":arch:kernel" })
        assertTrue("no Water Element source was read", elementSources.any { it.module == ":design:elements:water" })
    }

    @Test
    fun `Element modules draw only, with no feature, engine or audio`() {
        assertEquals(emptyList<String>(), FeatureRules.elementModulesStayDrawOnly(elementSources))
    }

    @Test
    fun `pure modules use no Android or Compose`() {
        assertEquals(emptyList<String>(), FeatureRules.pureModulesUseNoAndroid(pureSources))
    }

    @Test
    fun `every module depends only on what ADR-0005 allows`() {
        assertEquals(emptyList<String>(), FeatureRules.dependenciesFollowTheTable(modules))
    }

    @Test
    fun `the feature graph has no cycles`() {
        assertEquals(emptyList<String>(), FeatureRules.noFeatureCycles(modules))
    }

    private fun directoryOf(module: String) = File(root, module.removePrefix(":").replace(':', '/'))
}
