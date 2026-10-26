package dev.geode.feature.customization.ui

import dev.geode.feature.customization.api.ValueKind
import dev.geode.feature.customization.api.ValueKind.CONTINUOUS
import dev.geode.feature.customization.api.ValueKind.STEPPED
import dev.geode.feature.customization.api.ValueKind.SWITCH
import org.junit.Assert.assertEquals
import org.junit.Test

class NudgeTest {
    private data class Case(
        val value: Float,
        val min: Float,
        val max: Float,
        val kind: ValueKind,
        val up: Boolean,
        val coarse: Boolean,
        val expected: Float,
    )

    @Test
    fun `fine and coarse steps move by a share of the range and stay inside it`() {
        // value, range, kind, direction, size -> moved
        val table =
            listOf(
                Case(0.5f, 0f, 1f, CONTINUOUS, up = true, coarse = false, expected = 0.51f),
                Case(0.5f, 0f, 1f, CONTINUOUS, up = false, coarse = false, expected = 0.49f),
                Case(0.5f, 0f, 1f, CONTINUOUS, up = true, coarse = true, expected = 0.6f),
                Case(0.5f, 0f, 1f, CONTINUOUS, up = false, coarse = true, expected = 0.4f),
                // Zoom, 0.3..3: a fine step is 0.027.
                Case(1f, 0.3f, 3f, CONTINUOUS, up = true, coarse = false, expected = 1.027f),
                Case(0.995f, 0f, 1f, CONTINUOUS, up = true, coarse = false, expected = 1f),
                Case(0.05f, 0f, 1f, CONTINUOUS, up = false, coarse = true, expected = 0f),
                Case(1f, 0f, 1f, CONTINUOUS, up = true, coarse = true, expected = 1f),
                // Palette, 0..20: one at a time, or two.
                Case(3f, 0f, 20f, STEPPED, up = true, coarse = false, expected = 4f),
                Case(3f, 0f, 20f, STEPPED, up = false, coarse = false, expected = 2f),
                Case(3f, 0f, 20f, STEPPED, up = true, coarse = true, expected = 5f),
                Case(3f, 0f, 20f, STEPPED, up = false, coarse = true, expected = 1f),
                Case(1f, 0f, 20f, STEPPED, up = false, coarse = true, expected = 0f),
                // Particle kind, 0..3: a coarse step is still at least one.
                Case(1f, 0f, 3f, STEPPED, up = true, coarse = true, expected = 2f),
                Case(3f, 0f, 3f, STEPPED, up = true, coarse = false, expected = 3f),
                // The colour map starts at -1 (off).
                Case(0f, -1f, 4f, STEPPED, up = false, coarse = false, expected = -1f),
                Case(-1f, -1f, 4f, STEPPED, up = false, coarse = false, expected = -1f),
                Case(0f, 0f, 1f, SWITCH, up = true, coarse = false, expected = 1f),
                Case(1f, 0f, 1f, SWITCH, up = false, coarse = true, expected = 0f),
                Case(1f, 0f, 1f, SWITCH, up = true, coarse = false, expected = 1f),
            )
        table.forEach { case ->
            val moved = nudged(case.value, case.min, case.max, case.kind, case.up, case.coarse)
            assertEquals(case.toString(), case.expected, moved, TOLERANCE)
        }
    }

    private companion object {
        const val TOLERANCE = 1e-4f
    }
}
