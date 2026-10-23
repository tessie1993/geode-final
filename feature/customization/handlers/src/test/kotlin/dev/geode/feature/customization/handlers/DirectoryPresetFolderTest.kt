package dev.geode.feature.customization.handlers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class DirectoryPresetFolderTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `presets are written, listed with their subfolders, and read back`() {
        val root = File(temp.root, "presets")
        val folder = DirectoryPresetFolder(root)

        folder.write("b.json", "first")
        folder.write("trance/a.json", "second")
        folder.write("b.json", "replaced")
        File(root, "notes.txt").writeText("not a preset")

        assertEquals(listOf("b.json", "trance/a.json"), folder.files())
        assertEquals("replaced", folder.read("b.json"))
        assertEquals("second", folder.read("trance/a.json"))
        assertFalse(root.walkTopDown().any { it.name.endsWith(".partial") })
    }

    @Test
    fun `a folder that does not exist yet holds no presets`() {
        assertEquals(emptyList<String>(), DirectoryPresetFolder(File(temp.root, "missing")).files())
    }

    @Test(expected = IOException::class)
    fun `a path that leaves the folder is refused`() {
        DirectoryPresetFolder(temp.newFolder("presets")).write("../outside.json", "text")
    }

    @Test(expected = IOException::class)
    fun `a preset that is not there cannot be read`() {
        DirectoryPresetFolder(temp.root).read("missing.json")
    }
}
