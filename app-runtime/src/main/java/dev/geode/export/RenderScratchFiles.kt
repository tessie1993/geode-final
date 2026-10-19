package dev.geode.export

import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Owns scratch paths for one application process. Keep one instance in the application container.
 * Cleanup can run after rendering starts: it never visits this process's directory.
 */
class RenderScratchFiles(
    private val cacheDir: File,
) {
    private val directory = File(cacheDir, "$DIRECTORY_PREFIX${UUID.randomUUID()}")

    /**
     * Allocates a unique path; the encoder creates the file. In particular, Transformer expects
     * a new output path. Callers still delete their own files when a render finishes or fails.
     */
    fun newFile(
        prefix: String,
        suffix: String,
    ): File {
        if (!directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Cannot create render scratch directory")
        }
        return File(directory, "$prefix${UUID.randomUUID()}$suffix")
    }

    /** Reclaims previous-process files, including the old cache-root layout. Run on an IO thread. */
    fun sweepStale() {
        cacheDir.listFiles().orEmpty().forEach { file ->
            when {
                file == directory -> Unit
                file.isDirectory && file.name.startsWith(DIRECTORY_PREFIX) -> file.deleteRecursively()
                file.isFile && LEGACY_PREFIXES.any(file.name::startsWith) -> file.delete()
            }
        }
    }

    private companion object {
        const val DIRECTORY_PREFIX = "geode-render-"
        val LEGACY_PREFIXES = listOf("geode_aac_", "geode_loop_", "studio-")
    }
}
