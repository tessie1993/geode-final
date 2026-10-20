package dev.geode.architecture

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Proves each architecture rule bites: a small fixture that breaks it is reported, and a fixture
 * that follows it is not. The real build is checked by [FeatureArchitectureKonsistTest].
 */
class FeatureRulesTest {
    // Two features laid out exactly as ADR-0005 says, with capture's core and handlers using
    // playback's interface.
    private val cleanBuild =
        listOf(
            Module(":app", setOf(":feature:capture:handlers", ":feature:capture:ui", ":arch:kernel")),
            Module(":arch:kernel", setOf(":engine:audio-core")),
            Module(":arch:audio", setOf(":engine:audio-core")),
            Module(":feature:playback:api", setOf(":arch:kernel")),
            Module(":feature:playback:core", setOf(":arch:kernel")),
            Module(":feature:capture:api", setOf(":arch:kernel")),
            Module(":feature:capture:core", setOf(":arch:kernel", ":feature:playback:api")),
            Module(
                ":feature:capture:handlers",
                setOf(":feature:capture:core", ":feature:capture:api", ":arch:audio", ":feature:playback:api"),
            ),
            Module(":feature:capture:ui", setOf(":feature:capture:api")),
        )

    @Test
    fun `a build laid out as the table says passes every rule`() {
        val sources = listOf(source(":feature:capture:core", "import kotlinx.coroutines.flow.StateFlow"))

        assertEquals(emptyList<String>(), FeatureRules.all(cleanBuild, sources))
    }

    @Test
    fun `a pure module that uses Android is reported, whether imported or written out`() {
        val imported = source(":feature:capture:core", "import android.media.AudioRecord")
        val written = source(":arch:kernel", "val x = androidx.compose.runtime.mutableStateOf(1)")
        val inApi = source(":feature:capture:api", "import org.jetbrains.compose.ui.Modifier")

        assertEquals(
            listOf(
                "capture/core/X.kt: :feature:capture:core uses android.media.AudioRecord",
                "kernel/X.kt: :arch:kernel uses androidx.compose.runtime.mutableStateOf",
                "capture/api/X.kt: :feature:capture:api uses org.jetbrains.compose.ui.Modifier",
            ),
            FeatureRules.pureModulesUseNoAndroid(listOf(imported, written, inApi)),
        )
    }

    @Test
    fun `Android in comments, strings, screens and handlers is not reported`() {
        val sources =
            listOf(
                source(":feature:capture:core", "// android.os.Build\nval s = \"androidx.compose.ui\""),
                source(":feature:capture:ui", "import androidx.compose.runtime.Composable"),
                source(":feature:capture:handlers", "import android.media.AudioRecord"),
            )

        assertEquals(emptyList<String>(), FeatureRules.pureModulesUseNoAndroid(sources))
    }

    @Test
    fun `screens that reach past their own api are reported`() {
        val ui = Module(":feature:capture:ui", setOf(":feature:capture:core", ":feature:capture:handlers", ":feature:playback:api"))

        assertEquals(
            listOf(
                ":feature:capture:ui may not depend on :feature:capture:core",
                ":feature:capture:ui may not depend on :feature:capture:handlers",
                ":feature:capture:ui may not depend on :feature:playback:api",
            ),
            FeatureRules.dependenciesFollowTheTable(listOf(ui)),
        )
    }

    @Test
    fun `a dependency on another feature's internals is reported from anywhere but the app`() {
        val modules =
            listOf(
                Module(":feature:capture:core", setOf(":feature:playback:core")),
                Module(":feature:capture:handlers", setOf(":feature:playback:handlers", ":feature:playback:core", ":feature:playback:api")),
                Module(":feature:capture:api", setOf(":feature:playback:api", ":arch:audio")),
                Module(":arch:audio", setOf(":feature:playback:api")),
                Module(":engine:scenes", setOf(":feature:playback:api")),
                Module(":feature:capture:extras", setOf(":arch:kernel")),
            )

        assertEquals(
            listOf(
                ":feature:capture:core may not depend on :feature:playback:core",
                ":feature:capture:handlers may not depend on :feature:playback:handlers",
                ":feature:capture:handlers may not depend on :feature:playback:core",
                ":feature:capture:api may not depend on :feature:playback:api",
                ":feature:capture:api may not depend on :arch:audio",
                ":arch:audio may not depend on :feature:playback:api",
                ":engine:scenes may not depend on :feature:playback:api",
                ":feature:capture:extras may not depend on :arch:kernel",
            ),
            FeatureRules.dependenciesFollowTheTable(modules),
        )
    }

    @Test
    fun `handlers that use each other's interface close a circle too`() {
        val modules =
            listOf(
                Module(":feature:capture:handlers", setOf(":feature:playback:api")),
                Module(":feature:playback:handlers", setOf(":feature:capture:api")),
            )

        assertEquals(emptyList<String>(), FeatureRules.dependenciesFollowTheTable(modules))
        assertEquals(
            listOf("features depend on each other in a cycle: capture -> playback -> capture"),
            FeatureRules.noFeatureCycles(modules),
        )
    }

    @Test
    fun `features that depend on each other in a circle are reported`() {
        val modules =
            listOf(
                Module(":feature:capture:core", setOf(":feature:playback:api")),
                Module(":feature:playback:core", setOf(":feature:library:api")),
                Module(":feature:library:core", setOf(":feature:capture:api")),
            )

        assertEquals(
            listOf("features depend on each other in a cycle: capture -> playback -> library -> capture"),
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
                ":feature:capture:api",
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

        assertEquals(listOf(":app", ":arch:kernel", ":feature:capture:api"), modulesIn(settings))
        assertEquals(setOf(":arch:kernel", ":arch:audio"), dependenciesIn(buildFile))
    }

    private fun source(
        module: String,
        code: String,
    ) = SourceFile(module, module.substringAfter(":feature:").removePrefix(":arch:").replace(':', '/') + "/X.kt", referencesIn(code))
}
