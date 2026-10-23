package dev.geode.feature.customization.handlers

import dev.geode.engine.types.PresetId
import dev.geode.engine.types.SceneId
import dev.geode.feature.customization.api.Preset
import dev.geode.feature.customization.core.PresetUpgrade
import dev.geode.feature.customization.core.StyleCatalogue
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest

/** What saving a preset came to: [SAVED], or saved but not copied to the mirror folder. */
enum class SaveOutcome {
    SAVED,
    NOT_MIRRORED,
}

/**
 * The preset files (ADR-0004): saved and loaded by name in the [places] Settings chose, as versioned
 * JSON ([PresetFormat]). A preset an older Geode saved is converted with [PresetUpgrade], using the app-wide
 * slots it was played with ([legacy]); a preset on a Style this version no longer draws moves to
 * [fallbackStyle], the app's single default Style. Every call blocks on IO.
 */
class PresetLibrary(
    private val places: PresetPlaces,
    private val legacy: LegacyModulation,
    private val styles: StyleCatalogue,
    private val fallbackStyle: SceneId,
) {
    /**
     * Writes [preset] to the preset folder, replacing a preset of the same name wherever it sits in it,
     * then copies it to the mirror folder. Throws an [IOException] when it could not be saved.
     */
    fun save(preset: Preset): SaveOutcome {
        val saved = places.saved()
        val text = PresetFormat.encode(preset)
        val path = pathOf(saved, preset.name) ?: fileNameOf(preset.name)
        saved.write(path, text)
        val mirror = places.mirror() ?: return SaveOutcome.SAVED
        return runCatching { mirror.write(fileNameOf(preset.name), text) }
            .fold(onSuccess = { SaveOutcome.SAVED }, onFailure = { SaveOutcome.NOT_MIRRORED })
    }

    /**
     * Reads the preset [id] from the preset folder, or from the app's own folder when it is not there.
     * One an older Geode saved is converted as it is opened. Throws an [IOException] when there is no
     * such preset or it cannot be read, and [org.json.JSONException] when the file is not a preset.
     */
    fun load(id: PresetId): Preset {
        val folders = listOf(places.saved(), places.appStorage).distinct()
        for (folder in folders) {
            val path = pathOf(folder, id.value) ?: continue
            return when (val stored = PresetFormat.decode(folder.read(path), fallbackStyle)) {
                is StoredPreset.Current -> stored.preset
                is StoredPreset.Legacy -> upgrade(stored)
            }
        }
        throw FileNotFoundException("no preset named ${id.value}")
    }

    /**
     * Converts every preset an older Geode saved in the app's own folder, once (#11, rounds 4 and 5):
     * the original goes to the backup folder first, and a converted file carries the current version,
     * so it is never converted again. A backup that already exists, from a conversion interrupted
     * after it, is kept as it is. A file that is not a preset at all is left alone, as the older Geode
     * left it. Returns how many were converted; throws an [IOException] if any could not be.
     */
    fun upgradeOld(): Int {
        val folder = places.appStorage
        val backedUp = places.backup.files().toSet()
        var converted = 0
        var failure: IOException? = null
        for (path in folder.files()) {
            try {
                val original = folder.read(path)
                val stored = runCatching { PresetFormat.decode(original, fallbackStyle) }.getOrNull()
                if (stored !is StoredPreset.Legacy) continue
                if (path !in backedUp) places.backup.write(path, original)
                folder.write(path, PresetFormat.encode(upgrade(stored)))
                converted++
            } catch (e: IOException) {
                failure = failure ?: e
            }
        }
        failure?.let { throw it }
        return converted
    }

    private fun upgrade(stored: StoredPreset.Legacy): Preset =
        PresetUpgrade.upgrade(stored.preset, legacy.lfos(), legacy.envelopes(), styles, fallbackStyle).preset

    private fun pathOf(
        folder: PresetFolder,
        name: String,
    ): String? {
        val wanted = fileNameOf(name)
        return folder.files().firstOrNull { it.substringAfterLast('/') == wanted }
    }

    companion object {
        private val UNSAFE = Regex("[^A-Za-z0-9-_ ]")
        private const val HASH_BYTES = 4
        private const val BYTE_MASK = 0xff

        /**
         * The file a preset named [name] is saved as: the name itself when it is safe on every file
         * system, otherwise the name with its unsafe characters replaced and a short hash of the
         * original, so two names never share a file. An older Geode named its files the same way, so
         * its presets are found by their names.
         */
        fun fileNameOf(name: String): String {
            val stem = name.replace(UNSAFE, "_")
            if (stem == name) return "$name.json"
            val hash =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(name.toByteArray(Charsets.UTF_8))
                    .take(HASH_BYTES)
                    .joinToString("") { "%02x".format(it.toInt() and BYTE_MASK) }
            return "$stem-$hash.json"
        }
    }
}
