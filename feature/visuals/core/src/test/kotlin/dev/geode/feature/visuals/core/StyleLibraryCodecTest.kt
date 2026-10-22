package dev.geode.feature.visuals.core

import dev.geode.engine.types.SceneId
import dev.geode.feature.visuals.api.Folder
import dev.geode.feature.visuals.api.FolderId
import dev.geode.feature.visuals.api.StyleLibrary
import dev.geode.feature.visuals.core.Fixtures.ink
import dev.geode.feature.visuals.core.Fixtures.milk
import dev.geode.feature.visuals.core.Fixtures.nested
import dev.geode.feature.visuals.core.Fixtures.one
import dev.geode.feature.visuals.core.Fixtures.three
import dev.geode.feature.visuals.core.Fixtures.tunnel
import dev.geode.feature.visuals.core.Fixtures.two
import org.junit.Assert.assertEquals
import org.junit.Test

/** The saved library's format (ADR-0004: every saved format carries a version). */
class StyleLibraryCodecTest {
    private val library =
        nested.copy(
            favourites = listOf(milk, ink),
            recent = listOf(ink, tunnel),
            folders = nested.folders + Folder(FolderId(4), "Late night: slow & low", null, listOf(ink, milk)),
        )

    private val written =
        """
        geode-style-library 1
        favourite milkdrop
        favourite fluid_ink
        recent fluid_ink
        recent tunnel
        folder 1 - Calm
        style 1 tunnel
        folder 2 1 Deep
        folder 3 2 Deeper
        folder 4 - Late night: slow & low
        style 4 fluid_ink
        style 4 milkdrop
        """.trimIndent()

    @Test
    fun `a library is written line by line under its version`() {
        assertEquals(written, StyleLibraryCodec.encode(library))
    }

    @Test
    fun `what is written reads back the same`() {
        assertEquals(library, StyleLibraryCodec.decode(StyleLibraryCodec.encode(library)))
        assertEquals(StyleLibrary(), StyleLibraryCodec.decode(StyleLibraryCodec.encode(StyleLibrary())))
    }

    @Test
    fun `nothing saved, or something that is no library, reads as an empty one`() {
        assertEquals(StyleLibrary(), StyleLibraryCodec.decode(""))
        assertEquals(StyleLibrary(), StyleLibraryCodec.decode("favourite tunnel"))
        assertEquals(StyleLibrary(), StyleLibraryCodec.decode("geode-style-library x\nfavourite tunnel"))
        assertEquals(StyleLibrary(), StyleLibraryCodec.decode("geode-style-library 0\nfavourite tunnel"))
    }

    @Test
    fun `a newer version is read as far as this one understands it`() {
        val newer = "geode-style-library 7\nfavourite tunnel\nsparkle tunnel 3\nrecent ink"

        assertEquals(StyleLibrary(favourites = listOf(tunnel), recent = listOf(SceneId("ink"))), StyleLibraryCodec.decode(newer))
    }

    @Test
    fun `broken lines are skipped and the rest is kept`() {
        val broken =
            """
            geode-style-library 1
            favourite
            favourite tunnel
            favourite tunnel
            folder x - Bad number
            folder 1 - Calm
            folder 1 - Twice
            folder 2
            folder 3 -${" "}
            style 9 tunnel
            style 1
            style 1 fluid_ink
            style 1 fluid_ink
            """.trimIndent()

        assertEquals(
            StyleLibrary(favourites = listOf(tunnel), folders = listOf(Folder(one, "Calm", null, listOf(ink)))),
            StyleLibraryCodec.decode(broken),
        )
    }

    @Test
    fun `a folder whose parent is missing, or that loops, comes back at the top`() {
        val text =
            """
            geode-style-library 1
            folder 1 3 Loop one
            folder 2 1 Loop two
            folder 3 2 Loop three
            folder 4 9 Orphan
            """.trimIndent()

        assertEquals(
            listOf(
                Folder(one, "Loop one", null, emptyList()),
                Folder(two, "Loop two", one, emptyList()),
                Folder(three, "Loop three", two, emptyList()),
                Folder(FolderId(4), "Orphan", null, emptyList()),
            ),
            StyleLibraryCodec.decode(text).folders,
        )
    }

    @Test
    fun `Recent reads back at most its limit`() {
        val text = "geode-style-library 1\n" + (1..StyleLibrary.RECENT_LIMIT + 3).joinToString("\n") { "recent s$it" }

        assertEquals((1..StyleLibrary.RECENT_LIMIT).map { SceneId("s$it") }, StyleLibraryCodec.decode(text).recent)
    }
}
