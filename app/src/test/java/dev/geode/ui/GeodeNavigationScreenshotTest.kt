package dev.geode.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.geode.design.theme.CrystalTheme
import dev.geode.design.theme.ThemePackCatalog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The navigation bar and the wide-screen rail, Home first and selected (#136). Navigation is the
 * same in every Element, so one theme covers it.
 *
 * The plain Application keeps Robolectric away from GeodeApp, which loads the native engine.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class)
class GeodeNavigationScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun bar() {
        compose.setContent {
            CrystalTheme(pack = ThemePackCatalog.lapisLazuli) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.width(BAR_WIDTH)) {
                        GeodeNavBar(geodeNavEntries(showsStudio = true), GeodeDestination.HOME, {}, opacity = 1f)
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/GeodeNavigation_bar.png")
    }

    @Test
    fun rail() {
        compose.setContent {
            CrystalTheme(pack = ThemePackCatalog.lapisLazuli) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.height(RAIL_HEIGHT)) {
                        GeodeNavRail(geodeNavEntries(showsStudio = true), GeodeDestination.HOME, {})
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/GeodeNavigation_rail.png")
    }

    private companion object {
        val BAR_WIDTH = 400.dp
        val RAIL_HEIGHT = 520.dp
    }
}
