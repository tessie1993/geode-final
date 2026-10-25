package dev.geode.visuals

import android.app.Application
import dev.geode.engine.types.PresetId
import dev.geode.feature.customization.api.PresetPart
import dev.geode.feature.customization.core.BuiltInStyles
import dev.geode.feature.customization.core.Defaults
import dev.geode.feature.customization.core.LegacyEnvelope
import dev.geode.feature.customization.core.LegacyLfo
import dev.geode.feature.customization.core.PresetRules
import dev.geode.feature.customization.handlers.LegacyModulation
import dev.geode.feature.customization.handlers.PresetFormat
import dev.geode.feature.visuals.api.PresetFolderPath
import dev.geode.feature.visuals.api.SavedPreset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The presets the Presets browser reaches (#144, #17): the same files Customization saves, in its
 * format, with their folders. The plain Application keeps Robolectric away from GeodeApp, which loads
 * the engine.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AppPresetFilesRobolectricTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val style = BuiltInStyles.all[0].id
    private val customization = Defaults.customization(BuiltInStyles.catalogue.manifestOf(style))

    private fun text(name: String): String = PresetFormat.encode(PresetRules.capture(name, customization, PresetPart.entries.toSet()))

    private fun files(root: File = folder.root) =
        AppPresetFiles(
            RuntimeEnvironment.getApplication(),
            root,
            object : LegacyModulation {
                override fun lfos() = emptyList<LegacyLfo>()

                override fun envelopes() = emptyList<LegacyEnvelope>()
            },
            style,
        )

    @Test
    fun `an imported preset is listed with its Style, and moves, renames and deletes`() =
        runTest {
            val presets = files()
            assertEquals(SavedPreset(PresetId("Calm"), style), presets.import(text("Calm")) { it })
            presets.createFolder(PresetFolderPath("Mine"))
            presets.move(PresetId("Calm"), PresetFolderPath("Mine"))
            assertEquals(listOf(SavedPreset(PresetId("Calm"), style, PresetFolderPath("Mine"))), presets.saved().presets)
            assertEquals(listOf(PresetFolderPath("Mine")), presets.saved().folders)

            presets.rename(PresetId("Calm"), "Quiet")
            assertEquals(listOf(SavedPreset(PresetId("Quiet"), style, PresetFolderPath("Mine"))), presets.saved().presets)

            presets.delete(PresetId("Quiet"))
            presets.deleteFolder(PresetFolderPath("Mine"))
            assertEquals(emptyList<SavedPreset>(), presets.saved().presets)
            assertEquals(emptyList<PresetFolderPath>(), presets.saved().folders)
        }

    @Test
    fun `an import is saved under the name it is given, and text that is no preset is refused`() =
        runTest {
            val presets = files()
            presets.import(text("Calm")) { "$it (2)" }
            assertEquals(listOf(PresetId("Calm (2)")), presets.saved().presets.map { it.id })
            assertNull(presets.import("not a preset") { it })
        }

    @Test
    fun `a missing preset folder has nothing in it`() =
        runTest {
            val presets = files(File(folder.root, "none"))
            assertEquals(emptyList<SavedPreset>(), presets.saved().presets)
        }
}
