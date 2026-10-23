package dev.geode.feature.visuals.handlers

import dev.geode.feature.visuals.api.MilkdropKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** The MilkDrop folder on disk, as the engine reads it: presets at the top, textures in `textures`. */
class FileMilkdropFolderTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val root: File by lazy { File(temporary.root, FileMilkdropFolder.FOLDER) }
    private val folder by lazy { FileMilkdropFolder(root) }

    @Test
    fun `a folder not made yet holds nothing`() =
        runBlocking {
            assertEquals(emptyList<String>(), folder.files(MilkdropKind.PRESETS))
            assertEquals(emptyList<String>(), folder.files(MilkdropKind.TEXTURES))
        }

    @Test
    fun `presets go at the top and textures in their own folder`() =
        runBlocking {
            folder.write(MilkdropKind.PRESETS, "Ocean.milk", byteArrayOf(1, 2))
            folder.write(MilkdropKind.TEXTURES, "clouds.png", byteArrayOf(3))

            assertArrayEquals(byteArrayOf(1, 2), File(root, "Ocean.milk").readBytes())
            assertArrayEquals(byteArrayOf(3), File(root, "textures/clouds.png").readBytes())
            assertEquals(listOf("Ocean.milk"), folder.files(MilkdropKind.PRESETS))
            assertEquals(listOf("clouds.png"), folder.files(MilkdropKind.TEXTURES))
            assertEquals(setOf("Ocean.milk", "textures"), root.list()?.toSet())
        }

    @Test
    fun `a file already there is never written over`() {
        runBlocking { folder.write(MilkdropKind.PRESETS, "Ocean.milk", byteArrayOf(1)) }

        assertThrows(IOException::class.java) { runBlocking { folder.write(MilkdropKind.PRESETS, "Ocean.milk", byteArrayOf(2)) } }

        assertArrayEquals(byteArrayOf(1), File(root, "Ocean.milk").readBytes())
    }

    @Test
    fun `a name taken by a folder is refused, leaves nothing behind and is not listed`() {
        File(root, "textures").apply { mkdirs() }
        File(root, "Ocean.milk").mkdirs()

        assertThrows(IOException::class.java) { runBlocking { folder.write(MilkdropKind.PRESETS, "Ocean.milk", byteArrayOf(1)) } }

        assertEquals(setOf("Ocean.milk", "textures"), root.list()?.toSet())
        assertTrue(File(root, "Ocean.milk").isDirectory)
        assertEquals(emptyList<String>(), runBlocking { folder.files(MilkdropKind.PRESETS) })
    }

    @Test
    fun `a folder that cannot be made is a failure`() {
        root.parentFile.mkdirs()
        root.writeText("not a folder")

        assertThrows(IOException::class.java) { runBlocking { folder.write(MilkdropKind.PRESETS, "Ocean.milk", byteArrayOf(1)) } }
    }
}
