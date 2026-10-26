package dev.geode.feature.visuals.handlers

import dev.geode.feature.visuals.api.PackLimit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Walking a zip pack with `java.util.zip` (#399): every file in order, within the pack limits however the zip is made. */
class ZipPackTest {
    private fun zip(
        vararg entries: Pair<String, ByteArray?>,
        charset: Charset = Charsets.UTF_8,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out, charset).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                bytes?.let { zip.write(it) }
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Each file the walk hands over, by path, with what reading it within [limit] gave; [read] false leaves it unread. */
    private fun walk(
        bytes: ByteArray,
        budget: PackBudget = PackBudget(),
        limit: Int = Int.MAX_VALUE,
        read: Boolean = true,
    ): List<Pair<String, ByteArray?>> {
        val met = mutableListOf<Pair<String, ByteArray?>>()
        runTest {
            ZipPack(ByteArrayInputStream(bytes), budget).walk { file -> met += file.path to (if (read) file.read(limit) else null) }
        }
        return met
    }

    @Test
    fun `every file is handed over in order, by its path, and folders are not`() {
        val met = walk(zip("Mix/" to null, "Mix/a.milk" to "a".toByteArray(), "Mix/textures/b.png" to "bb".toByteArray()))

        assertEquals(listOf("Mix/a.milk", "Mix/textures/b.png"), met.map { it.first })
        assertArrayEquals("a".toByteArray(), met[0].second)
        assertArrayEquals("bb".toByteArray(), met[1].second)
    }

    @Test
    fun `paths that leave the pack are handed over as written, for the rules to refuse`() {
        val met = walk(zip("../evil.milk" to "x".toByteArray(), "/abs.milk" to "y".toByteArray()))

        assertEquals(listOf("../evil.milk", "/abs.milk"), met.map { it.first })
    }

    @Test
    fun `a file larger than its limit reads as nothing, and the walk goes on`() {
        val met = walk(zip("big.png" to ByteArray(100), "small.milk" to ByteArray(5)), limit = 10)

        assertNull(met[0].second)
        assertEquals(5, met[1].second?.size)
    }

    @Test
    fun `past the entry limit nothing more is met`() {
        val budget = PackBudget { entries, _ -> if (entries > 2) PackLimit.ENTRIES else null }

        val met = walk(zip("a/" to null, "a/1.milk" to null, "a/2.milk" to null, "a/3.milk" to null), budget)

        assertEquals(listOf("a/1.milk"), met.map { it.first })
        assertEquals(PackLimit.ENTRIES, budget.stopped)
    }

    @Test
    fun `what a file unpacks to counts even unread, so a zip bomb stops at the size limit`() {
        val budget = PackBudget { _, bytes -> if (bytes > 1000) PackLimit.SIZE else null }

        val met = walk(zip("readme.txt" to ByteArray(5000), "after.milk" to ByteArray(1)), budget, read = false)

        assertEquals(listOf("readme.txt"), met.map { it.first })
        assertEquals(PackLimit.SIZE, budget.stopped)
    }

    @Test
    fun `a file read past the room left reads as nothing, and the walk stops`() {
        val budget = PackBudget { _, bytes -> if (bytes > 1000) PackLimit.SIZE else null }

        val met = walk(zip("a.milk" to ByteArray(600), "b.milk" to ByteArray(600), "c.milk" to ByteArray(1)), budget)

        assertEquals(600, met[0].second?.size)
        assertNull(met[1].second)
        assertEquals(listOf("a.milk", "b.milk"), met.map { it.first })
        assertEquals(PackLimit.SIZE, budget.stopped)
    }

    @Test
    fun `a file that is not a zip holds nothing`() {
        assertEquals(emptyList<String>(), walk("[preset00]\nfDecay=0.9\n".toByteArray()).map { it.first })
    }

    @Test
    fun `names old Windows tools wrote are read in code page 437`() {
        val met = walk(zip("Café/é.milk" to ByteArray(1), charset = Charset.forName("IBM437")))

        assertEquals(listOf("Café/é.milk"), met.map { it.first })
    }

    @Test
    fun `a budget counts entries and bytes, and stays stopped once a limit is reached`() {
        val budget = PackBudget { entries, bytes -> if (entries > 1 || bytes > 10) PackLimit.SIZE else null }

        assertTrue(budget.enter())
        assertTrue(budget.take(10))
        assertNull(budget.stopped)
        assertFalse(budget.take(1))
        assertEquals(PackLimit.SIZE, budget.stopped)
        assertFalse(budget.take(0))
    }

    @Test
    fun `the default budget keeps the pack limits`() {
        val budget = PackBudget()

        assertTrue(budget.take(1))
        assertTrue(budget.enter())
        assertNull(budget.stopped)
    }
}
