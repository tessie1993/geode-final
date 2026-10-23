package dev.geode.feature.customization.handlers

import dev.geode.engine.bridge.CustomizationSnapshot
import dev.geode.feature.customization.api.Customization
import dev.geode.feature.customization.api.SharedProperty
import java.io.FileNotFoundException
import java.io.IOException

/** A preset folder in memory; [broken] makes every write fail, as a full or revoked folder would. */
class MemoryPresetFolder(
    val contents: MutableMap<String, String> = mutableMapOf(),
    var broken: Boolean = false,
    val made: MutableSet<String> = mutableSetOf(),
) : PresetFolder {
    override fun files(): List<String> = contents.keys.filter { it.endsWith(".json") }.sorted()

    override fun read(path: String): String = contents[path] ?: throw FileNotFoundException(path)

    override fun write(
        path: String,
        text: String,
    ) {
        if (broken) throw IOException("the folder refused $path")
        contents[path] = text
    }

    override fun delete(path: String) {
        contents.remove(path)
    }

    override fun folders(): List<String> =
        (made + contents.keys.filter { '/' in it }.map { it.substringBeforeLast('/') }).sorted()

    override fun makeFolder(path: String) {
        made += path
    }

    override fun deleteFolder(path: String) {
        if (contents.keys.any { it.startsWith("$path/") }) throw IOException("$path is not empty")
        made.remove(path)
    }
}

class MemoryPlaces(
    var saved: PresetFolder? = null,
    var mirror: MemoryPresetFolder? = null,
    override val appStorage: MemoryPresetFolder = MemoryPresetFolder(),
    override val backup: MemoryPresetFolder = MemoryPresetFolder(),
) : PresetPlaces {
    override fun saved(): PresetFolder = saved ?: appStorage

    override fun mirror(): PresetFolder? = mirror
}

/**
 * A stand-in for `:app`'s snapshot: the scene parameters carry only the Customization's Zoom, in the
 * first slot, so a test sees exactly when a change reaches the engine.
 */
fun zoomSnapshot(customization: Customization): CustomizationSnapshot =
    CustomizationSnapshot(
        revision = 0,
        reducedMotion = false,
        params = List(CustomizationSnapshot.PARAMS_COUNT) { if (it == 0) customization.shared.getValue(SharedProperty.ZOOM) else 0f },
        lfo = List(CustomizationSnapshot.LFO_COUNT) { 0f },
        adsr = List(CustomizationSnapshot.ADSR_COUNT) { 0f },
        plan = List(CustomizationSnapshot.PLAN_COUNT) { 0f },
        soundSensitivity = 3f,
        soundRefractoryMs = 60f,
        soundAttackSeconds = 0.01f,
        soundReleaseSeconds = 0.15f,
    )

/** An old preset file as an older Geode wrote it (`PresetStore.toJson`), with the fields that matter here. */
fun oldPresetFile(
    name: String,
    style: String,
    zoom: Double = 1.0,
    extra: String = "",
): String =
    """
    {
      "name": "$name",
      "sceneId": "$style",
      "attack": 0.1,
      "decay": 0.4,
      "zoom": $zoom,
      "speed": 1.0,
      "strobe": 0.0,
      "kaleidoscope": true$extra
    }
    """.trimIndent()
