package dev.geode.feature.diagnostics.handlers

import dev.geode.feature.diagnostics.core.ReportFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class CacheReportFilesTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val granted = mutableListOf<File>()

    private fun files(directory: File) =
        CacheReportFiles(directory) { file ->
            granted += file
            "content://test/${file.name}"
        }

    @Test
    fun `the report is written to one file in its directory and granted`() {
        val directory = File(temporary.root, REPORT_DIRECTORY)

        val file = files(directory).write("first")

        assertEquals(ReportFile("content://test/geode-diagnostics.txt"), file)
        assertEquals(listOf(File(directory, "geode-diagnostics.txt")), granted)
        assertEquals("first", granted.single().readText())
    }

    @Test
    fun `a new report replaces the last and leaves nothing half written`() {
        val directory = temporary.newFolder(REPORT_DIRECTORY)
        val files = files(directory)

        files.write("first")
        files.write("second")

        assertEquals(listOf("geode-diagnostics.txt"), directory.list().orEmpty().toList())
        assertEquals("second", File(directory, "geode-diagnostics.txt").readText())
    }

    @Test(expected = IOException::class)
    fun `a directory that cannot be made is an error`() {
        val blocker = temporary.newFile("blocker")

        files(File(blocker, REPORT_DIRECTORY)).write("text")
    }

    @Test
    fun `nothing is granted when the write fails`() {
        val blocker = temporary.newFile("blocker")

        runCatching { files(File(blocker, REPORT_DIRECTORY)).write("text") }

        assertFalse(granted.isNotEmpty())
    }
}
