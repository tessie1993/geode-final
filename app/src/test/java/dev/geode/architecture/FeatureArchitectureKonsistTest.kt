package dev.geode.architecture

import com.lemonappdev.konsist.api.Konsist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The architecture test of ADR-0003, run on the app's production code: feature cores are plain
 * Kotlin, no feature reaches into another's internals, and the feature graph has no cycles. The
 * rules themselves are proved to bite in [FeatureRulesTest].
 *
 * Named `*KonsistTest` so the mutation run leaves it out: it reads source files, not the classes
 * under mutation, so it can neither kill a mutant nor be slowed down per mutant.
 */
class FeatureArchitectureKonsistTest {
    private val files by lazy {
        Konsist
            .scopeFromProduction(moduleName = "app")
            .files
            .filter { it.packagee?.name.orEmpty().startsWith("dev.geode.feature.") }
            .map { file ->
                SourceFile(
                    path = file.path,
                    packageName = file.packagee?.name.orEmpty(),
                    references = file.imports.map { it.name }.toSet() + referencesIn(file.text),
                )
            }
    }

    @Test
    fun `the scan reaches the feature packages`() {
        assertTrue("no files found under dev.geode.feature", files.any { it.packageName == "dev.geode.feature.kernel.core" })
    }

    @Test
    fun `feature cores import no Android or Compose`() {
        assertEquals(emptyList<String>(), FeatureRules.coreIsPlainKotlin(files))
    }

    @Test
    fun `features use each other only through their public interface`() {
        assertEquals(emptyList<String>(), FeatureRules.noFeatureReachesIntoAnother(files))
    }

    @Test
    fun `the feature graph has no cycles`() {
        assertEquals(emptyList<String>(), FeatureRules.noFeatureCycles(files))
    }
}
