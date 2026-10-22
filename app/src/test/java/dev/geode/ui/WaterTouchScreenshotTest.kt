package dev.geode.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.geode.design.elements.water.WaterTouchTiming
import dev.geode.design.elements.water.waterTouch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Water's touch (#154): the ink bloom and ripple ring on a pebble at the start, middle and end of
 * their run, and under reduced motion. The end frames must match a pebble no one touched: the touch
 * leaves nothing behind.
 *
 * The plain Application keeps Robolectric away from GeodeApp, which loads the native engine.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class)
class WaterTouchScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun start() = capture("start", elapsedMs = 0L, reducedMotion = false)

    @Test
    fun middle() = capture("middle", elapsedMs = WaterTouchTiming.BLOOM_MS / 2L, reducedMotion = false)

    @Test
    fun end() = capture("end", elapsedMs = WaterTouchTiming.BLOOM_MS.toLong(), reducedMotion = false)

    @Test
    fun reducedMotionStart() = capture("reduced_start", elapsedMs = 0L, reducedMotion = true)

    @Test
    fun reducedMotionEnd() = capture("reduced_end", elapsedMs = WaterTouchTiming.REDUCED_FADE_MS.toLong(), reducedMotion = true)

    @Test
    fun untouched() = capture("untouched", elapsedMs = null, reducedMotion = false)

    private fun capture(
        name: String,
        elapsedMs: Long?,
        reducedMotion: Boolean,
    ) {
        compose.setContent {
            val touch = with(LocalDensity.current) { Offset(48.dp.toPx(), 30.dp.toPx()) }
            val progress = elapsedMs?.let { WaterTouchTiming.progressAt(it, reducedMotion) } ?: 1f
            Box(Modifier.background(Water).padding(24.dp)) {
                Box(
                    Modifier
                        .size(width = 180.dp, height = 72.dp)
                        .background(Pebble, RoundedCornerShape(36.dp))
                        .waterTouch(progress = { progress }, touch = { touch }, reducedMotion = reducedMotion),
                )
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/WaterTouch_$name.png")
    }

    private companion object {
        val Water = Color(0xFF071A1C)
        val Pebble = Color(0xFF173538)
    }
}
