package dev.geode.feature.customization.core

import dev.geode.feature.customization.api.ExtraKind
import dev.geode.feature.customization.api.Macro
import dev.geode.feature.customization.api.MacroLink
import dev.geode.feature.customization.api.SharedProperty
import dev.geode.feature.customization.api.SharedProperty.AUDIO_DRIVE
import dev.geode.feature.customization.api.SharedProperty.SPEED
import dev.geode.feature.customization.api.SharedProperty.ZOOM
import dev.geode.feature.customization.api.Target
import dev.geode.feature.customization.core.Fixtures.julia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URL

class IgnoredControlsTest {
    private val milkDropIgnores = setOf(SPEED, AUDIO_DRIVE)

    private fun link(property: SharedProperty) = MacroLink(Target.Shared(property), 0f, 1f)

    private val extraLink = MacroLink(Target.Extra(ExtraKind.SCALE), 0f, 1f)

    @Test
    fun `a family's ignores read as Shared core properties, unknown names left out`() {
        // names declared -> properties
        val table: List<Pair<List<String>, Set<SharedProperty>>> =
            listOf(
                emptyList<String>() to emptySet(),
                listOf("SPEED") to setOf(SPEED),
                listOf("SPEED", "AUDIO_DRIVE") to setOf(SPEED, AUDIO_DRIVE),
                listOf("SPEED", "WARP_DRIVE") to setOf(SPEED),
                listOf("speed") to emptySet(),
            )
        table.forEach { (names, properties) -> assertEquals(names.toString(), properties, IgnoredControls.properties(names)) }
    }

    @Test
    fun `each ignored property is greyed out, and nothing when the Style reads them all`() {
        assertEquals(emptySet<Target>(), IgnoredControls.targets(julia.copy(macros = List(6) { Macro("m$it") }), emptySet()))
        val greyed = IgnoredControls.targets(julia.copy(macros = List(6) { Macro("m$it") }), milkDropIgnores)
        assertEquals(setOf(Target.Shared(SPEED), Target.Shared(AUDIO_DRIVE)), greyed)
    }

    @Test
    fun `a Macro is greyed out only when every link it has moves an ignored property`() {
        // the Macro's links -> greyed out on a Style that ignores Speed and Audio drive
        val table: List<Pair<List<MacroLink>, Boolean>> =
            listOf(
                emptyList<MacroLink>() to false,
                listOf(link(SPEED)) to true,
                listOf(link(SPEED), link(AUDIO_DRIVE)) to true,
                listOf(link(SPEED), link(ZOOM)) to false,
                listOf(link(ZOOM)) to false,
                listOf(link(SPEED), extraLink) to false,
                listOf(MacroLink(Target.MacroSlot(1), 0f, 1f)) to false,
            )
        table.forEach { (links, greyed) ->
            val macros = listOf(Macro("first"), Macro("linked", links = links)) + List(4) { Macro("rest$it") }
            val targets = IgnoredControls.targets(julia.copy(macros = macros), milkDropIgnores)
            assertEquals(links.toString(), greyed, Target.MacroSlot(1) in targets)
            assertEquals(links.toString(), false, Target.MacroSlot(0) in targets)
        }
    }

    @Test
    fun `a Macro whose links are all on ignored properties is greyed out wherever it sits`() {
        val macros =
            List(6) { index ->
                if (index % 2 == 0) Macro("speed $index", links = listOf(link(SPEED))) else Macro("zoom $index", links = listOf(link(ZOOM)))
            }
        val targets = IgnoredControls.targets(julia.copy(macros = macros), milkDropIgnores)
        assertEquals(setOf(0, 2, 4), targets.filterIsInstance<Target.MacroSlot>().map { it.index }.toSet())
    }

    /** Every name a family ignores in the shipped packs is a real Shared core property, so none is silently dropped. */
    @Test
    fun `the shipped families ignore only real Shared core properties`() {
        val families = File(repositoryRoot(), "engine/scenes/src/main/stylepacks/styles/families.json").readText()
        val lists = Regex("\"ignores\"\\s*:\\s*\\[([^\\]]*)]").findAll(families).map { it.groupValues[1] }.toList()
        assertTrue("families.json declares no ignores", lists.isNotEmpty())
        val names = lists.flatMap { list -> Regex("\"([^\"]*)\"").findAll(list).map { it.groupValues[1] }.toList() }
        val known = SharedProperty.entries.map { it.name }.toSet()
        assertEquals(emptyList<String>(), names.filterNot { it in known })
        // Its reactivity profile (#319) may sit between the name and the ignores.
        val milkDrop =
            Regex("\"name\"\\s*:\\s*\"milkdrop\",(\\s*\"reactivity\"\\s*:\\s*\\{[^}]*},)?\\s*\"ignores\"\\s*:\\s*\\[[^\\]]*\"SPEED\"")
        assertTrue("MilkDrop paces itself and takes raw sound", milkDrop.containsMatchIn(families))
    }

    // The repository root above the working directory, or above the compiled test class when a runner
    // (Pitest) starts somewhere else.
    private fun repositoryRoot(): File =
        listOf(File(System.getProperty("user.dir")), File(compiledClassLocation().toURI()))
            .flatMap { start -> generateSequence(start.absoluteFile) { it.parentFile }.toList() }
            .first { File(it, "settings.gradle.kts").isFile && File(it, "engine/scenes").isDirectory }

    private fun compiledClassLocation(): URL = javaClass.protectionDomain.codeSource.location
}
