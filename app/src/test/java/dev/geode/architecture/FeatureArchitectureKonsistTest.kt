package dev.geode.architecture

import com.lemonappdev.konsist.api.Konsist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The architecture test of ADR-0003 and ADR-0004, run on the app's production code: feature cores
 * and the kernel are plain Kotlin, the kernel knows no feature, no feature reaches into another's
 * internals, and the feature graph has no cycles. The rules are proved to bite in [FeatureRulesTest].
 *
 * Named `*KonsistTest` so the mutation run leaves it out: it reads source files, not the classes
 * under mutation, so it can neither kill a mutant nor be slowed down per mutant.
 */
class FeatureArchitectureKonsistTest {
    private val files by lazy {
        Konsist
            .scopeFromProduction(moduleName = "app")
            .files
            .map { file ->
                SourceFile(
                    path = file.path,
                    packageName = file.packagee?.name.orEmpty(),
                    references = file.imports.map { it.name }.toSet() + referencesIn(file.text),
                )
            }.filter { it.packageName.startsWith("dev.geode.feature.") || it.packageName.startsWith("dev.geode.kernel") }
    }

    @Test
    fun `the scan reaches the feature packages`() {
        assertTrue("the kernel was not found", files.any { it.packageName == "dev.geode.kernel" })
    }

    @Test
    fun `feature cores import no Android or Compose`() {
        assertEquals(emptyList<String>(), FeatureRules.coreIsPlainKotlin(files))
    }

    @Test
    fun `the kernel knows no feature`() {
        assertEquals(emptyList<String>(), FeatureRules.kernelKnowsNoFeature(files))
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
