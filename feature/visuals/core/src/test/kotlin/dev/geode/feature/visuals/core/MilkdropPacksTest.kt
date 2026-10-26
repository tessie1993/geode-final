package dev.geode.feature.visuals.core

import dev.geode.feature.visuals.api.MilkdropKind.PRESETS
import dev.geode.feature.visuals.api.MilkdropKind.TEXTURES
import dev.geode.feature.visuals.api.PackLimit
import dev.geode.feature.visuals.api.PackProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which files of a pack come in and as what, how far a pack may go, and where its presets are filed (#399). */
class MilkdropPacksTest {
    private fun take(path: String) = MilkdropPacks.sort(path)

    @Test
    fun `presets and textures come in by their extension, case aside, with the folders they sit in`() {
        assertEquals(PackEntry.Take(PRESETS, emptyList(), "Ocean.milk"), take("Ocean.milk"))
        assertEquals(PackEntry.Take(PRESETS, listOf("Geiss", "Old"), "Ocean.MILK"), take("Geiss/Old/Ocean.MILK"))
        for (extension in listOf("png", "jpg", "jpeg", "bmp", "dib", "tga", "dds", "PNG")) {
            assertEquals(extension, PackEntry.Take(TEXTURES, listOf("textures"), "clouds.$extension"), take("textures/clouds.$extension"))
        }
    }

    @Test
    fun `anything else is skipped and said`() {
        for (path in listOf("readme.txt", "Pack/setup.exe", "Pack/milk", "Pack/Ocean.milk.bak", "Pack/notes")) {
            assertEquals(path, PackEntry.Skip(PackProblem.NOT_MILKDROP), take(path))
        }
    }

    @Test
    fun `a path that leaves the pack or starts at the top is unsafe`() {
        val unsafe =
            listOf(
                "../evil.milk",
                "Pack/../../evil.milk",
                "Pack/..",
                "/etc/evil.milk",
                "\\evil.milk",
                "Pack\\..\\..\\evil.milk",
                "C:/evil.milk",
                "c:evil.milk",
                "Pack/evil\u0000.milk",
                (1..MilkdropPacks.MAX_DEPTH).joinToString("/") { "d$it" } + "/deep.milk",
            )
        for (path in unsafe) {
            assertEquals(path, PackEntry.Skip(PackProblem.UNSAFE_PATH), take(path))
        }
    }

    @Test
    fun `a file as deep as the limit allows still comes in`() {
        val folders = (1 until MilkdropPacks.MAX_DEPTH).map { "d$it" }

        assertEquals(PackEntry.Take(PRESETS, folders, "deep.milk"), take(folders.joinToString("/") + "/deep.milk"))
    }

    @Test
    fun `either slash separates, and empty and dot steps are dropped`() {
        assertEquals(listOf("Pack", "Sub", "a.milk"), MilkdropPacks.segments("Pack\\Sub\\a.milk"))
        assertEquals(listOf("Pack", "a.milk"), MilkdropPacks.segments("./Pack//./a.milk"))
        assertEquals(listOf("a..b.milk"), MilkdropPacks.segments("a..b.milk"))
        assertEquals(emptyList<String>(), MilkdropPacks.segments(""))
        assertNull(MilkdropPacks.segments("a/../b.milk"))
    }

    @Test
    fun `folders and what systems leave in packs are ignored`() {
        for (path in listOf(
            "Pack/",
            "Pack\\",
            "",
            "./",
            "__MACOSX/Pack/._Ocean.milk",
            "Pack/._Ocean.milk",
            "Pack/.DS_Store",
            ".git/x.milk",
        )) {
            assertEquals(path, PackEntry.Ignore, take(path))
        }
        assertEquals(PackEntry.Take(PRESETS, listOf("MACOSX"), "a.milk"), take("MACOSX/a.milk"))
    }

    @Test
    fun `a pack within both limits goes on, and past either it stops`() {
        assertNull(MilkdropPacks.limitReached(0, 0))
        assertNull(MilkdropPacks.limitReached(MilkdropPacks.MAX_ENTRIES, MilkdropPacks.MAX_BYTES))
        assertEquals(PackLimit.ENTRIES, MilkdropPacks.limitReached(MilkdropPacks.MAX_ENTRIES + 1, 0))
        assertEquals(PackLimit.SIZE, MilkdropPacks.limitReached(1, MilkdropPacks.MAX_BYTES + 1))
        assertEquals(PackLimit.ENTRIES, MilkdropPacks.limitReached(MilkdropPacks.MAX_ENTRIES + 1, MilkdropPacks.MAX_BYTES + 1))
    }

    @Test
    fun `a pack is named as it was picked, a zip without its extension`() {
        assertEquals("Cream of the Crop", MilkdropPacks.packName("Cream of the Crop.zip"))
        assertEquals("Pack", MilkdropPacks.packName("  Pack.ZIP "))
        assertEquals("Presets", MilkdropPacks.packName("Presets"))
        assertEquals("Pack", MilkdropPacks.packName("Pack .zip"))
        assertEquals("a.zip.d", MilkdropPacks.packName("a.zip.d"))
        assertEquals(MilkdropPacks.UNNAMED_PACK, MilkdropPacks.packName(".zip"))
        assertEquals(MilkdropPacks.UNNAMED_PACK, MilkdropPacks.packName("  "))
    }

    @Test
    fun `presets are filed in a folder named for the pack, with its own folders inside`() {
        val placed =
            listOf(
                "top.milk" to emptyList(),
                "a.milk" to listOf("Geiss"),
                "b.milk" to listOf("Geiss", "Old"),
                "c.milk" to listOf("Martin"),
            )

        assertEquals(
            mapOf(
                "top.milk" to listOf("Mix"),
                "a.milk" to listOf("Mix", "Geiss"),
                "b.milk" to listOf("Mix", "Geiss", "Old"),
                "c.milk" to listOf("Mix", "Martin"),
            ),
            MilkdropPacks.folders("Mix.zip", placed),
        )
    }

    @Test
    fun `when every preset sits in one folder at the top, that folder is the pack's`() {
        val placed = listOf("a.milk" to listOf("Geiss"), "b.milk" to listOf("Geiss", "Old"))

        assertEquals(
            mapOf("a.milk" to listOf("Geiss"), "b.milk" to listOf("Geiss", "Old")),
            MilkdropPacks.folders("download.zip", placed),
        )
    }

    @Test
    fun `presets all at the top of the pack go straight into its folder`() {
        assertEquals(
            mapOf("a.milk" to listOf("Mix"), "b.milk" to listOf("Mix")),
            MilkdropPacks.folders("Mix", listOf("a.milk" to emptyList(), "b.milk" to emptyList())),
        )
        assertEquals(emptyMap<String, List<String>>(), MilkdropPacks.folders("Mix", emptyList()))
    }
}
