package dev.geode.data

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MilkCatalogTest {
    private val sample =
        """
        {
          "schemaVersion": 1,
          "sources": [
            {"id": "geode", "name": "Geode", "licence": "Geode's own"},
            {"id": "up", "name": "Up/stream", "url": "https://example.invalid/up", "commit": "abc", "licence": "MIT"}
          ],
          "presets": [
            {"file": "geode_bloom.milk", "name": "Geode Bloom", "author": "Geode", "source": "geode"},
            {"file": "Someone - long odd name [mix].milk", "name": "Odd", "author": "Someone", "source": "up", "path": "x/y.milk"}
          ]
        }
        """.trimIndent()

    @Test
    fun `a catalog reads its sources and presets`() {
        val catalog = MilkCatalog.parse(sample)

        assertEquals(
            listOf(
                MilkCatalogSource("geode", "Geode", "Geode's own"),
                MilkCatalogSource("up", "Up/stream", "MIT", "https://example.invalid/up", "abc"),
            ),
            catalog.sources,
        )
        assertEquals(
            MilkCatalogEntry("Someone - long odd name [mix].milk", "Odd", "Someone", "up"),
            catalog.entryFor("Someone - long odd name [mix].milk"),
        )
        assertEquals(2, catalog.presets.size)
    }

    @Test
    fun `a listed preset shows its display name and any other its file name`() {
        val catalog = MilkCatalog.parse(sample)

        assertEquals("Odd", catalog.displayNameOf("Someone - long odd name [mix].milk"))
        assertEquals("my import", catalog.displayNameOf("my import.milk"))
        assertNull(catalog.entryFor("my import.milk"))
        assertEquals("my import", MilkCatalog.EMPTY.displayNameOf("my import.milk"))
    }

    @Test
    fun `another schema version is refused`() {
        val newer = sample.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")

        val error = assertThrows(MilkCatalogFormatException::class.java) { MilkCatalog.parse(newer) }
        assertTrue(error.message.orEmpty().contains("schema 2"))
    }

    @Test
    fun `a preset without an author is refused`() {
        val noAuthor = sample.replace("\"author\": \"Someone\", ", "")

        assertThrows(JSONException::class.java) { MilkCatalog.parse(noAuthor) }
    }
}
