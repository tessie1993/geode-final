package dev.geode.architecture

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Proves each architecture rule bites: a small fixture that breaks it is reported, and the same
 * fixture without the break is not. The real code is checked by [FeatureArchitectureKonsistTest].
 */
class FeatureRulesTest {
    private val cleanCapture =
        file(
            "dev.geode.feature.capture.core",
            """
            package dev.geode.feature.capture.core

            import dev.geode.kernel.Next
            import dev.geode.feature.playback.Playback
            import kotlinx.coroutines.flow.StateFlow

            // Comments may mention android.os.Build and strings may say "androidx.compose.ui".
            class CaptureRunner(private val playback: Playback)
            """,
        )
    private val playbackRoot = file("dev.geode.feature.playback", "package dev.geode.feature.playback\n\ninterface Playback")

    @Test
    fun `clean features pass every rule`() {
        assertEquals(emptyList<String>(), FeatureRules.all(listOf(cleanCapture, playbackRoot)))
    }

    @Test
    fun `a feature core that imports Android is reported`() {
        val core = file("dev.geode.feature.capture.core", "import android.media.AudioRecord\nclass X")

        assertEquals(
            listOf("capture/core.kt: feature core uses android.media.AudioRecord"),
            FeatureRules.coreIsPlainKotlin(listOf(core)),
        )
    }

    @Test
    fun `a feature core that uses Compose by its full name is reported`() {
        val core = file("dev.geode.feature.capture.core.model", "val x = androidx.compose.runtime.mutableStateOf(1)")

        assertEquals(
            listOf("capture/core/model.kt: feature core uses androidx.compose.runtime.mutableStateOf"),
            FeatureRules.coreIsPlainKotlin(listOf(core)),
        )
    }

    @Test
    fun `a feature's screens may use Android`() {
        val ui = file("dev.geode.feature.capture.ui", "import androidx.compose.runtime.Composable")

        assertEquals(emptyList<String>(), FeatureRules.coreIsPlainKotlin(listOf(ui)))
    }

    @Test
    fun `a kernel that uses Android is reported`() {
        val kernel = file("dev.geode.kernel", "import android.os.SystemClock")

        assertEquals(
            listOf("kernel.kt: kernel uses android.os.SystemClock"),
            FeatureRules.coreIsPlainKotlin(listOf(kernel)),
        )
    }

    @Test
    fun `a kernel that knows a feature is reported`() {
        val kernel = file("dev.geode.kernel", "import dev.geode.feature.playback.Playback")

        assertEquals(
            listOf("kernel.kt: kernel uses dev.geode.feature.playback.Playback"),
            FeatureRules.kernelKnowsNoFeature(listOf(kernel)),
        )
    }

    @Test
    fun `a screen that uses another feature's internals is reported`() {
        val ui = file("dev.geode.feature.capture.ui", "import dev.geode.feature.playback.core.PlaybackReducer")

        assertEquals(
            listOf("capture/ui.kt: capture uses the internals of another feature: dev.geode.feature.playback.core.PlaybackReducer"),
            FeatureRules.noFeatureReachesIntoAnother(listOf(ui)),
        )
    }

    @Test
    fun `a feature's public interface, its own internals and the kernel are fair to use`() {
        val ui =
            file(
                "dev.geode.feature.capture.ui",
                """
                import dev.geode.feature.capture.core.CaptureState
                import dev.geode.kernel.Feature
                import dev.geode.feature.playback.Playback
                import dev.geode.feature.playback.Playback.State
                import dev.geode.feature.playback.playbackPreview
                """,
            )

        assertEquals(emptyList<String>(), FeatureRules.noFeatureReachesIntoAnother(listOf(ui)))
    }

    @Test
    fun `features that depend on each other in a circle are reported`() {
        val capture = file("dev.geode.feature.capture.core", "import dev.geode.feature.playback.Playback")
        val playback = file("dev.geode.feature.playback.core", "import dev.geode.feature.library.Library")
        val library = file("dev.geode.feature.library.core", "import dev.geode.feature.capture.Capture")

        assertEquals(
            listOf("features depend on each other in a cycle: capture -> playback -> library -> capture"),
            FeatureRules.noFeatureCycles(listOf(capture, playback, library)),
        )
    }

    @Test
    fun `dependencies that all point one way are not a cycle`() {
        val capture = file("dev.geode.feature.capture.core", "import dev.geode.feature.playback.Playback")
        val library = file("dev.geode.feature.library.core", "import dev.geode.feature.playback.Playback")
        val playback = file("dev.geode.feature.playback.core", "import dev.geode.feature.settings.Settings")

        assertEquals(emptyList<String>(), FeatureRules.noFeatureCycles(listOf(capture, library, playback)))
    }

    private fun file(
        packageName: String,
        text: String,
    ) =
        SourceFile(
            path = packageName.removePrefix("dev.geode.feature.").removePrefix("dev.geode.").replace('.', '/') + ".kt",
            packageName = packageName,
            references = referencesIn(text.trimIndent()),
        )
}
