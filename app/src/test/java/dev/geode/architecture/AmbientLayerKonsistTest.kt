package dev.geode.architecture

import com.lemonappdev.konsist.api.Konsist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The two lines the floating assets must never cross (issue #72): the ambient layer is App UI only,
 * so it never takes audio input and is never shown on the Player over the Visual.
 *
 * Named `*KonsistTest` so the mutation run leaves it out: it reads files, not the classes under
 * mutation.
 */
class AmbientLayerKonsistTest {
    private val root: File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }

    private val ambientSources by lazy {
        Konsist
            .scopeFromDirectory(File(root, AMBIENT_DIR).relativeTo(root).path)
            .files
            .toList()
    }

    @Test
    fun `the scan reaches the ambient layer`() {
        assertTrue("no ambient source was read", ambientSources.any { it.path.endsWith(LAYER_FILE) })
    }

    @Test
    fun `the ambient layer takes no audio input and knows nothing of the Visual`() {
        val breaks =
            ambientSources.flatMap { file ->
                file.imports
                    .map { it.name }
                    .filter { imported -> FORBIDDEN_IMPORTS.any { imported.startsWith(it) } }
                    .map { "${file.path} imports $it" }
            }
        assertEquals(emptyList<String>(), breaks)
    }

    @Test
    fun `the ambient layer never sits on the Player or over the Visual`() {
        val breaks =
            UI_SOURCE_DIRS
                .flatMap { dir -> File(root, dir).walkTopDown().toList() }
                .filter { it.isFile && it.extension == "kt" && it.path.contains(MAIN_SOURCES) }
                .filter { file ->
                    val text = file.readText()
                    text.contains(LAYER_CALL) && (file.name.contains(PLAYER) || VISUAL_HOSTS.any { text.contains(it) })
                }.map { it.relativeTo(root).path }
        assertEquals(emptyList<String>(), breaks)
    }

    private companion object {
        const val AMBIENT_DIR = "design/src/main/java/dev/geode/design/ambient"
        const val LAYER_FILE = "AmbientLayer.kt"
        const val LAYER_CALL = "AmbientLayer("
        const val MAIN_SOURCES = "/src/main/"
        const val PLAYER = "Player"

        /** Where the App UI is written: the app and every feature's screens. */
        val UI_SOURCE_DIRS = listOf("app/src/main", "feature")

        /** What hosts the Visual: a screen that names one of these shows the Visual. */
        val VISUAL_HOSTS = listOf("VisualizerView", "GLSurfaceView", "SurfaceView")

        /** Audio, analysis and the Visual's packages, and Android's audio and microphone APIs. */
        val FORBIDDEN_IMPORTS =
            listOf(
                "dev.geode.audio",
                "dev.geode.analysis",
                "dev.geode.render",
                "dev.geode.engine",
                "dev.geode.arch",
                "dev.geode.feature",
                "android.media",
                "android.speech",
            )
    }
}
