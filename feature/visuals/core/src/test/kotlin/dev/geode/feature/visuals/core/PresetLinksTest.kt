package dev.geode.feature.visuals.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream

/** Preset share links (#144): made and read as Geode has always made them. */
class PresetLinksTest {
    private val text = """{"version":2,"name":"Calm","style":"tunnel"}"""

    /** A link carrying [bytes] gzipped, made by hand. */
    private fun linkOf(bytes: ByteArray): String {
        val packed = ByteArrayOutputStream()
        GZIPOutputStream(packed).use { it.write(bytes) }
        return "geode://preset/" + Base64.getUrlEncoder().withoutPadding().encodeToString(packed.toByteArray())
    }

    @Test
    fun `a link carries the preset text there and back`() {
        val link = PresetLinks.encode(text)

        assertTrue(link.startsWith("geode://preset/"))
        assertFalse(link.contains('='))
        assertEquals(text, PresetLinks.decode(link))
    }

    @Test
    fun `a link is the gzipped text in URL-safe Base64, so links an older Geode made still open`() {
        assertEquals(linkOf(text.toByteArray()), PresetLinks.encode(text))
        assertEquals("ünïcode ✓", PresetLinks.decode(linkOf("ünïcode ✓".toByteArray())))
    }

    @Test
    fun `a link is read whatever its case and spaces, and without what follows a hash or question mark`() {
        val link = PresetLinks.encode(text)
        val payload = link.removePrefix("geode://preset/")

        assertEquals(text, PresetLinks.decode("  GEODE://Preset/$payload \n"))
        assertEquals(text, PresetLinks.decode("$link#more"))
        assertEquals(text, PresetLinks.decode("$link?from=chat"))
    }

    @Test
    fun `what is not a preset link reads as nothing`() {
        assertNull(PresetLinks.decode("https://example.com/preset/abc"))
        assertNull(PresetLinks.decode("geode://preset/"))
        assertNull(PresetLinks.decode("geode://preset/!!not base64!!"))
        assertNull(PresetLinks.decode("geode://preset/" + Base64.getUrlEncoder().encodeToString("plain".toByteArray())))
        assertNull(PresetLinks.decode(linkOf("   ".toByteArray())))
    }

    @Test
    fun `a link longer than the longest Geode makes is not read`() {
        val link = PresetLinks.encode(text)
        val padded = link + "#" + "x".repeat(PresetLinks.MAX_LINK_LENGTH - link.length - 1)

        assertEquals(PresetLinks.MAX_LINK_LENGTH, padded.length)
        assertEquals(text, PresetLinks.decode(padded))
        assertNull(PresetLinks.decode(padded + "x"))
    }

    @Test
    fun `a link that unpacks past the limit is refused`() {
        val atLimit = ByteArray(PresetLinks.MAX_TEXT_BYTES) { 'a'.code.toByte() }

        assertEquals(PresetLinks.MAX_TEXT_BYTES, PresetLinks.decode(linkOf(atLimit))?.length)
        assertNull(PresetLinks.decode(linkOf(atLimit + 'a'.code.toByte())))
    }

    @Test
    fun `whether a link fits`() {
        assertTrue(PresetLinks.fits("x".repeat(PresetLinks.MAX_LINK_LENGTH)))
        assertFalse(PresetLinks.fits("x".repeat(PresetLinks.MAX_LINK_LENGTH + 1)))
    }

    @Test
    fun `the first link in a message is found, up to the next space`() {
        assertEquals("geode://preset/abc", PresetLinks.findIn("try geode://preset/abc and geode://preset/def"))
        assertEquals("GEODE://PRESET/abc", PresetLinks.findIn("GEODE://PRESET/abc"))
        assertEquals("geode://preset/abc", PresetLinks.findIn("geode://preset/abc\tnext"))
        assertNull(PresetLinks.findIn("no link here"))
    }
}
