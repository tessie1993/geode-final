package dev.geode.feature.studio.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** App UI screenshots of Studio's library, taken from its previews (ADR-0003). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class)
class StudioScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun projectList() = capture("Studio_project_list") { ProjectListPreview() }

    @Test
    @Config(qualifiers = "+night")
    fun projectListDark() = capture("Studio_project_list_dark") { ProjectListDarkPreview() }

    @Test
    fun projectListEmpty() = capture("Studio_project_list_empty") { ProjectListEmptyPreview() }

    @Test
    fun project() = capture("Studio_project") { ProjectPreview() }

    @Test
    fun projectFiltered() = capture("Studio_project_recordings") { ProjectFilteredPreview() }

    @Test
    fun video() = capture("Studio_video") { VideoPreview() }

    private fun capture(
        name: String,
        content: @Composable () -> Unit,
    ) {
        compose.setContent(content)
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }
}
