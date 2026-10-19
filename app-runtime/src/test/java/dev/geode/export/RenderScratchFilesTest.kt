package dev.geode.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RenderScratchFilesTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `delayed recovery removes abandoned exports but preserves the current render`() {
        val previousProcess = RenderScratchFiles(temporary.root)
        val abandoned = previousProcess.newFile("studio-", ".mp4").apply { writeText("abandoned") }
        val currentProcess = RenderScratchFiles(temporary.root)
        val active = currentProcess.newFile("studio-", ".mp4").apply { writeText("current") }

        // Model startup cleanup being scheduled late, after the new render has already started.
        currentProcess.sweepStale()

        assertFalse(abandoned.exists())
        assertFalse(requireNotNull(abandoned.parentFile).exists())
        assertEquals("current", active.readText())
        active.appendText(" continues")
        assertEquals("current continues", active.readText())
    }

    @Test
    fun `recovery migrates old scratch without deleting unrelated cache data`() {
        val legacy =
            listOf("geode_aac_old.bin", "geode_loop_old.mp4", "studio-old.mp4")
                .map { File(temporary.root, it).apply { writeText("abandoned") } }
        val artwork = File(temporary.root, "artwork.png").apply { writeText("keep") }
        val unrelatedDirectory = File(temporary.root, "studio-assets").apply { mkdirs() }
        val asset = File(unrelatedDirectory, "image.png").apply { writeText("keep") }
        val currentProcess = RenderScratchFiles(temporary.root)

        currentProcess.sweepStale()

        legacy.forEach { assertFalse(it.exists()) }
        assertTrue(artwork.exists())
        assertTrue(asset.exists())
    }

    @Test
    fun `output paths stay unique and uncreated until an encoder writes them`() {
        val scratch = RenderScratchFiles(temporary.root)
        val first = scratch.newFile("studio-", ".mp4")
        val second = scratch.newFile("studio-", ".mp4")

        assertNotEquals(first, second)
        assertFalse(first.exists())
        assertFalse(second.exists())
        assertEquals(first.parentFile, second.parentFile)
        assertTrue(requireNotNull(first.parentFile).isDirectory)
        scratch.sweepStale()
        first.writeText("encoder output")
        assertEquals("encoder output", first.readText())
    }
}
