package dev.geode.design.forest

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PebbleShapeTest {
    private fun corners() =
        PebbleCorners(
            topStart = PebbleCorner(10.dp, 4.dp),
            topEnd = PebbleCorner(6.dp, 8.dp),
            bottomEnd = PebbleCorner(12.dp, 2.dp),
            bottomStart = PebbleCorner(3.dp, 5.dp),
        )

    private val shape = PebbleShape(corners())
    private val density = Density(DENSITY)

    @Test
    fun `corners are uneven ellipses scaled by density`() {
        val r = shape.roundRect(Size(200f, 100f), LayoutDirection.Ltr, density)

        assertEquals(CornerRadius(20f, 8f), r.topLeftCornerRadius)
        assertEquals(CornerRadius(12f, 16f), r.topRightCornerRadius)
        assertEquals(CornerRadius(24f, 4f), r.bottomRightCornerRadius)
        assertEquals(CornerRadius(6f, 10f), r.bottomLeftCornerRadius)
        assertEquals(200f, r.width, 0f)
        assertEquals(100f, r.height, 0f)
    }

    @Test
    fun `right to left mirrors which corner goes where`() {
        val r = shape.roundRect(Size(200f, 100f), LayoutDirection.Rtl, density)

        assertEquals(CornerRadius(12f, 16f), r.topLeftCornerRadius)
        assertEquals(CornerRadius(20f, 8f), r.topRightCornerRadius)
        assertEquals(CornerRadius(6f, 10f), r.bottomRightCornerRadius)
        assertEquals(CornerRadius(24f, 4f), r.bottomLeftCornerRadius)
    }

    @Test
    fun `a corner never reaches past half its side`() {
        val r = shape.roundRect(Size(16f, 6f), LayoutDirection.Ltr, density)

        assertEquals(CornerRadius(8f, 3f), r.topLeftCornerRadius)
        assertEquals(CornerRadius(8f, 3f), r.topRightCornerRadius)
        assertEquals(CornerRadius(8f, 3f), r.bottomRightCornerRadius)
        assertEquals(CornerRadius(6f, 3f), r.bottomLeftCornerRadius)
    }

    @Test
    fun `pebbles with the same corners are equal`() {
        val same = PebbleShape(corners())

        assertEquals(shape, same)
        assertEquals(shape.hashCode(), same.hashCode())
        assertNotEquals(shape, ForestShapes.Card)
        assertNotEquals(shape, Any())
        assertTrue(shape.toString().startsWith("PebbleShape("))
    }

    @Test
    fun `no shape token is a plain rectangle or a perfect capsule`() {
        for (token in listOf(ForestShapes.Button, ForestShapes.Chip, ForestShapes.Card)) {
            val r = token.roundRect(Size(400f, 400f), LayoutDirection.Ltr, Density(1f))
            val radii =
                listOf(r.topLeftCornerRadius, r.topRightCornerRadius, r.bottomRightCornerRadius, r.bottomLeftCornerRadius)
            assertTrue("$token has a square corner", radii.all { it.x > 0f && it.y > 0f })
            assertTrue("$token is even", radii.toSet().size == radii.size)
        }
        val sheet = ForestShapes.Sheet.roundRect(Size(400f, 400f), LayoutDirection.Ltr, Density(1f))
        assertTrue(sheet.topLeftCornerRadius.x > 0f && sheet.topRightCornerRadius.x > 0f)
        assertEquals(CornerRadius.Zero, sheet.bottomLeftCornerRadius)
        assertEquals(CornerRadius.Zero, sheet.bottomRightCornerRadius)
    }

    private companion object {
        const val DENSITY = 2f
    }
}
