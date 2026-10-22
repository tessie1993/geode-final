package dev.geode.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * The presets the app bundles (`assets/milk`) agree with their catalog and their credits (issue
 * #93): Geode's own 6 plus the curated 50, each with a display name and an author, none of them
 * needing a texture the app does not ship or a function projectM does not have, and every
 * third-party one credited in the shipped notices. Whether each one renders as written is checked
 * on projectM itself, by the "MilkDrop Renders are frame-exact" CI job.
 */
class BundledMilkPresetsTest {
    private val milkDir = File(ASSETS, "milk")
    private val catalogJson = JSONObject(File(milkDir, "catalog.json").readText())
    private val catalog = MilkCatalog.parse(catalogJson.toString())
    private val notices = File(ASSETS, "third_party_notices.txt").readText()

    private fun milkFiles(): List<File> = milkDir.listFiles { f -> f.isFile && f.extension == "milk" }.orEmpty().toList()

    @Test
    fun `every bundled preset file is in the catalog and every catalog entry has its file`() {
        val files = milkFiles().map { it.name }.toSortedSet()
        val listed = catalog.presets.map { it.file }

        assertEquals("a file is listed twice", listed.size, listed.toSet().size)
        assertEquals(files, listed.toSortedSet())
    }

    @Test
    fun `the set is Geode's own 6 plus 8 MIT presets and 42 from Cream of the Crop`() {
        val bySource = catalog.presets.groupingBy { it.source }.eachCount()

        assertEquals(mapOf(GEODE to GEODE_COUNT, M0RF30 to M0RF30_COUNT, CREAM to CREAM_COUNT), bySource)
        assertEquals(catalog.sources.map { it.id }.toSet(), bySource.keys)
        assertEquals("MIT", catalog.sources.single { it.id == M0RF30 }.licence)
        assertEquals(M0RF30_COMMIT, catalog.sources.single { it.id == M0RF30 }.commit)
        assertEquals(CREAM_COMMIT, catalog.sources.single { it.id == CREAM }.commit)
    }

    @Test
    fun `every preset has a short unique display name and an author`() {
        val names = catalog.presets.map { it.name }

        assertEquals("display names repeat", names.size, names.toSet().size)
        catalog.presets.forEach { p ->
            assertTrue("${p.file}: blank name", p.name.isNotBlank())
            assertTrue("${p.file}: '${p.name}' is longer than $MAX_DISPLAY_NAME", p.name.length <= MAX_DISPLAY_NAME)
            assertTrue("${p.file}: no author", p.author.isNotBlank())
        }
    }

    @Test
    fun `third-party presets are byte for byte what was taken from upstream`() {
        val entries = catalogJson.getJSONArray("presets")
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            if (entry.getString("source") == GEODE) continue
            val file = entry.getString("file")
            assertTrue("$file: no upstream path", entry.getString("path").isNotBlank())
            assertEquals("$file was changed", entry.getString("sha256"), sha256(File(milkDir, file)))
        }
    }

    @Test
    fun `no preset needs an external texture or get_fft`() {
        milkFiles().forEach { file ->
            val text = file.readText(Charsets.ISO_8859_1)
            assertTrue("${file.name} uses get_fft, which projectM does not have", GET_FFT !in text)
            val samplers =
                SAMPLER
                    .findAll(text)
                    .map { it.groupValues[1].replace(WRAP_PREFIX, "") }
                    .toSet()
            assertEquals("${file.name} needs a texture the app does not ship", emptySet<String>(), samplers - BUILT_IN_SAMPLERS)
        }
    }

    @Test
    fun `the notices carry the MIT notice and credit every third-party preset`() {
        assertTrue(notices.contains(M0RF30_COPYRIGHT))
        assertTrue(notices.contains(M0RF30_COMMIT))
        assertTrue(notices.contains(CREAM_COMMIT))
        assertTrue("the notices must say Cream of the Crop has no licence", notices.contains(CREAM_NO_LICENCE))
        catalog.presets.filter { it.source != GEODE }.forEach { p ->
            assertTrue("${p.file} is not credited", notices.contains("\"${p.file}\""))
            assertTrue("${p.author} is not credited", notices.contains(p.author))
        }
    }

    private fun sha256(file: File): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        // Unit tests run in the module directory.
        val ASSETS = File("src/main/assets")
        const val GEODE = "geode"
        const val M0RF30 = "m0rf30"
        const val CREAM = "cream-of-the-crop"
        const val GEODE_COUNT = 6
        const val M0RF30_COUNT = 8
        const val CREAM_COUNT = 42
        const val M0RF30_COMMIT = "ec5b03901e5715e2ddb30b7d1b0c3b7bf1a9b845"
        const val CREAM_COMMIT = "0180df21f5e0bd39b9060cc5de420ed2f1f9e509"
        const val M0RF30_COPYRIGHT = "Copyright (c) 2026 Gianluca Boiano (M0Rf30)"
        const val CREAM_NO_LICENCE = "They have no licence."
        const val MAX_DISPLAY_NAME = 24
        const val GET_FFT = "get_fft"
        val SAMPLER = Regex("""sampler_(\w+)""")

        // fw_, fc_, pw_ and pc_ pick the filtering and wrap mode of the same texture.
        val WRAP_PREFIX = Regex("""^(fw|fc|pw|pc)_""")

        // The textures projectM makes itself: the previous frame, its blurs and the noise volumes.
        val BUILT_IN_SAMPLERS =
            setOf(
                "main",
                "blur1",
                "blur2",
                "blur3",
                "noise_lq",
                "noise_lq_lite",
                "noise_mq",
                "noise_hq",
                "noisevol_lq",
                "noisevol_hq",
            )
    }
}
