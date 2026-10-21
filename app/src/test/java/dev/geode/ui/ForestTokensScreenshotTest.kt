package dev.geode.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.geode.design.forest.ForestGlass
import dev.geode.design.forest.ForestMode
import dev.geode.design.forest.ForestShapes
import dev.geode.design.forest.ForestSpacing
import dev.geode.design.forest.ForestTheme
import dev.geode.design.forest.ForestThemeId
import dev.geode.design.forest.LocalForestColors
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A specimen of the water, forest and stone tokens (issue #70) in each theme and mode: the base,
 * a pebble of text glass, Fraunces over Figtree, and every text colour. No screen uses the tokens
 * yet; this is where their look is reviewed.
 *
 * The plain Application keeps Robolectric away from GeodeApp, which loads the native engine.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class)
class ForestTokensScreenshotTest {
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

    private fun capture(
        theme: ForestThemeId,
        mode: ForestMode,
    ) {
        compose.setContent {
            ForestTheme(theme, mode) {
                val colors = LocalForestColors.current
                val type = MaterialTheme.typography
                Box(Modifier.width(SPECIMEN_WIDTH).background(colors.base).padding(ForestSpacing.ExtraLarge)) {
                    Column(
                        Modifier
                            .background(ForestGlass.textGlass(colors), ForestShapes.Card)
                            .padding(ForestSpacing.ExtraLarge),
                        verticalArrangement = Arrangement.spacedBy(ForestSpacing.Medium),
                    ) {
                        Text("River stones", style = type.displaySmall, color = colors.textPrimary)
                        Text("Album title", style = type.titleMedium, color = colors.textPrimary)
                        Text("Artist · 12 tracks · 48 min", style = type.bodyMedium, color = colors.textSecondary)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(ForestSpacing.Small),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .defaultMinSize(minHeight = ForestSpacing.TapTarget)
                                    .background(colors.accent, ForestShapes.Button)
                                    .padding(horizontal = ForestSpacing.ExtraLarge, vertical = ForestSpacing.Medium),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("Play", style = type.labelLarge, color = colors.onAccent)
                            }
                            Text("Now playing", style = type.labelLarge, color = colors.highlight)
                            Text("Accent", style = type.labelLarge, color = colors.accent)
                        }
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/ForestTokens_${theme.name}_${mode.name}.png")
    }

    private companion object {
        val SPECIMEN_WIDTH = 360.dp
    }
}
