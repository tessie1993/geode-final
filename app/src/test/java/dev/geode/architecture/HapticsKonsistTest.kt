package dev.geode.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every slider, switch and checkbox in the App UI plays the haptic set (#398, #417): outside `:design`
 * a raw Material `Slider` ticks through `rememberTicking`, a `RangeSlider` through
 * `rememberRangeTicking`, and a `Switch` or `Checkbox` toggles through `rememberToggling` (or leaves
 * `onCheckedChange` null for a row that plays it). `GlassSlider` and `GlassSwitchRow` already do. Read
 * from the source text of every feature's `:ui` and of `:app`.
 *
 * Named `*KonsistTest` so the mutation run leaves it out (see FeatureArchitectureKonsistTest).
 */
class HapticsKonsistTest {
    private val root: File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }

    private fun sources(): List<File> {
        val features = File(root, "feature").listFiles().orEmpty().map { File(it, "ui/src/main") }
        return (features + File(root, "app/src/main"))
            .filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
    }

    @Test
    fun `the scan reaches the screens`() {
        assertTrue("no :ui source was read", sources().size > MIN_SOURCES)
    }

    @Test
    fun `every raw slider, switch and checkbox plays the haptic set`() {
        val silent =
            sources().flatMap { file ->
                silentControls(file.readText()).map { "${file.relativeTo(root).path}: $it" }
            }

        assertEquals(emptyList<String>(), silent)
    }

    @Test
    fun `the scan reports a planted silent control and passes a playing one`() {
        val code =
            """
            Slider(value = v, onValueChange = { v = it })
            Slider(value = v, onValueChange = rememberTicking(v, 0f..1f) { v = it })
            GlassSlider(value = v, onValueChange = { v = it })
            RangeSlider(value = r, onValueChange = { r = it })
            Switch(checked = on, onCheckedChange = { on = it })
            Switch(checked = on, onCheckedChange = null)
            Checkbox(checked = on, onCheckedChange = rememberToggling { on = it })
            """.trimIndent()

        assertEquals(listOf("Slider at 1", "RangeSlider at 4", "Switch at 5"), silentControls(code))
    }

    private companion object {
        const val MIN_SOURCES = 20
        val control = Regex("""(?<![\w.])(Slider|RangeSlider|Switch|Checkbox)\(""")
        val plays =
            mapOf(
                "Slider" to Regex("""rememberTicking"""),
                "RangeSlider" to Regex("""rememberRangeTicking"""),
                "Switch" to Regex("""rememberToggling|onCheckedChange\s*=\s*null"""),
                "Checkbox" to Regex("""rememberToggling|onCheckedChange\s*=\s*null"""),
            )

        /** Each control in [code] whose arguments play no haptic, as "<name> at <line>". */
        fun silentControls(code: String): List<String> =
            control
                .findAll(code)
                .filterNot { match -> plays.getValue(match.groupValues[1]).containsMatchIn(arguments(code, match.range.last)) }
                .map { match -> "${match.groupValues[1]} at ${code.substring(0, match.range.first).count { it == '\n' } + 1}" }
                .toList()

        /** The text of the argument list whose opening parenthesis is at [open]. */
        fun arguments(
            code: String,
            open: Int,
        ): String {
            var depth = 0
            for (i in open until code.length) {
                when (code[i]) {
                    '(' -> depth++
                    ')' -> if (--depth == 0) return code.substring(open, i)
                }
            }
            return code.substring(open)
        }
    }
}
