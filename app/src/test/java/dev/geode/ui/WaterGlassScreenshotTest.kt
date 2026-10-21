package dev.geode.ui

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.geode.design.forest.ForestMode
import dev.geode.design.forest.ForestShapes
import dev.geode.design.forest.ForestSpacing
import dev.geode.design.forest.ForestTheme
import dev.geode.design.forest.ForestThemeId
import dev.geode.design.forest.LocalForestColors
import dev.geode.design.water.GlassBackdrop
import dev.geode.design.water.GlassBackdropState
import dev.geode.design.water.LiveBlurBudget
import dev.geode.design.water.RippleTier
import dev.geode.design.water.StoneGlass
import dev.geode.design.water.rememberGlassBackdropState
import dev.geode.design.water.rememberWaterRippleState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Frosted stone glass and water ripples (issue #71) in each theme and mode: a text card and a bar
 * of glass blurring the art behind, a third panel that the screen's budget of two live blurs turns
 * into tinted stone, and ripples held mid-flight in each tier (the AGSL shader of Android 13+, the
 * pre-rendered ring of Android 12, and the still pulse for removed animations). No screen uses
 * these components yet; this is where their look is reviewed.
 *
 * The backdrop is a test stand-in for the art-directed scenery: a dark-to-accent wash with one
 * bright mote, the worst case the glass's opacity floor is set for.
 *
 * The plain Application keeps Robolectric away from GeodeApp, which loads the native engine.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class)
class WaterGlassScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun deepForestDark() = capture(ForestThemeId.DeepForest, ForestMode.Dark)

    @Test
    fun deepForestLight() = capture(ForestThemeId.DeepForest, ForestMode.Light)

    @Test
    fun nightForestDark() = capture(ForestThemeId.NightForest, ForestMode.Dark)

    @Test
    fun nightForestLight() = capture(ForestThemeId.NightForest, ForestMode.Light)

    @Test
    fun earthLichenDark() = capture(ForestThemeId.EarthLichen, ForestMode.Dark)

    @Test
    fun earthLichenLight() = capture(ForestThemeId.EarthLichen, ForestMode.Light)

    @Test
    fun noScreenDrawsMoreThanTwoLiveBlurs() {
        lateinit var state: GlassBackdropState
        compose.setContent {
            state = rememberGlassBackdropState()
            ForestTheme {
                Screen(state, shaderRipple = false)
            }
        }
        compose.waitForIdle()
        assertEquals(LiveBlurBudget.MAX_LIVE_BLURS, state.liveBlurs)
    }

    private fun capture(
        theme: ForestThemeId,
        mode: ForestMode,
    ) {
        compose.setContent {
            ForestTheme(theme, mode) {
                Screen(rememberGlassBackdropState(), shaderRipple = mode == ForestMode.Dark)
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/WaterGlass_${theme.name}_${mode.name}.png")
    }

    /**
     * Four glass panels over the backdrop, one of them nested in another. Only two may blur live;
     * the nested one draws no glass at all.
     */
    @Composable
    private fun Screen(
        state: GlassBackdropState,
        shaderRipple: Boolean,
    ) {
        val colors = LocalForestColors.current
        val type = MaterialTheme.typography
        GlassBackdrop(
            modifier = Modifier.size(SCREEN_WIDTH, SCREEN_HEIGHT),
            state = state,
            backdrop = { Backdrop() },
        ) {
            Column(
                Modifier.fillMaxSize().padding(ForestSpacing.ExtraLarge),
                verticalArrangement = Arrangement.spacedBy(ForestSpacing.Large),
            ) {
                StoneGlass(Modifier.fillMaxWidth(), ripples = null) {
                    Column(Modifier.padding(ForestSpacing.ExtraLarge)) {
                        Text("River stones", style = type.headlineSmall, color = colors.textPrimary)
                        Text("Artist · 12 tracks · 48 min", style = type.bodyMedium, color = colors.textSecondary)
                        StoneGlass(Modifier.padding(top = ForestSpacing.Small), ripples = null) {
                            Text("Glass in glass draws no glass", style = type.labelLarge, color = colors.accent)
                        }
                    }
                }
                HeldRipple(if (shaderRipple) RippleTier.Shader else RippleTier.Ring, "Touched glass ripples")
                HeldRipple(RippleTier.Pulse, "Third panel: tinted stone, no blur")
            }
        }
    }

    @Composable
    private fun HeldRipple(
        tier: RippleTier,
        label: String,
    ) {
        val colors = LocalForestColors.current
        val ripple = rememberWaterRippleState(tier)
        val density = LocalDensity.current
        LaunchedEffect(ripple) {
            with(density) {
                val size = IntSize(PANEL_WIDTH.roundToPx(), PANEL_HEIGHT.roundToPx())
                ripple.hold(Offset(size.width * RIPPLE_X, size.height * RIPPLE_Y), size, RIPPLE_PROGRESS)
            }
        }
        StoneGlass(
            modifier = Modifier.size(PANEL_WIDTH, PANEL_HEIGHT),
            shape = ForestShapes.Card,
            ripples = ripple,
        ) {
            Text(
                label,
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
            )
        }
    }

    @Composable
    private fun Backdrop() {
        val colors = LocalForestColors.current
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(colors.base, colors.accent, colors.highlight, colors.base))),
        ) {
            Canvas(Modifier.fillMaxWidth().height(SCREEN_HEIGHT)) {
                drawCircle(Color.White, radius = MOTE_RADIUS.toPx(), center = Offset(size.width * MOTE_X, size.height * MOTE_Y))
            }
        }
    }

    private companion object {
        val SCREEN_WIDTH = 360.dp
        val SCREEN_HEIGHT = 640.dp
        val PANEL_WIDTH = 312.dp
        val PANEL_HEIGHT = 140.dp
        val MOTE_RADIUS = 46.dp
        const val MOTE_X = 0.72f
        const val MOTE_Y = 0.2f
        const val RIPPLE_X = 0.35f
        const val RIPPLE_Y = 0.45f
        const val RIPPLE_PROGRESS = 0.35f
    }
}
