package dev.geode.render.scene

import android.content.Context
import java.io.File

/**
 * Copies the bundled MilkDrop presets (`assets/milk`, listed with their credits in
 * `assets/milk/catalog.json`) into the user's preset folder, and Geode's own starter textures
 * (`assets/milk/textures`, made by `tools/milk_textures/generate.py`, #307) into its `textures`
 * folder, where presets that ask for the common texture names find them. A file already there is left
 * alone, so a preset the user has edited keeps their version, and so is a starter texture whose name
 * the folder already holds under any extension: a preset asks for a texture by name alone. Each
 * version installs once; whatever the person deletes afterwards stays deleted.
 */
object MilkStarterPack {
    private const val ASSET_DIR = "milk"

    private const val TEXTURE_DIR = "textures"

    private const val MARKER = ".starter-pack"

    /** The images MilkDrop can sample, by extension. */
    private val TEXTURE_EXTENSIONS = setOf("png", "jpg", "jpeg", "bmp", "dib", "tga", "dds")

    // 2: the curated set of 50 (issue #93) joined Geode's own 6; installs at 1 copy the new files.
    // 3: Geode's own starter textures (#307).
    const val VERSION: Int = 3

    @Suppress("ReturnCount")
    fun install(
        context: Context,
        target: File = File(context.filesDir, "milk"),
    ): Int {
        if (readMarker(target) >= VERSION) return 0
        if (!target.isDirectory && !target.mkdirs()) return 0
        val written =
            assets(context, ASSET_DIR)
                .filter { it.endsWith(".milk") }
                .filterNot { File(target, it).exists() }
                .count { copy(context, "$ASSET_DIR/$it", File(target, it)) }
        val textures = File(target, TEXTURE_DIR)
        val present = textures.list().orEmpty().toList()
        val wanted = texturesToInstall(assets(context, "$ASSET_DIR/$TEXTURE_DIR"), present)
        if (wanted.isNotEmpty() && !textures.isDirectory) textures.mkdirs()
        val copied = wanted.count { copy(context, "$ASSET_DIR/$TEXTURE_DIR/$it", File(textures, it)) }
        writeMarker(target)
        return written + copied
    }

    /**
     * Which of the starter textures [bundled] (file names) to copy into a textures folder holding
     * [present]: every image whose name, before its extension and in any case, is not there yet.
     */
    fun texturesToInstall(
        bundled: List<String>,
        present: List<String>,
    ): List<String> {
        val taken = present.map(::stem).toSet()
        return bundled.filter { it.substringAfterLast('.', "").lowercase() in TEXTURE_EXTENSIONS && stem(it) !in taken }
    }

    private fun stem(name: String): String = name.substringBeforeLast('.').lowercase()

    private fun assets(
        context: Context,
        dir: String,
    ): List<String> = runCatching { context.assets.list(dir).orEmpty().toList() }.getOrDefault(emptyList())

    private fun copy(
        context: Context,
        assetPath: String,
        destination: File,
    ): Boolean {
        val copied =
            runCatching {
                context.assets.open(assetPath).use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                }
            }.isSuccess
        if (!copied) runCatching { destination.delete() }
        return copied
    }

    private fun readMarker(target: File): Int = runCatching { File(target, MARKER).readText().trim().toInt() }.getOrDefault(0)

    private fun writeMarker(target: File) {
        runCatching { File(target, MARKER).writeText(VERSION.toString()) }
    }
}
