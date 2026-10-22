package dev.geode.ui

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.geode.design.CrystalBackground
import dev.geode.design.CrystalButton
import dev.geode.design.CrystalNavBar
import dev.geode.design.CrystalNavItem
import dev.geode.design.CrystalPlayButton
import dev.geode.design.CrystalSegmented
import dev.geode.design.CrystalSlider
import dev.geode.design.theme.CrystalTheme
import dev.geode.design.theme.StoneComponent
import dev.geode.design.theme.StoneIcon
import dev.geode.design.theme.StoneState
import dev.geode.design.theme.StoneSurfaceArt
import dev.geode.design.theme.ThemePackCatalog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Water Element's skin (#105): its controls on the water, and every part in every state. All of
 * it is drawn in code until the art lands, so this is where its look is reviewed.
 *
 * The plain Application keeps Robolectric away from GeodeApp, which loads the native engine.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class)
class WaterThemeScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun controls() {
        compose.setContent {
            CrystalTheme(pack = ThemePackCatalog.water) {
                Box {
                    CrystalBackground(Modifier.matchParentSize())
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CrystalButton(onClick = {}) { Text("Play all") }
                        CrystalButton(onClick = {}, filled = false) { Text("Shuffle") }
                        CrystalButton(onClick = {}, compact = true) { Text("Queue") }
                        CrystalButton(onClick = {}, enabled = false) { Text("Disabled") }
                        CrystalPlayButton(StoneIcon.PLAY, "Play", onClick = {})
                        CrystalSegmented(listOf("Songs", "Albums", "Artists"), selected = 1, onSelect = {})
                        CrystalSlider(value = 0.4f, onValueChange = {}, modifier = Modifier.width(280.dp))
                        CrystalNavBar(
                            items =
                                listOf(
                                    CrystalNavItem("Library", StoneIcon.LIBRARY),
                                    CrystalNavItem("Player", StoneIcon.PLAY),
                                    CrystalNavItem("Settings", StoneIcon.SETTINGS),
                                ),
                            selected = 1,
                            onSelect = {},
                            opacity = 1f,
                        )
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/WaterTheme_controls.png")
    }

    @Test
    fun parts() {
        compose.setContent {
            CrystalTheme(pack = ThemePackCatalog.water) {
                Box {
                    CrystalBackground(Modifier.matchParentSize())
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (component in StoneComponent.entries) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (state in StoneState.entries) {
                                    StoneSurfaceArt(component, state, Modifier.size(width = 64.dp, height = 36.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/WaterTheme_parts.png")
    }
}
