package dev.geode.visuals

import dev.geode.engine.types.MilkdropStyles
import dev.geode.engine.types.SceneId
import dev.geode.render.scene.SceneIds
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** What the engine draws for a Style (#305): MilkDrop presets through the MilkDrop scene, everything else as itself. */
class StyleTargetsTest {
    private val folder = File("/data/milk")

    @Test
    fun `a Style that is not a MilkDrop preset is its own scene, with no preset`() {
        assertEquals(StyleTarget(SceneId("tunnel"), null), StyleTargets.of(SceneId("tunnel"), folder))
        assertEquals(StyleTarget(SceneIds.MILKDROP, null), StyleTargets.of(SceneIds.MILKDROP, folder))
        assertEquals(SceneId("tunnel"), StyleTargets.sceneOf(SceneId("tunnel")))
    }

    @Test
    fun `a MilkDrop preset's Style is the MilkDrop scene playing that preset from the folder`() {
        val ocean = MilkdropStyles.of("Ocean Deep.milk")

        assertEquals(StyleTarget(SceneIds.MILKDROP, File(folder, "Ocean Deep.milk")), StyleTargets.of(ocean, folder))
        assertEquals(SceneIds.MILKDROP, StyleTargets.sceneOf(ocean))
    }

    @Test
    fun `a preset name that is not a plain file of the folder plays nothing`() {
        for (name in listOf("../secrets.milk", "sub/Ocean.milk", "..", ".")) {
            assertEquals(name, StyleTarget(SceneIds.MILKDROP, null), StyleTargets.of(MilkdropStyles.of(name), folder))
        }
    }
}
