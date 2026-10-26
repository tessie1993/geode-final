package dev.geode.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A Recording captures tilt as well as touch, so it looks like what was on screen (#397, the owner's
 * decision). It holds by construction, and this keeps it that way:
 *
 * - the Recording copies the very frame the live renderer has just drawn to the window, which the
 *   fingers and the phone's tilt have already moved, rather than drawing a picture of its own;
 * - nothing on the Recording's path stills tilt or touch, and the host runs the tilt sensor on the
 *   same terms whether a Recording is under way or not.
 *
 * Read as files, so the mutation run leaves it out (see FeatureArchitectureKonsistTest).
 */
class RecordingKeepsTiltKonsistTest {
    private val root: File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }

    private fun read(path: String): String = File(root, path).readText()

    @Test
    fun `a Recording copies the frame just drawn to the window, tilt and touch included`() {
        val renderer = read("engine/scenes/src/main/kotlin/dev/geode/render/VisualizerRenderer.kt")
        val drawFrame = renderer.substringAfter("override fun onDrawFrame(").substringBefore("\n    }\n")
        val drawn = drawFrame.indexOf("nativeViz.render(")
        val copied = drawFrame.indexOf("record()")
        assertTrue("onDrawFrame draws the Visual", drawn >= 0)
        assertTrue("onDrawFrame copies what it drew into the Recording, after drawing it", copied > drawn)
        // The copy is a blit of the window's own framebuffer, not a second render.
        val target = read("engine/scenes/src/main/kotlin/dev/geode/render/RecordingTarget.kt")
        assertTrue(target.contains("glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0)"))
        assertTrue(target.contains("glBlitFramebuffer("))
        assertEquals(false, target.contains("nativeViz"))
    }

    @Test
    fun `nothing on the Recording's path stills tilt or touch`() {
        val held =
            RECORDING_PATH.filter { path ->
                STILLS.containsMatchIn(read(path))
            }
        assertEquals(emptyList<String>(), held)
    }

    @Test
    fun `the tilt sensor runs on the same terms while a Recording is under way`() {
        val host = read("app/src/main/java/dev/geode/ui/VisualizerCanvasHost.kt")
        assertEquals(false, Regex("(?i)record").containsMatchIn(host))
        assertTrue(host.contains("val wanted = tilt > 0f && !powerSave"))
    }

    private companion object {
        val RECORDING_PATH =
            listOf(
                "app/src/main/java/dev/geode/studio/LiveVisualRecorder.kt",
                "app/src/main/java/dev/geode/studio/RecordingEncoder.kt",
                "app/src/main/java/dev/geode/studio/RecordingMath.kt",
                "app/src/main/java/dev/geode/ui/RecordingHost.kt",
                "engine/scenes/src/main/kotlin/dev/geode/render/RecordingTarget.kt",
                "feature/studio/core/src/main/kotlin/dev/geode/feature/studio/core/Recordings.kt",
                "feature/studio/handlers/src/main/kotlin/dev/geode/feature/studio/handlers/RecordingPorts.kt",
            )

        /** Anything that would hold the phone's tilt or the fingers still, or reset them. */
        val STILLS = Regex("""(?i)gravity|\btilt|submitTouch|touchField|reducedMotion|reduceMotion""")
    }
}
