package dev.geode.architecture

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Proves each architecture rule bites: a small fixture that breaks it is reported, and a fixture
 * that follows it is not. The real build is checked by [FeatureArchitectureKonsistTest].
 */
class FeatureRulesTest {
    // Two features laid out exactly as ADR-0005 says, with liveinput's core and handlers using
    // playback's interface, and liveinput's screens drawn with :design.
    private val cleanBuild =
        listOf(
            Module(":app", setOf(":feature:liveinput:handlers", ":feature:liveinput:ui", ":arch:kernel", ":design")),
            Module(":design", emptySet()),
            Module(":arch:kernel", setOf(":engine:audio-core")),
            Module(":arch:audio", setOf(":engine:audio-core")),
            Module(":feature:playback:api", setOf(":arch:kernel")),
            Module(":feature:playback:core", setOf(":arch:kernel")),
            Module(":feature:liveinput:api", setOf(":arch:kernel")),
            Module(":feature:liveinput:core", setOf(":arch:kernel", ":feature:playback:api")),
            Module(
                ":feature:liveinput:handlers",
                setOf(":feature:liveinput:core", ":feature:liveinput:api", ":arch:audio", ":feature:playback:api"),
            ),
            Module(":feature:liveinput:ui", setOf(":feature:liveinput:api", ":design")),
        )

    @Test
    fun `a build laid out as the table says passes every rule`() {
        val sources = listOf(source(":feature:liveinput:core", "import kotlinx.coroutines.flow.StateFlow"))

        assertEquals(emptyList<String>(), FeatureRules.all(cleanBuild, sources))
    }

    @Test
    fun `a pure module that uses Android is reported, whether imported or written out`() {
        val imported = source(":feature:liveinput:core", "import android.media.AudioRecord")
        val written = source(":arch:kernel", "val x = androidx.compose.runtime.mutableStateOf(1)")
        val inApi = source(":feature:liveinput:api", "import org.jetbrains.compose.ui.Modifier")

        assertEquals(
            listOf(
                "liveinput/core/X.kt: :feature:liveinput:core uses android.media.AudioRecord",
                "kernel/X.kt: :arch:kernel uses androidx.compose.runtime.mutableStateOf",
                "liveinput/api/X.kt: :feature:liveinput:api uses org.jetbrains.compose.ui.Modifier",
            ),
            FeatureRules.pureModulesUseNoAndroid(listOf(imported, written, inApi)),
        )
    }

    @Test
    fun `Android in comments, strings, screens and handlers is not reported`() {
        val sources =
            listOf(
                source(":feature:liveinput:core", "// android.os.Build\nval s = \"androidx.compose.ui\""),
                source(":feature:liveinput:ui", "import androidx.compose.runtime.Composable"),
                source(":feature:liveinput:handlers", "import android.media.AudioRecord"),
            )

        assertEquals(emptyList<String>(), FeatureRules.pureModulesUseNoAndroid(sources))
    }

    @Test
    fun `screens that reach past their own api are reported`() {
        val ui = Module(":feature:liveinput:ui", setOf(":feature:liveinput:core", ":feature:liveinput:handlers", ":feature:playback:api"))

        assertEquals(
            listOf(
                ":feature:liveinput:ui may not depend on :feature:liveinput:core",
                ":feature:liveinput:ui may not depend on :feature:liveinput:handlers",
                ":feature:liveinput:ui may not depend on :feature:playback:api",
            ),
            FeatureRules.dependenciesFollowTheTable(listOf(ui)),
        )
    }

    @Test
    fun `a dependency on another feature's internals is reported from anywhere but the app`() {
        val modules =
            listOf(
                Module(":feature:liveinput:core", setOf(":feature:playback:core")),
                Module(
                    ":feature:liveinput:handlers",
                    setOf(":feature:playback:handlers", ":feature:playback:core", ":feature:playback:api"),
                ),
                Module(":feature:liveinput:api", setOf(":feature:playback:api", ":arch:audio")),
                Module(":arch:audio", setOf(":feature:playback:api")),
                Module(":engine:scenes", setOf(":feature:playback:api")),
                Module(":feature:liveinput:extras", setOf(":arch:kernel")),
            )

        assertEquals(
            listOf(
                ":feature:liveinput:core may not depend on :feature:playback:core",
                ":feature:liveinput:handlers may not depend on :feature:playback:handlers",
                ":feature:liveinput:handlers may not depend on :feature:playback:core",
                ":feature:liveinput:api may not depend on :feature:playback:api",
                ":feature:liveinput:api may not depend on :arch:audio",
                ":arch:audio may not depend on :feature:playback:api",
                ":engine:scenes may not depend on :feature:playback:api",
                ":feature:liveinput:extras may not depend on :arch:kernel",
            ),
            FeatureRules.dependenciesFollowTheTable(modules),
        )
    }

    @Test
    fun `only screens and the app may use the design module`() {
        val modules =
            listOf(
                Module(":feature:liveinput:ui", setOf(":design")),
                Module(":app", setOf(":design")),
                Module(":feature:liveinput:api", setOf(":design")),
                Module(":feature:liveinput:core", setOf(":design")),
                Module(":feature:liveinput:handlers", setOf(":design")),
                Module(":arch:kernel", setOf(":design")),
                Module(":engine:scenes", setOf(":design")),
            )

        assertEquals(
            listOf(
                ":feature:liveinput:api may not depend on :design",
                ":feature:liveinput:core may not depend on :design",
                ":feature:liveinput:handlers may not depend on :design",
                ":arch:kernel may not depend on :design",
                ":engine:scenes may not depend on :design",
            ),
            FeatureRules.dependenciesFollowTheTable(modules),
        )
    }

    @Test
    fun `the design module depends on no feature, no arch and nothing of the Visual`() {
        val design = Module(":design", setOf(":feature:liveinput:api", ":feature:liveinput:ui", ":arch:kernel", ":engine:scenes"))

        assertEquals(
            listOf(
                ":design may not depend on :feature:liveinput:api",
                ":design may not depend on :feature:liveinput:ui",
                ":design may not depend on :arch:kernel",
                ":design may not depend on :engine:scenes",
            ),
            FeatureRules.dependenciesFollowTheTable(listOf(design)),
        )
    }

    @Test
    fun `handlers that use each other's interface close a circle too`() {
        val modules =
            listOf(
                Module(":feature:liveinput:handlers", setOf(":feature:playback:api")),
                Module(":feature:playback:handlers", setOf(":feature:liveinput:api")),
            )

        assertEquals(emptyList<String>(), FeatureRules.dependenciesFollowTheTable(modules))
        assertEquals(
            listOf("features depend on each other in a cycle: liveinput -> playback -> liveinput"),
            FeatureRules.noFeatureCycles(modules),
        )
    }

    @Test
    fun `features that depend on each other in a circle are reported`() {
        val modules =
            listOf(
                Module(":feature:liveinput:core", setOf(":feature:playback:api")),
                Module(":feature:playback:core", setOf(":feature:library:api")),
                Module(":feature:library:core", setOf(":feature:liveinput:api")),
            )

        assertEquals(
            listOf("features depend on each other in a cycle: library -> liveinput -> playback -> library"),
            FeatureRules.noFeatureCycles(modules),
        )
        assertEquals(emptyList<String>(), FeatureRules.noFeatureCycles(modules.dropLast(1)))
    }

    @Test
    fun `the module graph is read from the build files`() {
        val settings =
            """
            include(":app")
            // include(":old")
            include(
                ":arch:kernel",
                ":feature:liveinput:api",
            )
            """.trimIndent()
        val buildFile =
            """
            dependencies {
                api(project(":arch:kernel"))
                testImplementation(testFixtures(project( ":arch:audio" )))
                // implementation(project(":feature:playback:core"))
            }
            """.trimIndent()

        assertEquals(listOf(":app", ":arch:kernel", ":feature:liveinput:api"), modulesIn(settings))
        assertEquals(setOf(":arch:kernel", ":arch:audio"), dependenciesIn(buildFile))
    }

    private fun source(
        module: String,
        code: String,
    ) = SourceFile(module, module.substringAfter(":feature:").removePrefix(":arch:").replace(':', '/') + "/X.kt", referencesIn(code))
}
