package dev.geode.design.element

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.geode.design.forest.PebbleCorner
import dev.geode.design.forest.PebbleCorners
import dev.geode.design.forest.PebbleShape

/**
 * The look of each first-wave Element: the only place its colours, faces, shapes, timings and art
 * are set. Restyling an Element means editing its entry here; no screen or rule changes with it.
 *
 * Water's colours and timings are the ones in its skin design (#105). Wood, Stone and Bioluminescence
 * carry placeholder values, taken from the forest themes they replace, until their own skin designs
 * land. No Element has art yet ([ElementArt.None]), so all of them draw in flat token colours.
 * `ElementContrastRulesTest` holds every set to WCAG AA.
 */
object ElementSkins {
    private val firstWaveType = ElementType(display = SpaceGroteskFamily, text = InterFamily)

    val Water =
        ElementTokens(
            key = "Water",
            colors =
                ElementColors(
                    background = Color(0xFF071A1C),
                    backgroundDeep = Color(0xFF041113),
                    surface = Color(0xFF10282B),
                    surfaceHigh = Color(0xFF173538),
                    textPrimary = Color(0xFFE6F4F1),
                    textSecondary = Color(0xFF9FC2BD),
                    accent = Color(0xFFF2B35E),
                    onAccent = Color(0xFF071A1C),
                    active = Color(0xFF7FD8CF),
                    onActive = Color(0xFF071A1C),
                    secondary = Color(0xFF5FBFA0),
                    glow = Color(0xFFA8F0E6),
                    error = Color(0xFFF08A7A),
                    divider = Color(0xFF2A4A4C),
                ),
            type = firstWaveType,
            shapes = pebbles(button = 22, chip = 16, card = 28, sheet = 36, uneven = 4),
            motion = ElementMotion(pressMillis = 90, releaseMillis = 220, focusMillis = 160, selectMillis = 400, pageMillis = 320),
        )

    val Wood =
        ElementTokens(
            key = "Wood",
            colors =
                ElementColors(
                    background = Color(0xFF0D1A14),
                    backgroundDeep = Color(0xFF08110D),
                    surface = Color(0xFF24302B),
                    surfaceHigh = Color(0xFF2D3B35),
                    textPrimary = Color(0xFFEEF1EA),
                    textSecondary = Color(0xFFB9C4B8),
                    accent = Color(0xFFE8B15A),
                    onAccent = Color(0xFF0D1A14),
                    active = Color(0xFFA9C77F),
                    onActive = Color(0xFF0D1A14),
                    secondary = Color(0xFF7E9A5C),
                    glow = Color(0xFFD9E8B8),
                    error = Color(0xFFF09A86),
                    divider = Color(0xFF3A4842),
                ),
            type = firstWaveType,
            shapes = pebbles(button = 12, chip = 10, card = 14, sheet = 18, uneven = 2),
            motion = ElementMotion(pressMillis = 100, releaseMillis = 240, focusMillis = 180, selectMillis = 320, pageMillis = 360),
        )

    val Stone =
        ElementTokens(
            key = "Stone",
            colors =
                ElementColors(
                    background = Color(0xFF18120E),
                    backgroundDeep = Color(0xFF100C09),
                    surface = Color(0xFF2C2622),
                    surfaceHigh = Color(0xFF37302B),
                    textPrimary = Color(0xFFF1EBE2),
                    textSecondary = Color(0xFFC7BBAA),
                    accent = Color(0xFFF0A57C),
                    onAccent = Color(0xFF18120E),
                    active = Color(0xFFC9D99E),
                    onActive = Color(0xFF18120E),
                    secondary = Color(0xFF9A8F80),
                    glow = Color(0xFFE8E2D6),
                    error = Color(0xFFF29C8C),
                    divider = Color(0xFF4A423B),
                ),
            type = firstWaveType,
            shapes = pebbles(button = 8, chip = 6, card = 10, sheet = 14, uneven = 6),
            motion = ElementMotion(pressMillis = 80, releaseMillis = 200, focusMillis = 140, selectMillis = 280, pageMillis = 300),
        )

    val Bioluminescence =
        ElementTokens(
            key = "Bioluminescence",
            colors =
                ElementColors(
                    background = Color(0xFF060D0B),
                    backgroundDeep = Color(0xFF030806),
                    surface = Color(0xFF122019),
                    surfaceHigh = Color(0xFF1A2B23),
                    textPrimary = Color(0xFFE6F4EF),
                    textSecondary = Color(0xFFA7C2B9),
                    accent = Color(0xFFF2E36B),
                    onAccent = Color(0xFF06231D),
                    active = Color(0xFF5CE6CF),
                    onActive = Color(0xFF06231D),
                    secondary = Color(0xFF3FA58F),
                    glow = Color(0xFFB8FFF0),
                    error = Color(0xFFFF9A8A),
                    divider = Color(0xFF25392F),
                ),
            type = firstWaveType,
            shapes = pebbles(button = 26, chip = 18, card = 30, sheet = 40, uneven = 3),
            motion = ElementMotion(pressMillis = 110, releaseMillis = 260, focusMillis = 200, selectMillis = 480, pageMillis = 400),
        )

    /** Every first-wave Element, in the order the picker shows them. */
    val all: List<ElementTokens> = listOf(Water, Wood, Stone, Bioluminescence)
}

/**
 * A set of organic outlines from one corner size per surface, in dp. [uneven] skews each corner's
 * ellipse a little differently, so no outline is a perfect rectangle or capsule.
 */
private fun pebbles(
    button: Int,
    chip: Int,
    card: Int,
    sheet: Int,
    uneven: Int,
): ElementShapes {
    fun pebble(
        size: Int,
        squareBottom: Boolean = false,
    ): PebbleShape {
        fun corner(
            across: Int,
            down: Int,
        ) = PebbleCorner(across.dp, down.dp)
        val bottom = if (squareBottom) 0 else 1
        return PebbleShape(
            PebbleCorners(
                topStart = corner(size, size - uneven),
                topEnd = corner(size - uneven, size),
                bottomEnd = corner((size + uneven) * bottom, size * bottom),
                bottomStart = corner(size * bottom, (size + uneven) * bottom),
            ),
        )
    }
    return ElementShapes(
        button = pebble(button),
        chip = pebble(chip),
        card = pebble(card),
        sheet = pebble(sheet, squareBottom = true),
        barItem = pebble(chip),
    )
}
