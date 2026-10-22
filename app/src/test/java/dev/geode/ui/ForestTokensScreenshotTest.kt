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
import dev.geode.design.forest.LocalForestColors
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A specimen of the forest tokens (issue #70) in each mode: the base, a pebble of text glass,
 * Fraunces over Figtree, and every text colour. The forest themes became Elements (#103), whose
 * screenshots are Home's and Settings' (feature/home/ui, feature/settings/ui).
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
    fun deepForestDark() = capture(ForestMode.Dark)

    @Test
    fun deepForestLight() = capture(ForestMode.Light)

    private fun capture(mode: ForestMode) {
        compose.setContent {
            ForestTheme(mode) {
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
        compose.onRoot().captureRoboImage("src/test/screenshots/ForestTokens_DeepForest_${mode.name}.png")
    }

    private companion object {
        val SPECIMEN_WIDTH = 360.dp
    }
}
