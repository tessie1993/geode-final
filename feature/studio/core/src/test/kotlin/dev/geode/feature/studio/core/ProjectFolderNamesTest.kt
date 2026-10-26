package dev.geode.feature.studio.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** The name a Project's folder gets inside the Save folder (#340). */
class ProjectFolderNamesTest {
    @Test
    fun `a plain name is the folder's name`() {
        assertEquals("Trip", ProjectFolderNames.of("Trip", emptySet()))
        assertEquals("Live at the Lido", ProjectFolderNames.of("  Live at the Lido ", emptySet()))
    }

    @Test
    fun `characters a folder name cannot hold are replaced`() {
        assertEquals("AC_DC", ProjectFolderNames.clean("AC/DC"))
        assertEquals("a_b_c_d_e_f_g_h_i", ProjectFolderNames.clean("a\\b:c*d?e\"f<g>h|i"))
        assertEquals("tab_new_line", ProjectFolderNames.clean("tab\tnew\nline"))
        assertEquals("Café ☕ 2024", ProjectFolderNames.clean("Café ☕ 2024"))
    }

    @Test
    fun `runs of spaces become one, and leading or trailing dots and spaces go`() {
        assertEquals("Summer gigs", ProjectFolderNames.clean("Summer    gigs"))
        assertEquals("hidden", ProjectFolderNames.clean("..hidden"))
        assertEquals("Tour", ProjectFolderNames.clean(" Tour. . "))
        assertEquals("v1.2 cut", ProjectFolderNames.clean("v1.2 cut"))
    }

    @Test
    fun `a name with nothing left is called Project`() {
        assertEquals("Project", ProjectFolderNames.clean(""))
        assertEquals("Project", ProjectFolderNames.clean(" . .. "))
        assertEquals("Project", ProjectFolderNames.clean(".."))
        assertEquals("_", ProjectFolderNames.clean("/"))
    }

    @Test
    fun `a long name is cut, never in the middle of a character`() {
        val long = "x".repeat(ProjectFolderNames.MAX_LENGTH + 10)
        assertEquals("x".repeat(ProjectFolderNames.MAX_LENGTH), ProjectFolderNames.clean(long))
        val emoji = "a".repeat(ProjectFolderNames.MAX_LENGTH - 1) + "😀"
        assertEquals("a".repeat(ProjectFolderNames.MAX_LENGTH - 1), ProjectFolderNames.clean(emoji))
        val cutAtSpace = "b".repeat(ProjectFolderNames.MAX_LENGTH - 1) + " tail"
        assertEquals("b".repeat(ProjectFolderNames.MAX_LENGTH - 1), ProjectFolderNames.clean(cutAtSpace))
    }

    @Test
    fun `a name already taken in the Save folder gets the first free number, whatever its case`() {
        assertEquals("Trip (2)", ProjectFolderNames.of("Trip", setOf("Trip")))
        assertEquals("Trip (2)", ProjectFolderNames.of("Trip", setOf("TRIP")))
        assertEquals("Trip (4)", ProjectFolderNames.of("Trip", setOf("trip", "Trip (2)", "trip (3)")))
        assertEquals("Trip", ProjectFolderNames.of("Trip", setOf("Trip (2)", "Trips")))
        assertEquals("AC_DC (2)", ProjectFolderNames.of("AC/DC", setOf("AC_DC")))
    }
}
