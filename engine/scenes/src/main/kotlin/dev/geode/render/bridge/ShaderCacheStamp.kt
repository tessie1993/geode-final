package dev.geode.render.bridge

import java.io.File

/**
 * The engine's shader cache (`cache/gl-program-binaries`: program binaries, and MilkDrop shaders
 * translated from HLSL, #304) belongs to one install of the app. The native side already starts over
 * when the GPU driver changes; here, the first time a renderer is made after an update, the whole cache
 * is emptied, so nothing an older Geode compiled or translated is ever used.
 */
object ShaderCacheStamp {
    /** The cache's folder under the app's cache, as the native ProgramBinaryCache names it. */
    const val DIR = "gl-program-binaries"

    /** Where the install the cache belongs to is noted: beside the folder, which the native side prunes. */
    const val STAMP = "gl-program-binaries.install"

    @Volatile
    private var checked = false

    /**
     * Empties [cacheDir]'s shader cache unless it was made by the install [install] names (anything that
     * changes with every install, such as the APK's modification time). Once per process.
     */
    fun forgetIfStale(
        cacheDir: File,
        install: String,
    ) {
        if (checked) return
        synchronized(this) {
            if (checked) return
            checked = true
            val stamp = File(cacheDir, STAMP)
            val stored = runCatching { stamp.readText() }.getOrNull()
            if (!stale(stored, install)) return
            runCatching { File(cacheDir, DIR).deleteRecursively() }
            runCatching { stamp.writeText(install) }
        }
    }

    /** Whether a cache noted as made by [stored] must go for the install [current]. */
    fun stale(
        stored: String?,
        current: String,
    ): Boolean = stored != current
}
