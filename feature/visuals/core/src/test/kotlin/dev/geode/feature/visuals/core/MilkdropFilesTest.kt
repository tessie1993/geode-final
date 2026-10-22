package dev.geode.feature.visuals.core

import dev.geode.feature.visuals.api.BrokenFile
import dev.geode.feature.visuals.api.ImportProblem
import dev.geode.feature.visuals.api.MilkdropKind
import dev.geode.feature.visuals.api.MilkdropKind.PRESETS
import dev.geode.feature.visuals.api.MilkdropKind.TEXTURES
import dev.geode.feature.visuals.api.MilkdropPreset
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which picked files come into the MilkDrop folder, under what name, and how the folder is listed (#145). */
class MilkdropFilesTest {
    private val preset = "MILKDROP_PRESET_VERSION=201\n[preset00]\nfDecay=0.97\n".toByteArray()
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A)
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
    private val bmp = "BM....".toByteArray()
    private val dds = "DDS |....".toByteArray()

    /** A TGA header: colour-map type, image type and pixel depth where a reader finds them. */
    private fun tga(
        colorMap: Int = 0,
        type: Int = 2,
        depth: Int = 32,
        size: Int = 18,
    ): ByteArray =
        ByteArray(size).also {
            it[1] = colorMap.toByte()
            it[2] = type.toByte()
            it[16] = depth.toByte()
        }

    private fun read(
        name: String,
        bytes: ByteArray,
    ) = PickedFile.Read(name, bytes)

    /** The one outcome of picking [file] alone into a folder holding [present]. */
    private fun one(
        kind: MilkdropKind,
        file: PickedFile,
        present: List<String> = emptyList(),
    ): Planned = MilkdropFiles.plan(kind, listOf(file), present).single()

    private fun Planned.written(): Planned.Write = this as? Planned.Write ?: throw AssertionError("refused: $this")

    private fun problem(
        kind: MilkdropKind,
        file: PickedFile,
        present: List<String> = emptyList(),
    ): ImportProblem? = (one(kind, file, present) as? Planned.Refuse)?.broken?.problem

    @Test
    fun `a good preset is written as a milk file, shown by its name`() {
        val planned = one(PRESETS, read("Ocean.milk", preset)).written()

        assertEquals("Ocean.milk", planned.picked)
        assertEquals("Ocean.milk", planned.stored)
        assertEquals("Ocean", planned.shown)
        assertArrayEquals(preset, planned.bytes)
    }

    @Test
    fun `a preset is recognised by its section whatever its case or indent`() {
        assertTrue(MilkdropFiles.isPreset("  [PRESET01]\nzoom=1".toByteArray()))
        assertTrue(MilkdropFiles.isPreset("fRating=3\n[preset]".toByteArray()))
        assertFalse(MilkdropFiles.isPreset("zoom=1\nrot=0".toByteArray()))
        assertFalse(MilkdropFiles.isPreset("x [preset00]".toByteArray()))
        assertFalse(MilkdropFiles.isPreset("[preset00]\u0000\u0001".toByteArray(Charsets.ISO_8859_1)))
    }

    @Test
    fun `broken presets are refused with the reason`() {
        assertEquals(ImportProblem.NOT_A_PRESET, problem(PRESETS, read("notes.milk", "just some notes".toByteArray())))
        assertEquals(ImportProblem.NOT_A_PRESET, problem(PRESETS, read("photo.milk", png)))
        assertEquals(ImportProblem.EMPTY, problem(PRESETS, read("empty.milk", ByteArray(0))))
        assertEquals(ImportProblem.TOO_LARGE, problem(PRESETS, PickedFile.TooLarge("huge.milk")))
        assertEquals(ImportProblem.UNREADABLE, problem(PRESETS, PickedFile.Unreadable("gone.milk")))
    }

    @Test
    fun `a preset without the milk extension still comes in as one`() {
        val planned = one(PRESETS, read("Ocean", preset)).written()

        assertEquals("Ocean.milk", planned.stored)
        assertEquals("Ocean.milk", one(PRESETS, read("Ocean.MILK", preset)).written().stored)
        assertEquals("preset.milk", one(PRESETS, read(".milk", preset)).written().stored)
    }

    @Test
    fun `an unsafe preset name is made safe and kept apart by a hash`() {
        assertEquals("Flexi - don_t panic-e6f00b67.milk", MilkdropFiles.storedName(PRESETS, "Flexi - don't panic.milk"))
        assertEquals("a_b-c14cddc0.milk", MilkdropFiles.storedName(PRESETS, "a/b.milk"))
        assertEquals("Flexi - don_t panic-e6f00b67", one(PRESETS, read("Flexi - don't panic.milk", preset)).written().shown)
    }

    @Test
    fun `a preset already there, built-in or own, is left as it was`() {
        assertEquals(ImportProblem.ALREADY_THERE, problem(PRESETS, read("Ocean.milk", preset), listOf("ocean.milk")))
        // A built-in's own name is taken even though importing it would make that name safe first.
        assertEquals(
            ImportProblem.ALREADY_THERE,
            problem(PRESETS, read("Flexi - don't panic.milk", preset), listOf("Flexi - don't panic.milk")),
        )
        assertEquals(ImportProblem.ALREADY_THERE, problem(PRESETS, read("Ocean", preset), listOf("Ocean.milk")))
        // Taken under the safe name it would be kept under, from an earlier import of the same file.
        assertEquals(ImportProblem.ALREADY_THERE, problem(PRESETS, read("a/b.milk", preset), listOf("a_b-c14cddc0.milk")))
        assertTrue(one(PRESETS, read("Ocean.milk", preset), listOf("Ocean2.milk")) is Planned.Write)
    }

    @Test
    fun `good textures of every type come in under their own name`() {
        val good =
            listOf(
                read("clouds.png", png),
                read("sky.JPG", jpeg),
                read("rose.jpeg", jpeg),
                read("worms.bmp", bmp),
                read("dib.dib", bmp),
                read("skull.dds", dds),
                read("pic.tga", tga()),
            )

        val planned = MilkdropFiles.plan(TEXTURES, good, emptyList()).map { it.written().stored }

        assertEquals(listOf("clouds.png", "sky.jpg", "rose.jpeg", "worms.bmp", "dib.dib", "skull.dds", "pic.tga"), planned)
    }

    @Test
    fun `a texture shows as its file name`() {
        assertEquals("clouds.png", one(TEXTURES, read("clouds.png", png)).written().shown)
    }

    @Test
    fun `an unsafe texture name is made safe and kept apart by a hash`() {
        assertEquals("my_tex_0c0d16cd.png", MilkdropFiles.storedName(TEXTURES, "my tex.png"))
        assertEquals("t2_clouds_5ff01a05.png", MilkdropFiles.storedName(TEXTURES, "2 clouds.png"))
        assertEquals("tex_e3b0c442.png", MilkdropFiles.storedName(TEXTURES, ".png"))
        assertEquals("clouds.png", MilkdropFiles.storedName(TEXTURES, "clouds.PNG"))
    }

    @Test
    fun `broken textures are refused with the reason`() {
        assertEquals(ImportProblem.WRONG_TYPE, problem(TEXTURES, read("clip.gif", png)))
        assertEquals(ImportProblem.WRONG_TYPE, problem(TEXTURES, read("noextension", png)))
        assertEquals(ImportProblem.WRONG_TYPE, problem(TEXTURES, PickedFile.Unreadable("clip.webp")))
        assertEquals(ImportProblem.NOT_AN_IMAGE, problem(TEXTURES, read("fake.png", jpeg)))
        assertEquals(ImportProblem.NOT_AN_IMAGE, problem(TEXTURES, read("fake.jpg", png)))
        assertEquals(ImportProblem.NOT_AN_IMAGE, problem(TEXTURES, read("fake.bmp", dds)))
        assertEquals(ImportProblem.NOT_AN_IMAGE, problem(TEXTURES, read("fake.dds", bmp)))
        assertEquals(ImportProblem.NOT_AN_IMAGE, problem(TEXTURES, read("short.png", png.copyOf(3))))
        assertEquals(ImportProblem.EMPTY, problem(TEXTURES, read("empty.png", ByteArray(0))))
        assertEquals(ImportProblem.TOO_LARGE, problem(TEXTURES, PickedFile.TooLarge("huge.dds")))
        assertEquals(ImportProblem.UNREADABLE, problem(TEXTURES, PickedFile.Unreadable("gone.png")))
    }

    @Test
    fun `a TGA is told by its header`() {
        assertTrue(MilkdropFiles.isImage("tga", tga(colorMap = 1, type = 10, depth = 24)))
        for (type in listOf(1, 3, 9, 11)) assertTrue("type $type", MilkdropFiles.isImage("tga", tga(type = type)))
        for (depth in listOf(8, 15, 16)) assertTrue("depth $depth", MilkdropFiles.isImage("tga", tga(depth = depth)))
        assertFalse(MilkdropFiles.isImage("tga", tga(colorMap = 2)))
        assertFalse(MilkdropFiles.isImage("tga", tga(type = 0)))
        assertFalse(MilkdropFiles.isImage("tga", tga(type = 4)))
        assertFalse(MilkdropFiles.isImage("tga", tga(depth = 12)))
        assertFalse(MilkdropFiles.isImage("tga", tga(size = 17)))
        assertFalse(MilkdropFiles.isImage("gif", png))
        assertTrue(MilkdropFiles.isImage("dds", "DDS ".toByteArray()))
    }

    @Test
    fun `a texture whose name before the extension is taken is left as it was`() {
        assertEquals(ImportProblem.ALREADY_THERE, problem(TEXTURES, read("Clouds.jpg", jpeg), listOf("clouds.png")))
        // Taken under the name as picked, even though it would be kept under a safe one.
        assertEquals(ImportProblem.ALREADY_THERE, problem(TEXTURES, read("my tex.png", png), listOf("my tex.png")))
        assertTrue(one(TEXTURES, read("clouds.png", png), listOf("clouds2.png")) is Planned.Write)
    }

    @Test
    fun `one broken file never stops the others, and a name is taken once per pick`() {
        val picked =
            listOf(
                read("a.milk", preset),
                read("broken.milk", "nope".toByteArray()),
                read("A.milk", preset),
                PickedFile.Unreadable("lost.milk"),
                read("b.milk", preset),
            )

        val planned = MilkdropFiles.plan(PRESETS, picked, emptyList())

        assertEquals(listOf("a.milk", null, null, null, "b.milk"), planned.map { (it as? Planned.Write)?.stored })
        assertEquals(
            listOf(
                BrokenFile("broken.milk", ImportProblem.NOT_A_PRESET),
                BrokenFile("A.milk", ImportProblem.ALREADY_THERE),
                BrokenFile("lost.milk", ImportProblem.UNREADABLE),
            ),
            planned.mapNotNull { (it as? Planned.Refuse)?.broken },
        )
    }

    @Test
    fun `each kind is read only so far`() {
        assertEquals(1024 * 1024, MilkdropFiles.limit(PRESETS))
        assertEquals(16 * 1024 * 1024, MilkdropFiles.limit(TEXTURES))
    }

    @Test
    fun `the built-in presets and the person's own are listed together by name`() {
        val aurora = MilkdropPreset("aurora_veil.milk", "Aurora Veil", "Geode", builtIn = true)
        val tidal = MilkdropPreset("tidal_drift.milk", "Tidal Drift", "Geode", builtIn = true)

        val listed = MilkdropFiles.presets(listOf(tidal, aurora), listOf("tidal_drift.milk", "beach.milk", "Zebra.milk", "notes.txt"))

        assertEquals(
            listOf(
                aurora,
                MilkdropPreset("beach.milk", "beach", null, builtIn = false),
                tidal,
                MilkdropPreset("Zebra.milk", "Zebra", null, builtIn = false),
            ),
            listed,
        )
    }

    @Test
    fun `the textures are the images in the folder, by name`() {
        assertEquals(
            listOf("b.JPG", "c.png", "skull.dds"),
            MilkdropFiles.textures(listOf("c.png", "readme.txt", "skull.dds", "b.JPG", ".links")),
        )
    }
}
